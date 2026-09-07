package io.github.matthewjones372.kestrel.openapi

import io.github.matthewjones372.kestrel.perSecond
import io.github.matthewjones372.kestrel.plan.Declaration
import io.github.matthewjones372.kestrel.plan.DeclaredDraw
import io.github.matthewjones372.kestrel.plan.DeclaredGoal
import io.github.matthewjones372.kestrel.plan.DeclaredLoad
import io.github.matthewjones372.kestrel.plan.DeclaredStep
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import org.snakeyaml.engine.v2.exceptions.MarkedYamlEngineException
import org.snakeyaml.engine.v2.exceptions.YamlEngineException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A plan from an OpenAPI document, for the services that are not built from
 * Pelican values.
 *
 * Read here rather than through `pelican-import`, which is a code generator:
 * it writes Kotlin source and returns the paths it wrote, so there is no parsed
 * model to call. This reads what a plan needs — paths, methods, the facets on a
 * path parameter, and the statuses a response declares — and not the type model
 * a generator has to build. YAML 1.2 is a superset of JSON, so a `.json`
 * document reads through the same parser.
 */
fun planFromDocument(
    document: String,
    baseUrl: String? = null,
    scenario: String = "smoke",
    methods: Set<String> = setOf("get"),
    seed: Long = 0L,
): Declaration {
    val root = document.asOpenApi()

    val paths = root["paths"].asMap()
        ?: throw IllegalArgumentException("the document names no `paths`, so there is nothing to send")

    val components = root["components"].asMap().orEmpty()
    val url = baseUrl ?: root.firstServer() ?: throw IllegalArgumentException(
        "the document names no `servers`, so pass a baseUrl saying where to send this",
    )

    val steps = paths.entries.flatMapIndexed { index, (path, operations) ->
        operations.asMap().orEmpty().entries
            .filter { (verb, _) -> verb.lowercase() in methods }
            .map { (verb, operation) ->
                operation.asMap().orEmpty().asStep(path, verb, components, seed + index)
            }
    }

    require(steps.isNotEmpty()) {
        "no path in the document uses ${methods.joinToString { it.uppercase() }}"
    }

    // Drawn where the contract states the space, substituted where it does
    // not. A parameter of one name declared two different ways in two
    // operations is left to be substituted rather than drawn from whichever
    // was read first.
    val drawn = steps.flatMap { it.draws.entries }
        .groupBy({ it.key }, { it.value })
        .filterValues { it.distinct().size == 1 }
        .mapValues { (_, only) -> only.first() }

    return Declaration(
        version = Declaration.VERSION,
        draw = drawn,
        baseUrl = url.trimEnd('/'),
        scenario = scenario,
        steps = steps.map { it.step },
        load = DeclaredLoad.Constant(SMOKE_RATE.perSecond, SMOKE_WINDOW),
        goals = steps.map { DeclaredGoal.Percentile(it.step.name, "p99", PLACEHOLDER_LIMIT) },
    )
}

/** The same, from a file. */
fun planFromDocument(path: Path, baseUrl: String? = null): Declaration =
    planFromDocument(Files.readString(path, Charsets.UTF_8), baseUrl)

private fun Map<String, Any?>.asStep(
    path: String,
    verb: String,
    components: Map<String, Any?>,
    seed: Long,
): Drawing {
    val responses = this["responses"].asMap().orEmpty().keys.mapNotNull { it.toIntOrNull() }
    val success = responses.firstOrNull { it in SUCCESS } ?: OK

    val parameters = parameters(components)
    val drawn = BRACES.findAll(path).map { it.groupValues[1] }.toList()
        .mapNotNull { name -> drawFor(parameters[name].orEmpty())?.let { name to it } }
        .toMap()

    return Drawing(
        draws = drawn,
        step = DeclaredStep.Request(
            // The operation id where the document gives one, as its own tooling
            // names the row; the verb and template otherwise.
            name = (this["operationId"] as? String)?.takeIf { it.isNotBlank() } ?: "${verb.lowercase()} $path",
            method = verb.uppercase(),
            path = filled(path, parameters, seed, keeping = drawn.keys),
            expecting = success,
            // Every other declared status. A document that says it answers 404 is a
            // service working as written when it does.
            declared = responses.filter { it != success && it in DECLARED }.distinct().sorted(),
        ),
    )
}

/** One step, and the keys its path leaves for a draw to fill. */
internal data class Drawing(val draws: Map<String, DeclaredDraw>, val step: DeclaredStep.Request)

/**
 * The path parameters this operation declares, each with the facets its schema
 * carries, resolved through `$ref` where it uses one.
 */
private fun Map<String, Any?>.parameters(components: Map<String, Any?>): Map<String, Map<String, Any?>> =
    (this["parameters"] as? List<*>).orEmpty()
        .mapNotNull { it.asMap() }
        .map { it.resolved(components) }
        .filter { it["in"] == "path" }
        .mapNotNull { parameter ->
            (parameter["name"] as? String)?.let { name -> name to parameter["schema"].asMap().orEmpty() }
        }
        .toMap()

/** `$ref: '#/components/parameters/OrderId'`, followed one hop, which is what a document written by hand uses. */
private fun Map<String, Any?>.resolved(components: Map<String, Any?>): Map<String, Any?> {
    val reference = this["\$ref"] as? String ?: return this
    val steps = reference.removePrefix("#/").split('/')
    if (steps.firstOrNull() != "components") return this

    return steps.drop(1).fold(components as Any?) { at, key -> at.asMap()?.get(key) }.asMap() ?: this
}

/**
 * Every `{name}` replaced by one legal value, except the ones [keeping] will
 * draw — those keep their braces, which is what the session fills per user.
 */
private fun filled(
    path: String,
    parameters: Map<String, Map<String, Any?>>,
    seed: Long,
    keeping: Set<String>,
): String =
    BRACES.findAll(path).map { it.groupValues[1] }.toList().foldIndexed(path) { index, filling, name ->
        if (name in keeping) filling
        else filling.replace("{$name}", legalFor(facets = parameters[name].orEmpty(), seed = seed + index))
    }

private fun Map<String, Any?>.firstServer(): String? =
    ((this["servers"] as? List<*>)?.firstOrNull()).asMap()?.get("url") as? String

@Suppress("UNCHECKED_CAST")
private fun Any?.asMap(): Map<String, Any?>? = (this as? Map<*, *>)?.entries
    ?.mapNotNull { (key, value) -> (key as? String)?.let { it to value } }
    ?.toMap()

private val BRACES = Regex("""\{([^}]+)}""")
private val SUCCESS = 200..299
private val DECLARED = 400..599
private const val OK = 200
private const val SMOKE_RATE = 1
private val SMOKE_WINDOW = 10.seconds
private val PLACEHOLDER_LIMIT = 1_000.milliseconds

/**
 * The document as the mapping OpenAPI is.
 *
 * The parse and the shape check together, because a parse failure is the
 * parser's own exception type and every caller of this catches
 * `IllegalArgumentException` — so without this a document that is not YAML at
 * all goes past all of them.
 */
private fun String.asOpenApi(): Map<String, Any?> {
    val loaded = try {
        Load(LoadSettings.builder().build()).loadFromString(this)
    } catch (unreadable: YamlEngineException) {
        throw IllegalArgumentException(unreadable.said(), unreadable)
    }

    return loaded.asMap() ?: throw IllegalArgumentException("the document is not a mapping, so it is not OpenAPI")
}

/** A parse failure with its line first, as `readPlan` reports one. */
private fun YamlEngineException.said(): String {
    val where = (this as? MarkedYamlEngineException)?.problemMark?.map { it.line + 1 }?.orElse(null)
    val what = (this as? MarkedYamlEngineException)?.problem ?: message.orEmpty()
    return if (where == null) "this is not YAML: ${message.orEmpty()}" else "line $where: $what"
}

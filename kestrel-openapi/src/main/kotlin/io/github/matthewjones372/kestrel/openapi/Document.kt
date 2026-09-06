package io.github.matthewjones372.kestrel.openapi

import io.github.matthewjones372.kestrel.perSecond
import io.github.matthewjones372.kestrel.plan.Declaration
import io.github.matthewjones372.kestrel.plan.DeclaredGoal
import io.github.matthewjones372.kestrel.plan.DeclaredLoad
import io.github.matthewjones372.kestrel.plan.DeclaredStep
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
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
    val root = Load(LoadSettings.builder().build()).loadFromString(document).asMap()
        ?: throw IllegalArgumentException("the document is not a mapping, so it is not OpenAPI")

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

    return Declaration(
        version = Declaration.VERSION,
        baseUrl = url.trimEnd('/'),
        scenario = scenario,
        steps = steps,
        load = DeclaredLoad.Constant(SMOKE_RATE.perSecond, SMOKE_WINDOW),
        goals = steps.map { DeclaredGoal.Percentile(it.name, "p99", PLACEHOLDER_LIMIT) },
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
): DeclaredStep {
    val responses = this["responses"].asMap().orEmpty().keys.mapNotNull { it.toIntOrNull() }
    val success = responses.firstOrNull { it in SUCCESS } ?: OK

    return DeclaredStep(
        // The operation id where the document gives one, as its own tooling
        // names the row; the verb and template otherwise.
        name = (this["operationId"] as? String)?.takeIf { it.isNotBlank() } ?: "${verb.lowercase()} $path",
        method = verb.uppercase(),
        path = filled(path, parameters(components), seed),
        expecting = success,
        // Every other declared status. A document that says it answers 404 is a
        // service working as written when it does.
        declared = responses.filter { it != success && it in DECLARED }.distinct().sorted(),
    )
}

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

private fun filled(path: String, parameters: Map<String, Map<String, Any?>>, seed: Long): String =
    BRACES.findAll(path).map { it.groupValues[1] }.toList().foldIndexed(path) { index, filling, name ->
        filling.replace("{$name}", legalFor(facets = parameters[name].orEmpty(), seed = seed + index))
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

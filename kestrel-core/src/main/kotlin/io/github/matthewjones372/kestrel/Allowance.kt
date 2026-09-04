package io.github.matthewjones372.kestrel

import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration

/**
 * What a run on this machine may do, as a human committed it beside the build.
 *
 * A fence, not a sandbox. It stops a typed rate and a program's arithmetic, and
 * it stops neither somebody holding the jar nor a caller that constructs its
 * own. [io.github.matthewjones372.kestrel.Limits] is the other direction and
 * keeps its name: that one is what the injector ran *into* while measuring,
 * this is what an operator permits before it starts.
 *
 * Every field absent means unbounded. A tool that fails closed on a machine
 * with no configuration is a tool people delete the configuration to use.
 */
data class Allowance(
    /** Host patterns a run may send to, or empty for any. `*.` matches one or more leading labels. */
    val hosts: List<String> = emptyList(),
    val maxRate: Rate? = null,
    val maxDuration: Duration? = null,
    val maxRequests: Long? = null,
) {

    /** Whether [host] matches any pattern, and true where none was named. */
    fun allows(host: String): Boolean =
        hosts.isEmpty() || hosts.any { it.matchesHost(host) }

    companion object {

        /** Nothing bounded, which is what a machine with no file gets. */
        val none: Allowance = Allowance()

        /** The file beside the build, or [none] where nobody wrote one. */
        fun fromFile(path: Path = Path.of(FILE)): Allowance =
            if (Files.exists(path)) read(Files.readString(path, Charsets.UTF_8)) else none

        /**
         * The same, from text.
         *
         * A hand-read `key = value` subset rather than a TOML library: four keys
         * and a list do not earn a dependency in core, and the failure this has
         * to get right is the message, which is written here.
         */
        fun read(text: String): Allowance {
            val fields = text.lineSequence()
                .mapIndexed { index, line -> index + 1 to line.substringBefore(COMMENT).trim() }
                .filter { (_, line) -> line.isNotEmpty() }
                .map { (number, line) -> number to line.split('=', limit = 2) }
                .onEach { (number, parts) ->
                    require(parts.size == 2) { "line $number is not `key = value`: ${parts.first()}" }
                }
                .map { (number, parts) -> Field(number, parts[0].trim(), parts[1].trim()) }
                .toList()

            fields.forEach { field ->
                require(field.key in KEYS) {
                    "line ${field.number} names `${field.key}`, which is not one of ${KEYS.joinToString()}"
                }
            }

            return Allowance(
                hosts = fields.of("hosts")?.list().orEmpty(),
                maxRate = fields.of("maxRate")?.rate(),
                maxDuration = fields.of("maxDuration")?.duration(),
                maxRequests = fields.of("maxRequests")?.count(),
            )
        }

        private const val FILE = "kestrel.toml"
        private const val COMMENT = '#'
        private val KEYS = listOf("hosts", "maxRate", "maxDuration", "maxRequests")

        private fun List<Field>.of(key: String): Field? = firstOrNull { it.key == key }
    }
}

/** One `key = value`, with the line it came from, so every refusal can name it. */
private data class Field(val number: Int, val key: String, val value: String) {

    fun list(): List<String> = value
        .removeSurrounding("[", "]")
        .split(',')
        .map { it.trim().unquoted() }
        .filter { it.isNotEmpty() }

    fun rate(): Rate {
        val text = value.unquoted()
        val perSecond = when {
            text.endsWith("/s") -> text.removeSuffix("/s").toDoubleOrNull()
            text.endsWith("/m") -> text.removeSuffix("/m").toDoubleOrNull()?.div(SECONDS_A_MINUTE)
            else -> null
        }
        return requireNotNull(perSecond) { "line $number: `$text` is not a rate like \"500/s\" or \"30/m\"" }
            .perSecond
    }

    fun duration(): Duration {
        val text = value.unquoted()
        return requireNotNull(Duration.parseOrNull(text)) { "line $number: `$text` is not a duration like \"10m\"" }
    }

    fun count(): Long {
        val text = value.unquoted().replace("_", "")
        return requireNotNull(text.toLongOrNull()) { "line $number: `$text` is not a whole number of requests" }
    }
}

private fun String.unquoted(): String = trim().removeSurrounding("\"")

/** `*.staging.internal` covers a subdomain of it, and not the domain itself. */
private fun String.matchesHost(host: String): Boolean =
    if (startsWith("*.")) host.endsWith(substring(1)) && host.length > length - 1 else this == host

private const val SECONDS_A_MINUTE = 60.0

/**
 * Why a run was not allowed to start.
 *
 * A value rather than an exception: a caller asking whether it may run was
 * promised this answer, so it belongs in the return type. Each one carries what
 * was asked beside what is allowed, because a refusal that does not say the
 * number to come down to is one somebody guesses at twice.
 */
sealed interface Refusal {

    val described: String

    data class OverRate(val asked: Rate, val allowed: Rate) : Refusal {
        override val described: String
            get() = "${asked.perSecond}/s is over the ${allowed.perSecond}/s this machine allows"
    }

    data class OverDuration(val asked: Duration, val allowed: Duration) : Refusal {
        override val described: String get() = "$asked is over the $allowed this machine allows"
    }

    data class OverRequests(val asked: Long, val allowed: Long) : Refusal {
        override val described: String get() = "$asked requests is over the $allowed this machine allows"
    }

    data class HostNotAllowed(val host: String, val allowed: List<String>) : Refusal {
        override val described: String
            get() = "$host is not one of the hosts this machine allows: ${allowed.joinToString()}"
    }
}

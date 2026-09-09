package io.github.matthewjones372.proofload.http

import java.net.http.HttpResponse

/** What came back, as a value a capture can read without knowing the client. */
class Response(
    val status: Int,
    val body: String,
    private val headers: Map<String, List<String>>,
    /**
     * What a transport counted where it did not keep the body. Null where it
     * kept one, in which case [bytes] is read off what it kept.
     */
    private val counted: Long? = null,
) {

    /**
     * How many bytes came back.
     *
     * Counted as they arrived where the step said `discardingBody()`, and
     * derived from the body it kept otherwise — as UTF-8, which is what a
     * target that did not name a charset sent. Derived rather than measured is
     * said here because it is the only place it could be: a held body has no
     * byte count of its own to read.
     *
     * Computed on the call rather than held, so a run that never asks pays
     * nothing for it.
     */
    val bytes: Long get() = counted ?: body.toByteArray(Charsets.UTF_8).size.toLong()

    /**
     * Public because a [Transport] returns one, and a transport that cannot be
     * written outside this module is a seam that seams nothing. Header names
     * are lowercased here rather than trusted, so a transport built on another
     * client cannot change what `header("Location")` finds by casing its keys
     * differently.
     */
    constructor(status: Int, headers: List<Pair<String, String>>, body: String) : this(
        status = status,
        body = body,
        headers = headers.groupBy({ (name, _) -> name.lowercase() }, { (_, value) -> value }),
    )

    /** Case-insensitively, because HTTP field names are. */
    fun header(name: String): String? = headers[name.lowercase()]?.firstOrNull()

    /** All of them: a response that sets two cookies sends two of this header. */
    internal val setCookie: List<String> get() = headers["set-cookie"].orEmpty()

    internal companion object {

        fun of(raw: HttpResponse<String>): Response = Response(
            status = raw.statusCode(),
            body = raw.body(),
            headers = raw.headers().map().mapKeys { (name, _) -> name.lowercase() },
        )

        /**
         * The same response with its body counted rather than kept.
         *
         * The response is of no particular body type here: the JDK's counting
         * handler answers with a `Void` body, and naming that type is a detekt
         * finding rather than a thing this module chose.
         */
        fun counting(raw: HttpResponse<*>, bytes: Long): Response = Response(
            status = raw.statusCode(),
            body = "",
            headers = raw.headers().map().mapKeys { (name, _) -> name.lowercase() },
            counted = bytes,
        )
    }
}

package io.github.matthewjones372.kestrel.http

import java.net.http.HttpResponse

/** What came back, as a value a capture can read without knowing the client. */
class Response(
    val status: Int,
    val body: String,
    private val headers: Map<String, List<String>>,
) {

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
    }
}

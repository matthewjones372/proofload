package io.github.matthewjones372.kestrel.http

import java.net.http.HttpResponse

/** What came back, as a value a capture can read without knowing the client. */
class Response internal constructor(
    val status: Int,
    val body: String,
    private val headers: Map<String, List<String>>,
) {

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

package io.github.matthewjones372.kestrel.http

/**
 * Where requests are made from. A value, so a test against two services names
 * two of these rather than setting a global that the second one overwrites.
 */
class Http internal constructor(
    internal val baseUrl: String,
    internal val cookies: Boolean = false,
) {

    /** Trailing slash trimmed, so `baseUrl` and path agree about the one between them. */
    fun baseUrl(url: String): Http = Http(url.trimEnd('/'), cookies)

    /**
     * Carries cookies from one step to the next, in each virtual user's own
     * session. Off by default, because a request that sends a header the
     * scenario did not write is a different request from the one it reads as.
     */
    fun withCookies(): Http = Http(baseUrl, cookies = true)

    fun get(path: String): HttpAction = request("GET", path)

    fun post(path: String): HttpAction = request("POST", path)

    fun put(path: String): HttpAction = request("PUT", path)

    fun patch(path: String): HttpAction = request("PATCH", path)

    fun delete(path: String): HttpAction = request("DELETE", path)

    fun head(path: String): HttpAction = request("HEAD", path)

    private fun request(method: String, path: String): HttpAction = HttpAction(method, this, path)
}

/** No base URL of its own: `http.baseUrl(...)` gives one, or a path can be absolute. */
val http: Http = Http("")

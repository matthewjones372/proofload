package io.github.matthewjones372.kestrel.http

/**
 * Where requests are made from. A value, so a test against two services names
 * two of these rather than setting a global that the second one overwrites.
 */
class Http internal constructor(
    internal val baseUrl: String,
    internal val cookies: Boolean = false,
    internal val traced: Boolean = false,
) {

    /** Trailing slash trimmed, so `baseUrl` and path agree about the one between them. */
    fun baseUrl(url: String): Http = Http(url.trimEnd('/'), cookies, traced)

    /**
     * Carries cookies from one step to the next, in each virtual user's own
     * session. Off by default, because a request that sends a header the
     * scenario did not write is a different request from the one it reads as.
     */
    fun withCookies(): Http = Http(baseUrl, cookies = true, traced = traced)

    /**
     * Puts a W3C `traceparent` and a synthetic-traffic `baggage` entry on every
     * request from here, so a slow measurement has a trace id to follow into
     * whatever the target exports its spans to.
     */
    fun traced(): Http = Http(baseUrl, cookies = cookies, traced = true)

    fun get(path: String): HttpAction = request("GET", path)

    fun post(path: String): HttpAction = request("POST", path)

    fun put(path: String): HttpAction = request("PUT", path)

    fun patch(path: String): HttpAction = request("PATCH", path)

    fun delete(path: String): HttpAction = request("DELETE", path)

    fun head(path: String): HttpAction = request("HEAD", path)

    private fun request(method: String, path: String): HttpAction =
        HttpAction(method, this, path, traced = traced)
}

/** No base URL of its own: `http.baseUrl(...)` gives one, or a path can be absolute. */
val http: Http = Http("")

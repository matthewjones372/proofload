package io.github.matthewjones372.kestrel.websocket

/**
 * Where connections are made to. A value, so a test against two feeds names two
 * of these rather than setting a global that the second one overwrites.
 */
class Ws internal constructor(private val baseUrl: String) {

    /** Trailing slash trimmed, so the base and the path agree about the one between them. */
    fun baseUrl(url: String): Ws = Ws(url.trimEnd('/'))

    fun at(path: String): WsTarget = WsTarget(baseUrl + path)
}

/** One endpoint to connect to, as a value two scenarios can share. */
class WsTarget internal constructor(internal val url: String)

/** No base URL of its own: `ws.baseUrl(...)` gives one, or a path can be absolute. */
val ws: Ws = Ws("")

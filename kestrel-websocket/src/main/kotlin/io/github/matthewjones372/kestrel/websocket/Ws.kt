package io.github.matthewjones372.kestrel.websocket

/**
 * Where connections are made to, and what is sent over them. A value, so a test
 * against two feeds names two of these rather than setting a global that the
 * second one overwrites.
 */
class Ws internal constructor(private val baseUrl: String) {

    /** Trailing slash trimmed, so the base and the path agree about the one between them. */
    fun baseUrl(url: String): Ws = Ws(url.trimEnd('/'))

    fun at(path: String): WsTarget = WsTarget(baseUrl + path)

    /** One text frame, built once and sent by every user that reaches the step. */
    fun text(text: String): WsFrame = WsFrame.Text(text)

    /** The same for bytes. The array is not copied, so a caller that keeps one must not write to it. */
    fun binary(bytes: ByteArray): WsFrame = WsFrame.Binary(bytes)
}

/** One endpoint to connect to, as a value two scenarios can share. */
class WsTarget internal constructor(internal val url: String)

/** One message to send, whole: this module negotiates no fragmentation and no compression. */
sealed interface WsFrame {

    class Text internal constructor(internal val text: String) : WsFrame

    class Binary internal constructor(internal val bytes: ByteArray) : WsFrame
}

/** No base URL of its own: `ws.baseUrl(...)` gives one, or a path can be absolute. */
val ws: Ws = Ws("")

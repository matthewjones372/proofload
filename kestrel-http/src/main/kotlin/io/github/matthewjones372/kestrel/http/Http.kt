package io.github.matthewjones372.kestrel.http

/** Where requests are made from. A value: nothing is registered and nothing starts. */
class Http internal constructor() {

    fun get(path: String): HttpAction = HttpAction("GET", path)
}

val http: Http = Http()

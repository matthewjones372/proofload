// Written from catalogue.yaml by `kestrel emit`.
//
// A plan file says what it can without a lambda. This is the same run in the
// language, which is where a capture, a condition or a body per user goes —
// none of which a plan can state, and none of which should be added to one.

package io.github.matthewjones372.kestrel.examples.catalogue

import io.github.matthewjones372.kestrel.Scenario
import io.github.matthewjones372.kestrel.arbs.map
import io.github.matthewjones372.kestrel.arbs.oneOf
import io.github.matthewjones372.kestrel.arbs.uniform
import io.github.matthewjones372.kestrel.arbs.zipf
import io.github.matthewjones372.kestrel.at
import io.github.matthewjones372.kestrel.drawing
import io.github.matthewjones372.kestrel.expecting
import io.github.matthewjones372.kestrel.fedBy
import io.github.matthewjones372.kestrel.feed
import io.github.matthewjones372.kestrel.http.http
import io.github.matthewjones372.kestrel.p99
import io.github.matthewjones372.kestrel.perSecond
import io.github.matthewjones372.kestrel.plus
import io.github.matthewjones372.kestrel.scenario
import io.github.matthewjones372.kestrel.sessionKey
import io.github.matthewjones372.kestrel.step
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

val browse = step("browse")
val openProduct = step("open product")
val api = http.baseUrl("https://shop.internal")
val sku = sessionKey<String>("sku")
val skuDrawn = zipf(keys = 1000000, skew = 1.1, seed = 113956).map { it.toString() }
val page = sessionKey<String>("page")
val pageDrawn = uniform(keys = 20, seed = 3433110).map { (it + 1).toString() }
val region = sessionKey<String>("region")
val regionDrawn = oneOf("emea", "apac", "amer", seed = -934795525)

val catalogue: Scenario = scenario("catalogue") {
    exec(browse, api.get("/regions/{region}/products?page={page}"))
    exec(openProduct, api.get("/products/{sku}").declaring(404))
}

val simulation = catalogue
    .at(200.0.perSecond, over = 1.minutes)
    .fedBy(feed(sku) { skuDrawn at it } + feed(page) { pageDrawn at it } + feed(region) { regionDrawn at it })
    .drawing(skuDrawn.shape, pageDrawn.shape, regionDrawn.shape)
    .expecting(
        p99(openProduct) under 150.milliseconds,
    )

// Written from catalogue.yaml by `proofload emit`.
//
// A plan file says what it can without a lambda. This is the same run in the
// language, which is where a capture, a condition or a body per user goes —
// none of which a plan can state, and none of which should be added to one.

package io.github.matthewjones372.proofload.examples.catalogue

import io.github.matthewjones372.proofload.Scenario
import io.github.matthewjones372.proofload.arbs.map
import io.github.matthewjones372.proofload.arbs.oneOf
import io.github.matthewjones372.proofload.arbs.uniform
import io.github.matthewjones372.proofload.arbs.zipf
import io.github.matthewjones372.proofload.at
import io.github.matthewjones372.proofload.drawing
import io.github.matthewjones372.proofload.expecting
import io.github.matthewjones372.proofload.fedBy
import io.github.matthewjones372.proofload.feed
import io.github.matthewjones372.proofload.http.http
import io.github.matthewjones372.proofload.p99
import io.github.matthewjones372.proofload.perSecond
import io.github.matthewjones372.proofload.plus
import io.github.matthewjones372.proofload.scenario
import io.github.matthewjones372.proofload.sessionKey
import io.github.matthewjones372.proofload.step
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

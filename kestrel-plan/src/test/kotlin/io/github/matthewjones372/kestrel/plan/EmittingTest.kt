package io.github.matthewjones372.kestrel.plan

import io.github.matthewjones372.kestrel.perSecond
import io.kotest.assertions.withClue
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The emitted source has to compile, which a golden cannot prove. The proof is
 * the checked-in file in `examples`, which the build compiles; these assert the
 * shape a reader is meant to recognise.
 */
class EmittingTest {

    private val emitted = readPlan(PLAN).asKotlin(
        packageName = "io.github.matthewjones372.kestrel.examples",
        from = "checkout.yaml",
    )

    @Test
    fun `a step is a handle, not a string, so a rename breaks the build`() {
        withClue(emitted) {
            emitted shouldContain """val placeOrder = step("place order")"""
            emitted shouldContain "exec(placeOrder, api.post(\"/orders\")"
        }
    }

    @Test
    fun `a goal names the handle its step declared`() {
        emitted shouldContain "p99(placeOrder) under 200.milliseconds"
    }

    @Test
    fun `durations are written as the compiler reads them`() {
        withClue("Duration.toString prints 1m, which this library reads and kotlinc does not") {
            emitted shouldContain "over = 1.minutes"
            emitted shouldNotContain "over = 1m"
        }
    }

    @Test
    fun `the header says where it came from and does not claim to be a recording`() {
        withClue(emitted) {
            emitted shouldContain "Written from checkout.yaml by `kestrel emit`"
            emitted shouldNotContain "credential"
        }
    }

    @Test
    fun `nothing is imported that is not used`() {
        val imports = emitted.lines().filter { it.startsWith("import ") }.map { it.removePrefix("import ") }
        val body = emitted.lines().filterNot { it.startsWith("import ") }.joinToString("\n")

        withClue("detekt fails the build on an unused import, so emitted source must not carry one") {
            imports.forEach { import -> body shouldContain import.substringAfterLast('.') }
        }
    }

    @Test
    fun `a produce step emits the topic once and a step that sends to it`() {
        val emitted = Declaration(
            version = Declaration.VERSION,
            brokers = "localhost:9092",
            scenario = "orders",
            steps = listOf(
                DeclaredStep.Produce(
                    name = "place order",
                    topic = "orders",
                    body = """{"cart":"1 anvil"}""",
                    settings = mapOf("acks" to "all"),
                ),
            ),
            load = DeclaredLoad.Constant(500.perSecond, 1.minutes),
        ).asKotlin(packageName = "io.github.matthewjones372.kestrel.examples", from = "orders.yaml")

        withClue(emitted) {
            emitted shouldContain """val cluster = kafka.brokers("localhost:9092")"""
            emitted shouldContain """val ordersTopic = cluster.setting("acks", "all").topic("orders")"""
            emitted shouldContain "produce(placeOrder, ordersTopic.value {"
            withClue("a plan of nothing but topics names no HTTP client and imports none") {
                emitted shouldNotContain "http.baseUrl"
                emitted shouldNotContain "import io.github.matthewjones372.kestrel.http.http"
            }
        }
    }

    @Test
    fun `an answered produce step emits the emit-completing pair, not two unrelated steps`() {
        val emitted = Declaration(
            version = Declaration.VERSION,
            brokers = "localhost:9092",
            scenario = "orders",
            steps = listOf(
                DeclaredStep.Produce(name = "place order", topic = "orders", body = "{}"),
                DeclaredStep.Completes(
                    name = "confirmed",
                    completes = "place order",
                    on = "order-confirmations",
                    by = "correlation-id",
                    within = 30.seconds,
                ),
            ),
            load = DeclaredLoad.Constant(500.perSecond, 1.minutes),
        ).asKotlin(packageName = "io.github.matthewjones372.kestrel.examples", from = "orders.yaml")

        withClue(emitted) {
            emitted shouldContain """.correlatedBy(Header("correlation-id"))"""
            emitted shouldContain "emit(placeOrder, ordersTopic"
            emitted shouldContain "keyedBy = byUser"
            emitted shouldContain ".completing(confirmed, from = orderConfirmationsTopic.completions()"
            emitted shouldContain "drainingFor = 30.seconds"
            withClue("the answer is drained by the run, so it is not a second send inside the scenario") {
                emitted shouldNotContain "produce(confirmed"
                emitted shouldNotContain "exec(confirmed"
            }
        }
    }

    private companion object {
        val PLAN = """
            kestrel:  plan/1
            baseUrl:  https://orders.internal
            scenario: checkout
            steps:
              - name: browse
                get:  /products
              - name: place order
                post: /orders
                body: '{"cart":"1 anvil"}'
                expecting: 201
            load:
              rate: 50/s
              over: 1m
            goals:
              - step: place order
                p99:  200ms
        """.trimIndent()
    }
}

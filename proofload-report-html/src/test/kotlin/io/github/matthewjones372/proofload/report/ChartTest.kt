package io.github.matthewjones372.proofload.report

import io.github.matthewjones372.proofload.Histogram
import io.github.matthewjones372.proofload.timing
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds

class ChartTest {

    private fun chartOf(samples: List<Int>): String =
        Histogram().apply { samples.forEach { record(it.milliseconds) } }
            .timing()
            .distributionChart("pay")
            .joinToString("\n")

    @Test
    fun `one bar is drawn for each bucket that counted something`() {
        val chart = chartOf(List(30) { 8 } + List(4) { 300 })

        Regex("<rect class=\"bar\"").findAll(chart).count() shouldBe 2
    }

    @Test
    fun `a bar says what it counted, so a reader can check the drawing`() {
        chartOf(List(30) { 8 }) shouldContain "<title>30 at"
    }

    @Test
    fun `p50 and p99 are marked, because a shape without them is just a shape`() {
        val chart = chartOf((1..200).toList())

        chart shouldContain ">p50<"
        chart shouldContain ">p99<"
    }

    @Test
    fun `a step that recorded nothing is not given an empty chart`() {
        Histogram().timing().distributionChart("pay").shouldBeEmpty()
    }

    @Test
    fun `the axis is ticked at decades, not at the powers of two the buckets use`() {
        val chart = chartOf(listOf(1, 12, 130, 1_400))

        chart shouldContain "tick-label"
        chart shouldContain ">1.00 ms<"
    }

    @Test
    fun `a chart fetches nothing and needs no library`() {
        val chart = chartOf(List(10) { 5 })

        chart shouldContain "<svg"
        chart shouldNotContain "http"
    }

    @Test
    fun `two populations stay two populations on the page`() {
        val chart = chartOf(List(50) { 4 } + List(50) { 900 })

        Regex("<rect class=\"bar\"").findAll(chart).count() shouldBe 2
    }
}

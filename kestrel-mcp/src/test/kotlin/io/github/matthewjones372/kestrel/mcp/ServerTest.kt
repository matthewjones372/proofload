package io.github.matthewjones372.kestrel.mcp

import io.github.matthewjones372.kestrel.plan.asSimulation
import io.github.matthewjones372.kestrel.plan.kafka.kafkaLowerings
import io.github.matthewjones372.kestrel.plan.readPlan
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.io.StringWriter

/**
 * The framing, driven with strings rather than a process: a server whose
 * protocol can only be exercised by spawning one has its framing tested by
 * hand, which is to say not at all.
 */
class ServerTest {

    @Test
    fun `it says what it is`() {
        exchange("""{"jsonrpc":"2.0","id":1,"method":"initialize"}""") shouldContain """"name":"kestrel""""
    }

    @Test
    fun `it lists its tools, each saying what it sends`() {
        val listed = exchange(TOOLS_LIST)

        withClue(listed) {
            listed shouldContain """"name":"plan_schema""""
            listed shouldContain """"name":"from_openapi""""
            withClue("a caller has to know what a call costs before making it") {
                listed shouldContain "Sends: nothing."
            }
        }
    }

    @Test
    fun `a notification is not answered`() {
        withClue("answering one is what makes a client hang up on the next request") {
            exchange("""{"jsonrpc":"2.0","method":"notifications/initialized"}""") shouldBe ""
        }
    }

    @Test
    fun `a method nobody serves is an error, not a tool result`() {
        val answered = exchange("""{"jsonrpc":"2.0","id":3,"method":"dance"}""")

        withClue("a client must be able to tell a call it cannot make from a tool that said no") {
            answered shouldContain """"error""""
            answered shouldNotContain """"isError""""
        }
    }

    @Test
    fun `a line that is not a call does not stop the server`() {
        val output = StringWriter()
        val lines = "not json\n" + """{"jsonrpc":"2.0","id":4,"method":"tools/list"}""" + "\n"
        serve(lines.reader().buffered(), output, ::answer)

        output.toString().lines().count { it.isNotBlank() } shouldBe 2
    }

    @Test
    fun `every plan in the schema is a plan the reader accepts`() {
        val examples = PLAN_SCHEMA.substringAfter("Four worked plans.")
            .split("---")
            .map { it.trim() }
            .filter { it.startsWith("kestrel:") }

        withClue("a schema whose own examples do not parse is worse than no schema") {
            examples.size shouldBe 4
            examples.forEach { readPlan(it).asSimulation(kafkaLowerings) }
        }
    }

    @Test
    fun `the schema names only keys the reader knows`() {
        val named = Regex("""^\s{6}(\w+)""", RegexOption.MULTILINE)
            .findAll(PLAN_SCHEMA.substringBefore("Four worked plans."))
            .map { it.groupValues[1] }
            .toSet()

        withClue("documentation about a parser drifts; this is meant to be the parser's own keys") {
            named.forEach { key -> withClue(key) { KNOWN.contains(key) shouldBe true } }
        }
    }

    @Test
    fun `every tool it lists is a tool it will actually call`() {
        val listed = Regex(""""name":"(\w+)""").findAll(exchange(TOOLS_LIST)).map { it.groupValues[1] }.toList()

        withClue("a tool in the list that dispatch does not know is one a caller is invited to fail at") {
            listed.forEach { tool ->
                val answered = exchange(
                    """{"jsonrpc":"2.0","id":9,"method":"tools/call","params":{"name":"$tool","arguments":{}}}""",
                )
                withClue("$tool: $answered") { answered shouldNotContain "no tool" }
            }
        }
    }

    /**
     * Found by sending it a document that was not one. A YAML parse failure is
     * not an `IllegalArgumentException`, so it went past every catch that
     * reads a plan and out of `main` — six of the thirteen tools ended the
     * session, and every run this process was holding went with it.
     */
    @Test
    fun `a tool that throws is a tool result, not the end of the session`() {
        val calls = listOf(
            "from_openapi" to """{"document":"not: an: openapi"}""",
            "validate" to """{"plan":"not: an: openapi"}""",
            "plan_schema" to "{}",
        ).mapIndexed { at, (tool, arguments) ->
            """{"jsonrpc":"2.0","id":${at + 1},"method":"tools/call",""" +
                """"params":{"name":"$tool","arguments":$arguments}}"""
        }

        val answered = exchange(calls.joinToString(separator = System.lineSeparator()))

        withClue(answered) {
            withClue("every call is answered, including the two that failed") {
                answered.lines().size shouldBe 3
            }
            answered shouldContain """"isError":true"""
            withClue("the session is still usable after a tool threw") {
                answered.lines().last() shouldContain "A plan is YAML or JSON"
            }
        }
    }

    private fun exchange(line: String): String {
        val output = StringWriter()
        serve("$line\n".reader().buffered(), output, ::answer)
        return output.toString().trim()
    }

    private companion object {
        const val TOOLS_LIST = """{"jsonrpc":"2.0","id":2,"method":"tools/list"}"""

        val KNOWN = setOf(
            "kestrel", "baseUrl", "brokers", "scenario", "steps", "load", "goals",
            "headers", "body", "expecting", "declared", "pauseAfter",
            "key", "settings", "on", "by", "within", "group",
            "draw", "seed", "uniform", "zipf", "oneOf", "digits", "uuids",
        )
    }
}

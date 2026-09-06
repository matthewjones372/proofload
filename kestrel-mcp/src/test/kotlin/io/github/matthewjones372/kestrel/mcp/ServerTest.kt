package io.github.matthewjones372.kestrel.mcp

import io.github.matthewjones372.kestrel.plan.asSimulation
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
        val examples = PLAN_SCHEMA.substringAfter("Two worked plans.")
            .split("---")
            .map { it.trim() }
            .filter { it.startsWith("kestrel:") }

        withClue("a schema whose own examples do not parse is worse than no schema") {
            examples.size shouldBe 2
            examples.forEach { readPlan(it).asSimulation() }
        }
    }

    @Test
    fun `the schema names only keys the reader knows`() {
        val named = Regex("""^\s{6}(\w+)""", RegexOption.MULTILINE)
            .findAll(PLAN_SCHEMA.substringBefore("Two worked plans."))
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

    private fun exchange(line: String): String {
        val output = StringWriter()
        serve("$line\n".reader().buffered(), output, ::answer)
        return output.toString().trim()
    }

    private companion object {
        const val TOOLS_LIST = """{"jsonrpc":"2.0","id":2,"method":"tools/list"}"""

        val KNOWN = setOf(
            "kestrel", "baseUrl", "scenario", "steps", "load", "goals",
            "headers", "body", "expecting", "declared", "pauseAfter",
        )
    }
}

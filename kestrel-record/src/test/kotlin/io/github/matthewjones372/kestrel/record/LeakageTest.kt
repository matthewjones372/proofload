package io.github.matthewjones372.kestrel.record

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

/**
 * The generated file says every credential it carried was dropped. It looked
 * only at headers, so a recording with a token in the query string wrote that
 * sentence directly above the token — and the path becomes the step's name, so
 * it reached every report and baseline the run went on to write.
 */
class LeakageTest {

    private val jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.c2ln"

    private val har = """
        {"log":{"version":"1.2","entries":[
         {"startedDateTime":"2026-09-07T10:00:00.000Z",
          "request":{"method":"GET","url":"https://shop.internal/api/orders?access_token=$jwt&page=1",
           "headers":[]},
          "response":{"status":200,"content":{"text":"[]"}}},
         {"startedDateTime":"2026-09-07T10:00:01.000Z",
          "request":{"method":"POST","url":"https://shop.internal/api/login","headers":[],
           "postData":{"text":"{\"user\":\"priya\",\"password\":\"hunter2\"}"}},
          "response":{"status":201,"content":{"text":"{}"}}}
        ]}}
    """.trimIndent()

    private val source: String
        get() = draft(readHar(har)).asKotlin("load", "leaky", "leaky.har")

    @Test
    fun `a token in the query string does not reach the generated source`() {
        withClue(source) {
            source shouldNotContain jwt
            source shouldContain "`access_token` query parameter"
        }
    }

    @Test
    fun `the step's name carries no token either, because a name reaches every report`() {
        withClue("a path with a token in it is a row per token on the page and in every baseline") {
            source shouldContain """step("GET /api/orders?page=1")"""
        }
    }

    @Test
    fun `a password in a body does not reach it either`() {
        withClue(source) {
            source shouldNotContain "hunter2"
            source shouldContain "REDACTED"
        }
    }

    @Test
    fun `what is not a credential is left alone`() {
        withClue("over-redacting a recording until it describes nothing is its own failure") {
            source shouldContain "page=1"
            source shouldContain """"user":"priya""""
        }
    }

    @Test
    fun `a bare jwt anywhere in a body is redacted, whatever the field is called`() {
        val hidden = """{"session":{"nested":"$jwt"}}"""

        withClue("a token turns up under whatever name the service that issued it chose") {
            hidden.withoutSecrets() shouldNotContain jwt
        }
    }

    @Test
    fun `a url with no query survives untouched`() {
        "https://shop.internal/api/orders".withoutCredentialParameters() shouldBe
            ("https://shop.internal/api/orders" to emptyList())
    }
}

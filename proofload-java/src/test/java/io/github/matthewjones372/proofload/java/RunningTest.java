package io.github.matthewjones372.proofload.java;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.github.matthewjones372.proofload.Clock;
import io.github.matthewjones372.proofload.Offered;
import io.github.matthewjones372.proofload.RunResult;
import io.github.matthewjones372.proofload.Scenario;
import io.github.matthewjones372.proofload.SessionKey;
import io.github.matthewjones372.proofload.StepName;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * A run rather than a construction: the claim is that a Java caller reaches the
 * whole path, and only a real target proves the accessors read what ran.
 */
class RunningTest {

    private static final StepName BROWSE = Steps.named("browse");

    private static final SessionKey<String> BODY = SessionKeys.of(String.class, "body");

    @Test
    void aJavaCallerRunsAScenarioAndReadsItsPercentiles() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/products", exchange -> {
            exchange.getResponseHeaders().add("location", "/orders/1");
            exchange.sendResponseHeaders(200, 0);
            exchange.close();
        });
        server.start();

        try {
            var api = Https.baseUrl("http://localhost:" + server.getAddress().getPort());
            Scenario browsing = Scenarios.named("browsing")
                .exec(BROWSE, Https.capturing(api.get("/products").expecting(200), BODY, response -> response.header("location")))
                .build();

            RunResult result = Proofload.create()
                .run(Simulations.at(browsing, Rates.perSecond(20), Duration.ofMillis(500)));

            assertTrue(Results.ran(result, BROWSE));
            assertTrue(Results.count(result, BROWSE) > 0);
            assertEquals(Results.count(result, BROWSE), Results.ok(result, BROWSE));
            assertEquals(0, Results.failed(result, BROWSE));
            assertFalse(Results.p99(result, BROWSE).isNegative());
            assertTrue(Results.max(result, BROWSE).compareTo(Results.p50(result, BROWSE)) >= 0);
            assertFalse(Results.p95(result, BROWSE, Clock.ServiceTime).isNegative());

            Offered offered = Offereds.of(result);
            assertNotNull(offered);
            assertEquals(20.0, Offereds.asked(offered).getPerSecond());
            assertTrue(Offereds.left(offered).getPerSecond() > 0);
            assertFalse(Offereds.over(offered).isNegative());
            assertEquals(
                Offereds.left(offered).getPerSecond() / Offereds.asked(offered).getPerSecond(),
                offered.getShare()
            );
        } finally {
            server.stop(0);
        }
    }
}

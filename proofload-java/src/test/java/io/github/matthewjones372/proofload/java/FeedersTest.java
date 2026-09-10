package io.github.matthewjones372.proofload.java;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.github.matthewjones372.proofload.Feeder;
import io.github.matthewjones372.proofload.RunResult;
import io.github.matthewjones372.proofload.Scenario;
import io.github.matthewjones372.proofload.SessionKey;
import io.github.matthewjones372.proofload.StepName;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

/**
 * A run rather than a construction: the claim is that a value the facade fed
 * reaches the target, and only the target can say whether it did.
 */
class FeedersTest {

    private static final StepName BROWSE = Steps.named("browse");

    private static final SessionKey<String> PERSON = SessionKeys.of(String.class, "personId");

    @Test
    void aJavaLoadTestSendsADifferentIdPerUser() throws IOException {
        Set<String> seen = ConcurrentHashMap.newKeySet();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/people", exchange -> {
            seen.add(exchange.getRequestURI().getPath());
            exchange.sendResponseHeaders(200, 0);
            exchange.close();
        });
        server.start();

        try {
            var api = Https.baseUrl("http://localhost:" + server.getAddress().getPort());
            Scenario browsing = Scenarios.named("browsing")
                .exec(BROWSE, api.get("/people/{personId}").expecting(200))
                .build();

            RunResult result = Proofload.create().run(
                Feeders.fedBy(
                    Simulations.at(browsing, Rates.perSecond(20), Duration.ofMillis(500)),
                    Feeders.of(PERSON, user -> String.valueOf(user))
                )
            );

            assertEquals(0, Results.failed(result, BROWSE));
            assertEquals(Results.count(result, BROWSE), seen.size(), "some users sent the same path as another");
            assertTrue(seen.contains("/people/0"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void aFeederFromAListWrapsRoundAndCombinesWithAnother() {
        SessionKey<String> target = SessionKeys.of(String.class, "target");
        Feeder both = Feeders.combined(
            Feeders.fromList(PERSON, List.of("luke", "leia")),
            Feeders.of(target, user -> "target-" + user)
        );

        assertEquals("luke", both.forUser(4).get(PERSON));
        assertEquals("leia", both.forUser(5).get(PERSON));
        assertEquals("target-5", both.forUser(5).get(target));
    }
}

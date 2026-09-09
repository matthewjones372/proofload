package io.github.matthewjones372.proofload.java;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.github.matthewjones372.proofload.Goal;
import io.github.matthewjones372.proofload.RunResult;
import io.github.matthewjones372.proofload.Scenario;
import io.github.matthewjones372.proofload.StepName;
import io.github.matthewjones372.proofload.Verdict;
import io.github.matthewjones372.proofload.http.Http;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A Java caller declares goals and reads the verdicts back. Nothing here names
 * a hash, which is the whole claim of the module.
 */
class GoalsTest {

    private static final StepName PAY = Steps.named("pay");

    private HttpServer server;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/pay", exchange -> {
            byte[] body = "{}".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void a_goal_reads_as_it_was_asked() {
        Goal goal = Goals.p99Under(PAY, Duration.ofMillis(200));

        assertEquals("pay p99 under 200ms", goal.getDescribed());
    }

    @Test
    void two_goals_produce_two_verdicts_a_java_caller_can_read() {
        RunResult result = run(
            Goals.p99Under(PAY, Duration.ofSeconds(30)),
            Goals.failureRateUnder(PAY, 100.0));

        List<Verdict> verdicts = Results.verdicts(result);

        assertEquals(2, verdicts.size());
        for (Verdict verdict : verdicts) {
            assertTrue(verdict.getMet(), verdict.getGoal().getDescribed());
        }
    }

    @Test
    void a_goal_nothing_could_meet_reports_the_margin_it_missed_by() {
        RunResult result = run(Goals.p99Under(PAY, Duration.ofNanos(1)));

        Verdict verdict = Results.verdicts(result).get(0);

        assertFalse(verdict.getMet());
        assertTrue(verdict.getOverBy() > 0.0, "a missed goal says by how much");
    }

    private RunResult run(Goal... goals) {
        Http api = Https.baseUrl("http://localhost:" + server.getAddress().getPort());
        Scenario paying = Scenarios.named("paying").exec(PAY, api.get("/pay")).build();

        return Proofload.create()
            .run(Simulations.at(paying, Rates.perSecond(20), Duration.ofMillis(300), goals));
    }
}

package io.github.matthewjones372.proofload.examples.java;

import io.github.matthewjones372.proofload.RunResult;
import io.github.matthewjones372.proofload.Scenario;
import io.github.matthewjones372.proofload.SessionKey;
import io.github.matthewjones372.proofload.StepName;
import io.github.matthewjones372.proofload.Verdict;
import io.github.matthewjones372.proofload.http.Http;
import io.github.matthewjones372.proofload.java.Goals;
import io.github.matthewjones372.proofload.java.Https;
import io.github.matthewjones372.proofload.java.Proofload;
import io.github.matthewjones372.proofload.java.Rates;
import io.github.matthewjones372.proofload.java.Results;
import io.github.matthewjones372.proofload.java.Scenarios;
import io.github.matthewjones372.proofload.java.SessionKeys;
import io.github.matthewjones372.proofload.java.Simulations;
import io.github.matthewjones372.proofload.java.Steps;
import java.time.Duration;

/**
 * A load test written in Java, and the gate on {@code proofload-java} being
 * callable from it. Nothing here imports Kotlin and no name here carries a
 * value-class hash; a facade method that goes takes this source set with it.
 */
public final class Checkout {

    private static final SessionKey<String> ORDER_ID = SessionKeys.of(String.class, "orderId");

    private static final StepName BROWSE = Steps.named("browse");

    private static final StepName PLACE_ORDER = Steps.named("place order");

    private Checkout() {
    }

    public static void main(String[] args) {
        Http api = Https.baseUrl("https://orders.internal");

        Scenario checkout = Scenarios.named("checkout")
            .exec(BROWSE, api.get("/products"))
            .exec(PLACE_ORDER, Https.capturing(
                api.post("/orders").body("{\"cart\":\"1 anvil\"}").expecting(201),
                ORDER_ID,
                response -> response.header("location")))
            .pause(Duration.ofSeconds(1))
            .build();

        RunResult result = Proofload.create().run(
            Simulations.at(checkout, Rates.perSecond(50), Duration.ofMinutes(1),
                Goals.p99Under(PLACE_ORDER, Duration.ofMillis(200)),
                Goals.failureRateUnder(0.1)));

        for (Verdict verdict : Results.verdicts(result)) {
            System.out.println(verdict.getGoal().getDescribed() + (verdict.getMet() ? " met" : " missed"));
        }

        Duration tail = Results.p99(result, PLACE_ORDER);
        System.out.println(PLACE_ORDER.getName() + " p99 " + tail.toMillis() + "ms over "
            + Results.count(result, PLACE_ORDER) + " requests, " + Results.failed(result, PLACE_ORDER) + " failed");
    }
}

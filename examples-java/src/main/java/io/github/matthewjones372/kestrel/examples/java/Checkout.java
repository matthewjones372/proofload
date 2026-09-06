package io.github.matthewjones372.kestrel.examples.java;

import io.github.matthewjones372.kestrel.RunResult;
import io.github.matthewjones372.kestrel.Scenario;
import io.github.matthewjones372.kestrel.SessionKey;
import io.github.matthewjones372.kestrel.StepName;
import io.github.matthewjones372.kestrel.Verdict;
import io.github.matthewjones372.kestrel.http.Http;
import io.github.matthewjones372.kestrel.java.Goals;
import io.github.matthewjones372.kestrel.java.Https;
import io.github.matthewjones372.kestrel.java.Kestrel;
import io.github.matthewjones372.kestrel.java.Rates;
import io.github.matthewjones372.kestrel.java.Results;
import io.github.matthewjones372.kestrel.java.Scenarios;
import io.github.matthewjones372.kestrel.java.SessionKeys;
import io.github.matthewjones372.kestrel.java.Simulations;
import io.github.matthewjones372.kestrel.java.Steps;
import java.time.Duration;

/**
 * A load test written in Java, and the gate on {@code kestrel-java} being
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

        RunResult result = Kestrel.create().run(
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

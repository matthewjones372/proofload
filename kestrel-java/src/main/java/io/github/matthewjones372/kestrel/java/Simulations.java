package io.github.matthewjones372.kestrel.java;

import io.github.matthewjones372.kestrel.Rate;
import io.github.matthewjones372.kestrel.Scenario;
import io.github.matthewjones372.kestrel.Simulation;
import java.time.Duration;

/**
 * A scenario, the rate it is sent at and the window it is sent over.
 *
 * Kotlin writes this as {@code scenario.at(50.perSecond, 1.minutes)}. A static
 * rather than a method on the scenario, because the scenario a Java caller
 * holds is core's own value and gains nothing here.
 */
public final class Simulations {

    private Simulations() {
    }

    public static Simulation at(Scenario scenario, Rate rate, Duration over) {
        return Runs.at(scenario, rate.getPerSecond(), over);
    }
}

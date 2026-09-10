package io.github.matthewjones372.proofload.java;

import io.github.matthewjones372.proofload.Capacity;
import io.github.matthewjones372.proofload.Goal;
import io.github.matthewjones372.proofload.Rate;
import io.github.matthewjones372.proofload.Rung;
import io.github.matthewjones372.proofload.Scenario;
import io.github.matthewjones372.proofload.Search;
import java.time.Duration;
import java.util.List;

/**
 * The hunt for the highest rate a scenario sustains, and the rates its answer
 * is read off.
 *
 * Kotlin writes {@code checkout.sustainable(500.perSecond, 2.minutes, goals)}.
 * Both of those arguments are value classes and so is every rate the curve
 * carries, which is why these are Java sources over {@link Hunts}.
 *
 * {@code Proofload.create().run(search)} sends it; the search itself is a value
 * and runs nothing.
 */
public final class Searches {

    private Searches() {
    }

    public static Search sustainable(Scenario scenario, Rate upTo, Duration holding, List<Goal> expecting) {
        return Hunts.sustainable(scenario, upTo.getPerSecond(), holding, expecting);
    }

    /** The same search, with every rung warmed for {@code over} at that rung's own rate before it is measured. */
    public static Search warmingUp(Search search, Duration over) {
        return Hunts.warmingUp(search, over);
    }

    /** The highest rate every goal held at, or null where even the lowest rung missed one. */
    public static Rate rate(Capacity capacity) {
        return (Rate) Hunts.capacityRate(capacity);
    }

    public static Rate rate(Rung rung) {
        return (Rate) Hunts.rungRate(rung);
    }

    /** The rate the load actually left at, which is under {@link #rate(Rung)} where the injector fell behind. */
    public static Rate offered(Rung rung) {
        return (Rate) Hunts.offered(rung);
    }
}

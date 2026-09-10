package io.github.matthewjones372.proofload.java;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.matthewjones372.proofload.Action;
import io.github.matthewjones372.proofload.Capacity;
import io.github.matthewjones372.proofload.Rung;
import io.github.matthewjones372.proofload.Scenario;
import io.github.matthewjones372.proofload.Search;
import io.github.matthewjones372.proofload.StepName;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Java source rather than Kotlin, for the reason {@code FactoriesTest} is one:
 * Kotlin resolves the mangled names too, so a Kotlin test here would pass while
 * the module failed at the only thing it is for.
 */
class SearchesTest {

    private static final StepName SERVE = Steps.named("serve");

    private static final Action NOTHING = Actions.of(scope -> { });

    private static final Scenario TARGET = Scenarios.named("target").exec(SERVE, NOTHING).build();

    /**
     * Two million a second is a rate no injector offers, so the first rung is
     * void and the search stops on it. A search that climbs would be a run of
     * this build's machine rather than of these accessors.
     */
    private static Search beyondTheInjector() {
        return Searches.sustainable(
            TARGET,
            Rates.perSecond(2_000_000),
            Duration.ofMillis(10),
            List.of(Goals.failureRateUnder(1))
        );
    }

    @Test
    void aSearchClimbsTenRungsUpToTheCeilingItWasGiven() {
        Search search = beyondTheInjector();

        assertEquals(10, search.getRungs().size());
        assertEquals(200_000.0, search.getRungs().get(0).getPerSecond());
        assertEquals(2_000_000.0, search.getRungs().get(9).getPerSecond());
    }

    @Test
    void aWarmUpIsAskedForInJavaDurations() {
        assertNull(beyondTheInjector().getWarmUp());

        Search warmed = Searches.warmingUp(beyondTheInjector(), Duration.ofMillis(20));

        assertNotNull(warmed.getWarmUp());
    }

    @Test
    void aRungReadsBackTheRateItHeldAndTheRateItActuallyOffered() {
        Capacity capacity = Proofload.create().run(beyondTheInjector());

        Rung rung = capacity.getCurve().get(0);
        assertEquals(200_000.0, Searches.rate(rung).getPerSecond());
        assertTrue(
            Searches.offered(rung).getPerSecond() < Searches.rate(rung).getPerSecond(),
            "a void rung is one the injector never offered at the rate it asked for"
        );
    }

    @Test
    void aRateNoInjectorOfferedIsNotARateTheTargetSustained() {
        Capacity capacity = Proofload.create().run(beyondTheInjector());

        assertTrue(capacity.getVoided());
        assertNull(Searches.rate(capacity));
    }
}

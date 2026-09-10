package io.github.matthewjones372.proofload.java;

import io.github.matthewjones372.proofload.Feeder;
import io.github.matthewjones372.proofload.FeederKt;
import io.github.matthewjones372.proofload.Search;
import io.github.matthewjones372.proofload.SearchKt;
import io.github.matthewjones372.proofload.SessionKey;
import io.github.matthewjones372.proofload.Simulation;
import io.github.matthewjones372.proofload.SimulationKt;
import java.util.List;
import java.util.function.LongFunction;

/**
 * What each virtual user starts with, so ten thousand of them do not send one
 * identical request and measure whatever the target does with a duplicate.
 *
 * A function of the user's number rather than a cursor over a source: a cursor
 * is shared state on the path every departure takes, and a generator that locks
 * to decide what to send is measuring itself. The number is also what makes a
 * run repeatable, so user 4,001 gets the same value tomorrow.
 *
 * A value fills a request through the same {@code {name}} the path and body
 * templates already use.
 */
public final class Feeders {

    private Feeders() {
    }

    /** Fills {@code key} with a value worked out from the user's number. */
    public static <T> Feeder of(SessionKey<T> key, LongFunction<T> value) {
        return Feeds.of(key, value);
    }

    /** Fills {@code key} from {@code values}, indexed by the user's number and wrapping round at the end. */
    public static <T> Feeder fromList(SessionKey<T> key, List<T> values) {
        return FeederKt.feedFrom(key, values);
    }

    /** Both, for the same user. Where they fill one key the later one wins. */
    public static Feeder combined(Feeder first, Feeder second) {
        return FeederKt.plus(first, second);
    }

    public static Simulation fedBy(Simulation simulation, Feeder feeder) {
        return SimulationKt.fedBy(simulation, feeder);
    }

    public static Search fedBy(Search search, Feeder feeder) {
        return SearchKt.fedBy(search, feeder);
    }
}

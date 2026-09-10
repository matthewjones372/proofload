package io.github.matthewjones372.proofload.java;

import io.github.matthewjones372.proofload.Offered;
import io.github.matthewjones372.proofload.OfferedKt;
import io.github.matthewjones372.proofload.Rate;
import io.github.matthewjones372.proofload.RunResult;
import java.time.Duration;

/**
 * The load a run asked for beside the load that actually left.
 *
 * What a reader needs when the generator fell behind: service time was measured
 * from the departures that happened, so it describes the target at
 * {@link #left(Offered)} rather than at {@link #asked(Offered)}.
 */
public final class Offereds {

    private Offereds() {
    }

    /** What this run offered, or null where it cannot be said: a closed run, or a result nobody ran. */
    public static Offered of(RunResult result) {
        return OfferedKt.getOffered(result);
    }

    public static Rate asked(Offered offered) {
        return (Rate) Offers.asked(offered);
    }

    public static Rate left(Offered offered) {
        return (Rate) Offers.left(offered);
    }

    /** The window the load took to leave, which is longer than the one asked for where it fell behind. */
    public static Duration over(Offered offered) {
        return Offers.over(offered);
    }
}

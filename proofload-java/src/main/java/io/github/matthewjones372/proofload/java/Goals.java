package io.github.matthewjones372.proofload.java;

import io.github.matthewjones372.proofload.Clock;
import io.github.matthewjones372.proofload.Goal;
import io.github.matthewjones372.proofload.StepName;
import java.time.Duration;

/**
 * What a run is asked to achieve, so the report says which goal missed and by
 * how much rather than a test failing on the first assertion that did.
 *
 * Kotlin writes these as {@code p99(placeOrder) under 200.milliseconds}. The
 * infix form has no Java spelling, and every call that builds one takes a
 * {@code StepName} or a {@code Share}, whose names mangle — so these are the
 * Java sources over {@link Judgements}.
 *
 * The clock defaults to {@link Clock#ResponseTime}: a goal written against
 * service time can be met by a generator that never sent the load.
 */
public final class Goals {

    private Goals() {
    }

    public static Goal p50Under(StepName step, Duration limit) {
        return p50Under(step, limit, Clock.ResponseTime);
    }

    public static Goal p50Under(StepName step, Duration limit, Clock clock) {
        return Judgements.percentileUnder(step.getName(), "p50", limit, clock);
    }

    public static Goal p95Under(StepName step, Duration limit) {
        return p95Under(step, limit, Clock.ResponseTime);
    }

    public static Goal p95Under(StepName step, Duration limit, Clock clock) {
        return Judgements.percentileUnder(step.getName(), "p95", limit, clock);
    }

    public static Goal p99Under(StepName step, Duration limit) {
        return p99Under(step, limit, Clock.ResponseTime);
    }

    public static Goal p99Under(StepName step, Duration limit, Clock clock) {
        return Judgements.percentileUnder(step.getName(), "p99", limit, clock);
    }

    public static Goal p999Under(StepName step, Duration limit) {
        return p999Under(step, limit, Clock.ResponseTime);
    }

    public static Goal p999Under(StepName step, Duration limit, Clock clock) {
        return Judgements.percentileUnder(step.getName(), "p999", limit, clock);
    }

    /** The share of requests that may fail, as a percentage: {@code 0.1} is a tenth of one percent. */
    public static Goal failureRateUnder(StepName step, double percent) {
        return Judgements.failureRateUnder(step.getName(), percent);
    }

    /** The same, asked of the whole run rather than one step. */
    public static Goal failureRateUnder(double percent) {
        return Judgements.failureRateUnder(null, percent);
    }

    /** The share of requests that were both fast enough and successful. */
    public static Goal goodputAtLeast(StepName step, Duration under, double percent) {
        return goodputAtLeast(step, under, percent, Clock.ResponseTime);
    }

    public static Goal goodputAtLeast(StepName step, Duration under, double percent, Clock clock) {
        return Judgements.goodputAtLeast(step.getName(), under, percent, clock);
    }
}

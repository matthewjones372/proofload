package io.github.matthewjones372.kestrel.java;

import io.github.matthewjones372.kestrel.Clock;
import io.github.matthewjones372.kestrel.RunResult;
import io.github.matthewjones372.kestrel.StepName;
import io.github.matthewjones372.kestrel.Verdict;
import java.time.Duration;
import java.util.List;

/**
 * What a run measured, off core's own {@code RunResult} rather than a copy of
 * it. Every duration leaves here as a {@code java.time.Duration}, since
 * {@code kotlin.time.Duration} reaches Java as a bare {@code long} whose unit
 * the type no longer states.
 *
 * The clock defaults to {@link Clock#ResponseTime}, as it does in Kotlin: a
 * goal written against service time can be met by a generator that never sent
 * the load.
 */
public final class Results {

    private Results() {
    }

    /** Every goal the run declared, with what it measured and the margin it missed by. */
    public static List<Verdict> verdicts(RunResult result) {
        return Judgements.verdicts(result);
    }

    public static boolean ran(RunResult result, StepName step) {
        return result.ran(step.getName());
    }

    public static long count(RunResult result, StepName step) {
        return Reads.stats(result, step.getName()).getCount();
    }

    public static long ok(RunResult result, StepName step) {
        return Reads.stats(result, step.getName()).getOk().getCount();
    }

    public static long failed(RunResult result, StepName step) {
        return Reads.stats(result, step.getName()).getFailed().getCount();
    }

    public static Duration p50(RunResult result, StepName step) {
        return p50(result, step, Clock.ResponseTime);
    }

    public static Duration p50(RunResult result, StepName step, Clock clock) {
        return Reads.percentile(result, step.getName(), 50.0, clock);
    }

    public static Duration p95(RunResult result, StepName step) {
        return p95(result, step, Clock.ResponseTime);
    }

    public static Duration p95(RunResult result, StepName step, Clock clock) {
        return Reads.percentile(result, step.getName(), 95.0, clock);
    }

    public static Duration p99(RunResult result, StepName step) {
        return p99(result, step, Clock.ResponseTime);
    }

    public static Duration p99(RunResult result, StepName step, Clock clock) {
        return Reads.percentile(result, step.getName(), 99.0, clock);
    }

    public static Duration max(RunResult result, StepName step) {
        return max(result, step, Clock.ResponseTime);
    }

    public static Duration max(RunResult result, StepName step, Clock clock) {
        return Reads.max(result, step.getName(), clock);
    }
}

package io.github.matthewjones372.proofload.java;

import io.github.matthewjones372.proofload.Clock;
import io.github.matthewjones372.proofload.Comparison;
import io.github.matthewjones372.proofload.ComparisonKt;
import io.github.matthewjones372.proofload.RunResult;
import io.github.matthewjones372.proofload.StepName;
import io.github.matthewjones372.proofload.Verdict;
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

    /**
     * Every step of this run against the same step of {@code baseline}, at p99
     * of response time.
     *
     * A null baseline is reported rather than ignored: a page with no
     * comparison on it reads the same whether this was a first run or a cache
     * key broke.
     */
    public static Comparison against(RunResult result, RunResult baseline) {
        return ComparisonKt.against(result, baseline, P99, Clock.ResponseTime);
    }

    /**
     * The same, at the percentile and on the clock named.
     *
     * Ask for {@link Clock#ServiceTime} where the generator fell behind: the
     * response times of such a run carry a wait this tool caused, which the
     * report says above the comparison table.
     */
    public static Comparison against(RunResult result, RunResult baseline, double percentile, Clock clock) {
        return ComparisonKt.against(result, baseline, percentile, clock);
    }

    private static final double P99 = 99.0;
}

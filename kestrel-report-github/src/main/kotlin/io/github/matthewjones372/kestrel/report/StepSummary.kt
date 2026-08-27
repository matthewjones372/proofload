package io.github.matthewjones372.kestrel.report

import io.github.matthewjones372.kestrel.Comparison
import io.github.matthewjones372.kestrel.Floor
import io.github.matthewjones372.kestrel.RunResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** What [appendToStepSummary] did, since off GitHub Actions it does nothing. */
sealed interface StepSummary {

    data class Appended(val path: Path) : StepSummary

    /** `GITHUB_STEP_SUMMARY` was unset or blank, so nothing was written anywhere. */
    data object NotOnActions : StepSummary
}

/**
 * Append this run's [markdown] to the job summary GitHub Actions names in
 * `GITHUB_STEP_SUMMARY`.
 *
 * Off Actions that variable is unset, and this writes nothing and answers
 * [StepSummary.NotOnActions] rather than throwing: the same call runs on a
 * laptop, and a load test that dies because it is not in CI is one people stop
 * running locally.
 *
 * @param environment how to read the variable. Injected so a test never has to
 *   mutate the JVM's own environment to reach either path.
 */
fun RunResult.appendToStepSummary(
    comparison: Comparison? = null,
    floor: Floor? = null,
    environment: (String) -> String? = { name -> System.getenv(name) },
): StepSummary {
    val named = environment(STEP_SUMMARY_VARIABLE)?.takeIf { it.isNotBlank() }
        ?: return StepSummary.NotOnActions

    val path = Path.of(named)
    // Appended and never truncated: the summary belongs to the whole job, and
    // the steps before this one have already written into it. The trailing
    // blank line keeps this table off the end of whatever comes next.
    Files.writeString(path, markdown(comparison, floor) + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    return StepSummary.Appended(path)
}

private const val STEP_SUMMARY_VARIABLE = "GITHUB_STEP_SUMMARY"

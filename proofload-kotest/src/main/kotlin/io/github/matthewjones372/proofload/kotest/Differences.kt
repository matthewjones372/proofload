package io.github.matthewjones372.proofload.kotest

import io.github.matthewjones372.proofload.Difference
import io.github.matthewjones372.proofload.Share
import io.github.matthewjones372.proofload.explained
import io.github.matthewjones372.proofload.notWorseThan
import io.kotest.matchers.Matcher
import io.kotest.matchers.MatcherResult

/**
 * That a statistic did not get worse by more than [acceptable].
 *
 * The threshold is declared here rather than where the comparison was made,
 * because this is where somebody decided what mattered.
 */
data class NotWorseThan(val acceptable: Share, val orCannotTell: Boolean = false) : Matcher<Difference> {

    override fun test(value: Difference): MatcherResult = MatcherResult(
        value.notWorseThan(acceptable, orCannotTell),
        { value.explained(acceptable) },
        { "${value.statistic.described} was not worse than the ${acceptable.described} declared acceptable" },
    )
}

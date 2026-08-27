package io.github.matthewjones372.kestrel.junit5

import io.github.matthewjones372.kestrel.Difference
import io.github.matthewjones372.kestrel.Share
import io.github.matthewjones372.kestrel.explained
import io.github.matthewjones372.kestrel.notWorseThan
import org.junit.jupiter.api.Assertions

/**
 * Fails the test where a statistic got worse by more than [acceptable], and
 * says what the runs concluded when it did.
 *
 * The threshold is declared here rather than where the comparison was made,
 * because this is where somebody decided what mattered.
 */
fun Difference.assertNotWorseThan(acceptable: Share, orCannotTell: Boolean = false) {
    if (!notWorseThan(acceptable, orCannotTell)) Assertions.fail<Unit>(explained(acceptable))
}

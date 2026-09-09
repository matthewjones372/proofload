package io.github.matthewjones372.proofload

/**
 * What a run's data was drawn from, in words, and the seed it drew from.
 *
 * A run's numbers mean something different at one cardinality than at another,
 * so this is what a page can state beside them, and what a comparison against
 * a baseline can refuse to reason across.
 *
 * Here rather than in `proofload-arbs`, which is where the generators that make
 * these live: a plan is a core value, and a leaf module cannot put a type of
 * its own into one.
 */
data class Shape(val description: String, val seed: Long) {

    override fun toString(): String = "$description, seed $seed"
}

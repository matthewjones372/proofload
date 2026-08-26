package io.github.matthewjones372.kestrel

/**
 * What each virtual user starts with.
 *
 * A function of the user's number rather than a cursor over a source: a cursor
 * is shared state on the path every departure takes, and a load generator that
 * locks to decide what to send is measuring itself. The number is already in
 * hand, so there is nothing to synchronise and nothing to run out of.
 *
 * It also makes a run repeatable — user 4,001 gets the same data tomorrow — so
 * a failure that names a value can be looked at rather than reproduced by luck.
 */
fun interface Feeder {

    fun forUser(user: Long): Session

    companion object {
        /** What a simulation nobody fed starts its users with. */
        val empty: Feeder = Feeder { Session.empty }
    }
}

/** Fills [key] with a value worked out from the user's number. */
fun <T : Any> feed(key: SessionKey<T>, value: (Long) -> T): Feeder =
    Feeder { user -> Session.empty.set(key, value(user)) }

/**
 * Fills [key] from [values], indexed by the user's number.
 *
 * Wraps round at the end. A feeder that ran out would end a load test for a
 * reason that has nothing to do with the target.
 */
fun <T : Any> feedFrom(key: SessionKey<T>, values: List<T>): Feeder =
    if (values.isEmpty()) Feeder.empty
    else Feeder { user -> Session.empty.set(key, values[(user % values.size).toInt()]) }

/**
 * Both, for the same user. Where they fill one key the later one wins, which
 * is what an override reads as everywhere else.
 */
operator fun Feeder.plus(next: Feeder): Feeder = Feeder { user -> forUser(user).and(next.forUser(user)) }

package io.github.matthewjones372.proofload.kafka

import io.github.matthewjones372.proofload.Reason

/**
 * The broker would not take the record, by the class of the error it gave.
 *
 * The class rather than the message, for the reason `Threw` uses one: a
 * message carries a topic, a partition and often an offset, so a report keyed
 * on it grows a row per request saying what one row says once.
 */
data class BrokerRefused(val what: String) : Reason {
    override val described: String get() = what
}

/** A record the scenario said nothing to send. */
data object NothingToSend : Reason {
    override val described: String get() = "no value"
}

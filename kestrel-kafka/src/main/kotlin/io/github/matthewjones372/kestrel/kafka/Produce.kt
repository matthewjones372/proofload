package io.github.matthewjones372.kestrel.kafka

import io.github.matthewjones372.kestrel.Action
import io.github.matthewjones372.kestrel.Correlation
import io.github.matthewjones372.kestrel.ScenarioBuilder
import io.github.matthewjones372.kestrel.StepName
import io.github.matthewjones372.kestrel.StepScope
import io.github.matthewjones372.kestrel.TimedOut
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.errors.TimeoutException
import java.util.concurrent.ExecutionException

/**
 * Produces one record to [topic], and answers the completion side with the
 * same id [keyedBy] names.
 *
 * The correlation is stated once and threaded to both: the departure the run
 * counts, and the header the record carries. Naming it twice is how a run ends
 * up matching on an id it never sent.
 *
 * What this step times is the broker's answer, which is `acks` deep — an
 * in-sync-replica round trip at `Acks.All` and nothing at all at `Acks.None`.
 * It is not a consumer having done the work, which is almost always what a
 * team load-testing Kafka wants; `completing` is where that number is.
 */
fun ScenarioBuilder.emit(name: StepName, topic: Topic, keyedBy: Correlation) {
    emit(name, Action { scope -> scope.produce(topic, keyedBy) }, keyedBy)
}

/** The same, for a step named by a string rather than a handle. */
fun ScenarioBuilder.emit(name: String, topic: Topic, keyedBy: Correlation) {
    emit(name, Action { scope -> scope.produce(topic, keyedBy) }, keyedBy)
}

/**
 * Produces one record to [topic] with nothing answering for it.
 *
 * The publish on its own: what the broker took to acknowledge, and no round
 * trip to a consumer. [emit] is the step to reach for where a second topic
 * carries the answer — this one is for a plan, or a scenario, that only
 * measures the write.
 */
fun ScenarioBuilder.produce(name: StepName, topic: Topic) {
    exec(name, Action { scope -> scope.produce(topic, UNANSWERED) })
}

/** The same, for a step named by a string rather than a handle. */
fun ScenarioBuilder.produce(name: String, topic: Topic) {
    exec(name, Action { scope -> scope.produce(topic, UNANSWERED) })
}

/**
 * The id a record carries when nothing is going to answer for it.
 *
 * Only ever read where a topic named a correlation header, which a produce step
 * with no completion has no reason to do — so this is a value that goes nowhere
 * rather than an id two steps could collide on.
 */
private val UNANSWERED = Correlation { 0L }

private fun StepScope.produce(topic: Topic, keyedBy: Correlation) {
    // The caller's own lambdas, on the departure thread: whatever
    // serialization costs is visible as this step's latency rather than hidden
    // inside one this tool wrote.
    val value = topic.value?.invoke(session) ?: return fail(NothingToSend)
    val record = ProducerRecord(topic.name, topic.key?.invoke(session), value)
    topic.header?.let { record.headers().add(it, keyedBy.of(session).toString().toByteArray()) }

    if (narrating) {
        note("produce ${record.value().size} bytes to ${topic.name} at acks=${topic.origin.acks.wire}")
        topic.header?.let { note("> $it: ${keyedBy.of(session)}") }
    }

    try {
        // Waited for on the user's own virtual thread, which unmounts while it
        // blocks. Whether there is anything to wait for is the `acks` setting's
        // business: at `Acks.None` the future is already done.
        topic.origin.producer.send(record).get()
    } catch (refused: ExecutionException) {
        fail(refused.cause.asReason())
    } catch (interrupted: InterruptedException) {
        // A run being torn down under a user rather than a broker that refused,
        // so the flag goes back for whoever is doing the tearing.
        Thread.currentThread().interrupt()
        fail(BrokerRefused(interrupted.javaClass.simpleName))
    }
}

/**
 * Kafka's own error, as this repository's vocabulary.
 *
 * A timeout is core's [TimedOut] rather than a name of its own, so "how many
 * timed out" has one answer across every module here; everything else is named
 * by its class.
 */
private fun Throwable?.asReason(): io.github.matthewjones372.kestrel.Reason = when (this) {
    null -> BrokerRefused("no reason given")
    is TimeoutException -> TimedOut
    else -> BrokerRefused(javaClass.simpleName)
}

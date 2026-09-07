package io.github.matthewjones372.kestrel.plan.kafka

import io.github.matthewjones372.kestrel.Completing
import io.github.matthewjones372.kestrel.Correlation
import io.github.matthewjones372.kestrel.SessionKey
import io.github.matthewjones372.kestrel.feed
import io.github.matthewjones372.kestrel.kafka.Header
import io.github.matthewjones372.kestrel.kafka.Kafka
import io.github.matthewjones372.kestrel.kafka.Topic
import io.github.matthewjones372.kestrel.kafka.completions
import io.github.matthewjones372.kestrel.kafka.emit
import io.github.matthewjones372.kestrel.kafka.kafka
import io.github.matthewjones372.kestrel.kafka.produce
import io.github.matthewjones372.kestrel.plan.Declaration
import io.github.matthewjones372.kestrel.plan.DeclaredStep
import io.github.matthewjones372.kestrel.plan.Lowered
import io.github.matthewjones372.kestrel.plan.Lowering
import io.github.matthewjones372.kestrel.scenario
import io.github.matthewjones372.kestrel.sessionKey
import org.apache.kafka.clients.consumer.Consumer

/**
 * The plan's Kafka steps, lowered onto a cluster.
 *
 * Handed to `asSimulation` rather than reached for from inside `kestrel-plan`,
 * so a plan of nothing but requests never sees `kafka-clients`.
 *
 * [onto] is the cluster the brokers in the plan are applied to. The default is
 * enough for anything a file describes; a caller with a producer of their own —
 * a mock, or one configured somewhere this cannot see — passes `kafka.over(it)`
 * and the plan still names the brokers. [reading] is the same for the consumer
 * a completion drains.
 */
class KafkaSteps(
    private val onto: Kafka = kafka,
    private val reading: Consumer<ByteArray, ByteArray>? = null,
) : Lowering {

    override fun lower(step: DeclaredStep, plan: Declaration): Lowered? = when (step) {
        is DeclaredStep.Request -> null

        // Built through the DSL rather than assembled here, so a plan cannot
        // describe a step the language could not have.
        is DeclaredStep.Produce -> {
            val answer = plan.answerTo(step)
            val topic = step.topic(plan, answer)
            Lowered(
                steps = scenario(plan.scenario) {
                    if (answer == null) produce(step.name, topic) else emit(step.name, topic, keyedBy = BY_USER)
                }.steps,
                feeder = answer?.let { feed(USER) { user -> user } },
            )
        }

        is DeclaredStep.Completes -> Lowered(
            completing = Completing(
                step = step.name,
                from = step.topic(plan).completions(reading),
                drainingFor = step.within,
            ),
        )
    }

    /**
     * The topic one produce step sends to.
     *
     * The body is UTF-8 of what the file said, and the key with it. A plan
     * carries no lambda, so every record from this step is the same bytes —
     * `kestrel emit` and a feeder are where per-user variety lives.
     */
    private fun DeclaredStep.Produce.topic(plan: Declaration, answer: DeclaredStep.Completes?): Topic {
        val written = cluster(plan, settings).topic(topic).value { body.toByteArray() }
        val keyed = key?.let { literal -> written.keyed { literal.toByteArray() } } ?: written
        return answer?.let { keyed.correlatedBy(Header(it.by)) } ?: keyed
    }

    /**
     * The topic a completion is read off.
     *
     * The group is a producer setting name away from the rest, so it goes
     * through the same map: `kestrel-kafka` opens the consumer with the
     * settings its `Kafka` carries.
     */
    private fun DeclaredStep.Completes.topic(plan: Declaration): Topic =
        cluster(plan, group?.let { mapOf("group.id" to it) }.orEmpty())
            .topic(on)
            .correlatedBy(Header(by))

    private fun cluster(plan: Declaration, settings: Map<String, String>): Kafka {
        val brokers = requireNotNull(plan.brokers) { "a Kafka step needs brokers, and `${plan.scenario}` names none" }
        return settings.entries.fold(onto.brokers(brokers)) { kafka, (name, value) -> kafka.setting(name, value) }
    }

    private companion object {

        /**
         * The id a record is answered by: the user's number.
         *
         * Unique per departure in an open model, and the only value a plan has
         * to correlate on — everything richer needs a lambda, which is what
         * `kestrel emit` is for.
         */
        val USER: SessionKey<Long> = sessionKey("user")

        val BY_USER = Correlation { session -> session[USER] ?: -1L }
    }
}

/** The declared answer to [step], where the plan declares one. */
private fun Declaration.answerTo(step: DeclaredStep.Produce): DeclaredStep.Completes? =
    steps.filterIsInstance<DeclaredStep.Completes>().firstOrNull { it.completes == step.name }

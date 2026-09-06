package io.github.matthewjones372.kestrel.plan.kafka

import io.github.matthewjones372.kestrel.Step
import io.github.matthewjones372.kestrel.kafka.Kafka
import io.github.matthewjones372.kestrel.kafka.Topic
import io.github.matthewjones372.kestrel.kafka.kafka
import io.github.matthewjones372.kestrel.kafka.produce
import io.github.matthewjones372.kestrel.plan.Declaration
import io.github.matthewjones372.kestrel.plan.DeclaredStep
import io.github.matthewjones372.kestrel.plan.Lowering
import io.github.matthewjones372.kestrel.scenario

/**
 * The plan's produce steps, lowered onto a cluster.
 *
 * Handed to `asSimulation` rather than reached for from inside `kestrel-plan`,
 * so a plan of nothing but requests never sees `kafka-clients`.
 *
 * [onto] is the cluster the brokers in the plan are applied to. The default is
 * enough for anything a file describes; a caller with a producer of their own —
 * a mock, or one configured somewhere this cannot see — passes `kafka.over(it)`
 * and the plan still names the brokers.
 */
class KafkaSteps(private val onto: Kafka = kafka) : Lowering {

    override fun lower(step: DeclaredStep, plan: Declaration): List<Step>? = when (step) {
        is DeclaredStep.Request -> null

        // Built through the DSL rather than assembled here, so a plan cannot
        // describe a step the language could not have.
        is DeclaredStep.Produce -> scenario(plan.scenario) { produce(step.name, step.topic(plan)) }.steps
    }

    /**
     * The topic one step sends to.
     *
     * The body is UTF-8 of what the file said, and the key with it. A plan
     * carries no lambda, so every record from this step is the same bytes —
     * `kestrel emit` and a feeder are where per-user variety lives.
     */
    private fun DeclaredStep.Produce.topic(plan: Declaration): Topic {
        val brokers = requireNotNull(plan.brokers) { "a produce step needs brokers, and `${plan.scenario}` names none" }
        val cluster = settings.entries.fold(onto.brokers(brokers)) { kafka, (name, value) ->
            kafka.setting(name, value)
        }

        val topic = cluster.topic(topic).value { body.toByteArray() }
        return key?.let { written -> topic.keyed { written.toByteArray() } } ?: topic
    }
}

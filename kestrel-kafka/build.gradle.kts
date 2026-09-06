plugins {
    // So the dependency on core is exported: this module's public signatures
    // are made of core's types and Kafka's.
    `java-library`
    // The fake broker is a fixture rather than a test source: `kestrel-plan-kafka`
    // proves it lowered a plan onto a topic by producing to the same socket, and
    // a second hand-rolled broker would be a second thing to keep in step with
    // `kafka-clients`' own wire versions.
    `java-test-fixtures`
}

// `kafka-clients` and nothing else of anyone's.
//
// No schema registry: `io.confluent:kafka-avro-serializer` is not on Maven
// Central — checked, 404 against repo1 where `kafka-clients` is 200 — so
// depending on it would force a `packages.confluent.io` declaration on every
// consumer of a published module, and would break the smoke project, which
// resolves from `mavenCentral()` on purpose.
//
// No serializer of this tool's own either. Avro, Protobuf and JSON Schema are
// the caller's choice, and keeping them out also keeps the measurement honest:
// whatever serialization costs, it is the caller's own lambda running on the
// departure thread, visible as such rather than hidden inside a step this tool
// wrote.
dependencies {
    api(project(":kestrel-core"))
    api("org.apache.kafka:kafka-clients:3.9.1")

    // An engine to run the scenarios these tests build, and nothing that is a
    // broker: whether a cluster is sized right needs the caller's cluster.
    testImplementation(project(":kestrel-engine"))
}

// The fixture is for this repository's own modules, not for consumers: without
// this, `java-test-fixtures` adds two variants to the published component and a
// `-test-fixtures` jar goes to Maven Central beside the library.
afterEvaluate {
    listOf("testFixturesApiElements", "testFixturesRuntimeElements").forEach { variant ->
        (components["java"] as AdhocComponentWithVariants)
            .withVariantsFromConfiguration(configurations[variant]) { skip() }
    }
}

tasks.test {
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dkestrel.kafka.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}

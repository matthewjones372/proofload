plugins {
    // So the dependency on core is exported: this module's public signatures
    // are made of core's types and Kafka's.
    `java-library`
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

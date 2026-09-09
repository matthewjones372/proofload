// The module that lowers a plan's produce steps onto a cluster.
//
// It sits beside `proofload-plan` rather than inside it. A plan of nothing but
// requests is the common case and has no business inheriting `kafka-clients`
// and its compression codecs; `proofload-plan` declares a `Lowering` and this
// module supplies one, which is the same arrangement core uses for `Action`.
plugins {
    // So both dependencies are exported: this module's one public type is made
    // of the plan's and Kafka's.
    `java-library`
}

dependencies {
    api(project(":proofload-plan"))
    api(project(":proofload-kafka"))

    // The engine runs the two scenarios these tests compare, and the fake
    // broker gives them a socket a real producer connects to — the one place
    // the accumulator this lowering hands records to is visible.
    testImplementation(project(":proofload-engine"))
    testImplementation(testFixtures(project(":proofload-kafka")))
}

tasks.test {
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dproofload.plan.kafka.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}

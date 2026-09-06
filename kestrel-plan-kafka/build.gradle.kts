// The module that lowers a plan's produce steps onto a cluster.
//
// It sits beside `kestrel-plan` rather than inside it. A plan of nothing but
// requests is the common case and has no business inheriting `kafka-clients`
// and its compression codecs; `kestrel-plan` declares a `Lowering` and this
// module supplies one, which is the same arrangement core uses for `Action`.
plugins {
    // So both dependencies are exported: this module's one public type is made
    // of the plan's and Kafka's.
    `java-library`
}

dependencies {
    api(project(":kestrel-plan"))
    api(project(":kestrel-kafka"))

    // The engine runs the two scenarios these tests compare, and the fake
    // broker gives them a socket a real producer connects to — the one place
    // the accumulator this lowering hands records to is visible.
    testImplementation(project(":kestrel-engine"))
    testImplementation(testFixtures(project(":kestrel-kafka")))
}

tasks.test {
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dkestrel.plan.kafka.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}

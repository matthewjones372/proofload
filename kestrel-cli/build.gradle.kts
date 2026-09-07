// The command line: a plan in, a verdict out, and an exit code a shell can
// branch on. Not a second product — every command here is a call the library
// already exposes, so what the CLI can do is what a Kotlin caller can do.
dependencies {
    api(project(":kestrel-core"))
    api(project(":kestrel-plan"))
    // The command line reads plans, and a plan may name a topic. Refusing one
    // here would leave `plan/1` half-usable from the tool it exists for, so the
    // Kafka client arrives with this module rather than with `kestrel-plan` —
    // which is what keeps it off a library consumer's classpath.
    api(project(":kestrel-plan-kafka"))
    implementation(project(":kestrel-openapi"))
    implementation(project(":kestrel-engine"))
    implementation(project(":kestrel-export"))
}

tasks.test {
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dkestrel.cli.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}

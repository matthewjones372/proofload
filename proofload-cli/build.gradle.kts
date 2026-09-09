// The command line: a plan in, a verdict out, and an exit code a shell can
// branch on. Not a second product — every command here is a call the library
// already exposes, so what the CLI can do is what a Kotlin caller can do.
dependencies {
    api(project(":proofload-core"))
    api(project(":proofload-plan"))
    // The command line reads plans, and a plan may name a topic. Refusing one
    // here would leave `plan/1` half-usable from the tool it exists for, so the
    // Kafka client arrives with this module rather than with `proofload-plan` —
    // which is what keeps it off a library consumer's classpath.
    api(project(":proofload-plan-kafka"))
    implementation(project(":proofload-openapi"))
    implementation(project(":proofload-engine"))
    implementation(project(":proofload-export"))
}

// So `jbang io.github.matthewjones372:proofload-cli:VERSION validate plan.yaml`
// works without being told the class. Named literally because this module has no
// `application` block to take it from; `Cli.kt`'s `main` is the whole entry point.
tasks.jar {
    manifest { attributes("Main-Class" to "io.github.matthewjones372.proofload.cli.CliKt") }
}

tasks.test {
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dproofload.cli.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}

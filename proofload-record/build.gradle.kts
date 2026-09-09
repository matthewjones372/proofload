plugins {
    // So the dependency on core is exported to consumers: this module's public
    // signatures are made of core's types.
    `java-library`
    // The only module with an entry point. A tool nobody depends on is not a
    // library, but it is not worth a second set of coordinates either, and it
    // runs before a run rather than during one.
    application
}

// A HAR is JSON out of a browser, and this repository takes no dependency
// lightly — but the rule it holds to is *per module*: proofload-kafka carries
// kafka-clients, proofload-otel carries the OTel SDK, proofload-core carries
// nothing. This is another such module, and it is further from the timed path
// than either: it runs before a run rather than during one.
//
// The `V2Encoding.kt` precedent for hand-rolling does not apply. That is a few
// hundred bytes of a written-down binary format; this is arbitrary JSON.
dependencies {
    api(project(":proofload-core"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
}

application {
    mainClass.set("io.github.matthewjones372.proofload.record.MainKt")
}

tasks.named<JavaExec>("run") {
    // The directory a reader is standing in when they type the command, which
    // is the repository root and not this module: a relative path in `--args`
    // otherwise resolves somewhere nobody was looking.
    workingDir = rootProject.projectDir
}

dependencies {
    // The generated scenario checked in under `src/test` is compiled here, which
    // is what makes "the generated file compiles" a gate rather than a claim.
    testImplementation(project(":proofload-http"))
}

tasks.test {
    // The recording the cookbook's own checkout was exported from, so the test
    // that regenerates it is reading the file a reader can open.
    systemProperty("proofload.record.checkoutHar", rootProject.file("docs/examples/checkout.har").absolutePath)

    // Hand the *main* runtime classpath to the test JVM, so the assertion about
    // what this module carries is about what a consumer would get.
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dproofload.record.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}

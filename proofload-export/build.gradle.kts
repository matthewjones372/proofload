// A run's numbers in formats other tools already read. Core and the JDK only
// on the main classpath: `Deflater` and `Base64` are all the histogram log
// encoding needs, and a consumer who wants one export should not inherit a
// library for it.
//
// HdrHistogram is on the *test* classpath as the oracle. Its own reader
// reading this module's output back is what proves the encoding, and a
// hand-written encoder checked against nothing would be a guess.
dependencies {
    api(project(":proofload-core"))
    testImplementation("org.hdrhistogram:HdrHistogram:2.2.2")
    // The schema is the contract this module publishes, so it is checked by a
    // real validator rather than by a reader written here — the same reason
    // HdrHistogram sits above as the oracle for the encoding.
    testImplementation("com.networknt:json-schema-validator:3.0.7")
}

tasks.test {
    val mainRuntime: FileCollection = configurations.runtimeClasspath.get()
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dproofload.export.runtimeClasspath=" +
                    mainRuntime.joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}

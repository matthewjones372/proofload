// A run's numbers in formats other tools already read. Core and the JDK only
// on the main classpath: `Deflater` and `Base64` are all the histogram log
// encoding needs, and a consumer who wants one export should not inherit a
// library for it.
//
// HdrHistogram is on the *test* classpath as the oracle. Its own reader
// reading this module's output back is what proves the encoding, and a
// hand-written encoder checked against nothing would be a guess.
dependencies {
    api(project(":kestrel-core"))
    testImplementation("org.hdrhistogram:HdrHistogram:2.2.2")
}

tasks.test {
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dkestrel.export.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}

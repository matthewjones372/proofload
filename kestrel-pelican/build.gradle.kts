// Pelican's ClientTransport is a one-method interface in pelican-core, with
// Pekko behind it in pelican-client-pekko. Implementing that seam is the whole
// integration: a generated typed client then runs inside a load test on
// virtual threads, with no actor system for this tool to measure.
//
// pelican-core is a released version from Maven Central, not a composite build
// of a sibling checkout. A module that only compiles on one laptop is not a
// module.
dependencies {
    api(project(":kestrel-core"))
    api("io.github.matthewjones372:pelican-core:1.0.0-RC1")
}

tasks.test {
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dkestrel.pelican.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}

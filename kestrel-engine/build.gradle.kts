// The JDK and kestrel-core, and nothing else. Virtual threads and
// `ScheduledExecutorService` are the whole runtime this engine needs, so a
// third-party arrival here would be a dependency every consumer of a run
// inherits for no gain.
//
// `NoThirdPartyDependenciesTest` asserts that list rather than promising it.
dependencies {
    // `api`, not `implementation`: `run()` returns core's `RunResult` and takes
    // core's `Simulation`, so a consumer cannot call it without core on the
    // compile classpath.
    api(project(":kestrel-core"))
}

tasks.test {
    // Hand the *main* runtime classpath to the test JVM. The test classpath
    // necessarily carries JUnit; only the main one is meant to be bare.
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dkestrel.engine.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}

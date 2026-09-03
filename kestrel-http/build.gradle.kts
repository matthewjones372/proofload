plugins {
    // So the dependency on core is exported to consumers: this module's public
    // signatures are made of core's types, and `implementation` would leave a
    // caller unable to name the `Action` it is handed.
    `java-library`
}

// The client is `java.net.http`, which ships with the JDK. That is why a module
// carrying HTTP still puts nothing on a consumer's classpath but kestrel-core;
// a Ktor or OkHttp module would be a third module beside this one rather than a
// dependency added here.
//
// `NoThirdPartyDependenciesTest` asserts that list rather than promising it.
dependencies {
    api(project(":kestrel-core"))

    // An engine to run the scenarios the SSE tests build. Test-only: this
    // module measures steps and does not schedule them, and the assertion
    // below is about the *main* classpath, which stays core and the JDK.
    testImplementation(project(":kestrel-engine"))
}

tasks.test {
    // Smaller than the response `DiscardingBodyTest` measures, so "the bytes
    // are counted rather than held" is a gate rather than a claim: a step that
    // held that body runs out of heap here instead of in somebody's download
    // test. Every other test in this module is far inside it.
    maxHeapSize = "96m"

    // Hand the *main* runtime classpath to the test JVM. The test classpath
    // necessarily carries JUnit; only the main one is meant to be bare.
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dkestrel.http.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}

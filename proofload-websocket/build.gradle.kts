plugins {
    // So the dependency on core is exported to consumers: this module's public
    // signatures are made of core's types, and `implementation` would leave a
    // caller unable to name the `StepName` a connection is opened under.
    `java-library`
}

// The client is `java.net.http.WebSocket`, which ships with the JDK. That is
// why a module carrying WebSocket still puts nothing on a consumer's classpath
// but proofload-core; a Netty or Ktor module would be a third module beside this
// one rather than a dependency added here.
//
// `NoThirdPartyDependenciesTest` asserts that list rather than promising it.
dependencies {
    api(project(":proofload-core"))
}

tasks.test {
    // Hand the *main* runtime classpath to the test JVM. The test classpath
    // necessarily carries JUnit; only the main one is meant to be bare.
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dproofload.websocket.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}

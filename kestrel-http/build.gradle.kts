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
}

tasks.test {
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

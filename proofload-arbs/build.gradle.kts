plugins {
    // So the dependency on core is exported to consumers: a generator is read
    // by a `Feeder`, and `implementation` would leave a caller unable to name
    // the `Session` it fills.
    `java-library`
}

// Core and the JDK. A generator here is arithmetic over the user's number, so
// a project that wanted shaped keys inherits no property-testing library, no
// faker and no megabyte of sample surnames for it.
//
// `NoThirdPartyDependenciesTest` asserts that rather than promising it.
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
                "-Dproofload.arbs.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}

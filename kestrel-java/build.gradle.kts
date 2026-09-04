plugins {
    // So the modules this delegates to are exported to consumers: every
    // signature here is made of their types, and `implementation` would leave a
    // Java caller unable to name the `Scenario` it is handed.
    `java-library`
}

// The facade builds core's values and computes nothing, so it carries the
// modules those values come from and no library beside them.
// `NoThirdPartyDependenciesTest` asserts that rather than promising it.
dependencies {
    api(project(":kestrel-core"))
    api(project(":kestrel-engine"))
    api(project(":kestrel-http"))
}

tasks.test {
    // Hand the *main* runtime classpath to the test JVM. The test classpath
    // necessarily carries JUnit; only the main one is meant to be bare.
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dkestrel.java.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}

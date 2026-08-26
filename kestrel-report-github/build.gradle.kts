// A leaf module that carries no library at all. The whole GitHub integration
// is a markdown string and a file named by an environment variable, so the JDK
// and kestrel-core are the entire runtime classpath — and
// `NoThirdPartyDependenciesTest` asserts that rather than promising it.
plugins {
    `java-library`
}

dependencies {
    // `api`, not `implementation`: every function here is an extension on a
    // core type, so a caller cannot use this module without core in scope.
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
                "-Dkestrel.report.github.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}

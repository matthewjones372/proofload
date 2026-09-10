// Deliberately minimal. A scenario is a value, so this module has nothing on
// its runtime classpath but the Kotlin standard library — no HTTP client, no
// reporting library, no coroutine engine of its own. Everything with a
// third-party type in it is a leaf module beside this one.
//
// `NoThirdPartyDependenciesTest` asserts that list rather than promising it.
// If this file ever grows a client dependency, the layering has been broken.
dependencies {
    // Nothing. On purpose.
}

tasks.test {
    // Hand the *main* runtime classpath to the test JVM. The test classpath
    // necessarily carries JUnit; only the main one is meant to be bare.
    val mainRuntime: FileCollection = configurations.runtimeClasspath.get()
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dproofload.core.runtimeClasspath=" +
                    mainRuntime.joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}

// A load test is an ordinary JUnit test, so this module carries JUnit as a real
// dependency — it is a leaf, which is exactly what AGENTS.md allows. What it
// must not carry is a second load-testing stack: core describes, engine runs,
// and nothing here reaches past them.
dependencies {
    api(project(":proofload-core"))
    api(project(":proofload-engine"))
    api("org.junit.jupiter:junit-jupiter-api:6.1.3")
}

dependencies {
    // The tests here run JUnit inside JUnit: a test that asserts on how a
    // failing load test is reported has to execute one and read the outcome.
    testImplementation("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    // The fixtures this module's tests execute through the launcher include one
    // that fails on purpose. Tagged out here, so the build runs the tests that
    // read their outcome rather than the fixtures themselves.
    useJUnitPlatform { excludeTags("fixture") }

    val mainRuntime: FileCollection = configurations.runtimeClasspath.get()
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dproofload.junit5.runtimeClasspath=" +
                    mainRuntime.joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}

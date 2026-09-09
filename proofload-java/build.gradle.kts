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
    api(project(":proofload-core"))
    api(project(":proofload-engine"))
    api(project(":proofload-http"))
}

tasks.test {
    // `FromJavaDocTest` reads the page and the source set it quotes, so both are
    // inputs: editing either re-runs the test rather than being told the task is
    // up to date.
    val page = rootProject.layout.projectDirectory.file("docs/from-java.md")
    val gate = rootProject.layout.projectDirectory.dir("examples-java/src")
    inputs.files(page).withPropertyName("theJavaPage")
    inputs.dir(gate).withPropertyName("theSourceSetItQuotes")
    systemProperty("proofload.repoRoot", rootProject.projectDir.path)

    // Hand the *main* runtime classpath to the test JVM. The test classpath
    // necessarily carries JUnit; only the main one is meant to be bare.
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dproofload.java.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}

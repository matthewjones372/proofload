// A plan somebody wrote down, lowered into the values the Kotlin DSL builds.
//
// The model and the lowering sit here rather than in core because lowering is
// what makes them worth having, and lowering a `post: /orders` into an action
// needs an HTTP client. Core declares an `Action` without a protocol type and
// may not grow one, so the module that already carries the JDK's client is the
// one that can turn a path into a step.
// The parser is snakeyaml-engine and nothing more. YAML 1.2 is a superset of
// JSON, so one dependency reads a plan a person hand-edited with comments in it
// and a plan a program generated, and there is no second reader to disagree
// with the first. It is the sibling repository's answer to the same question
// and is copied rather than re-decided.
dependencies {
    api(project(":proofload-core"))
    api(project(":proofload-http"))
    // Generators, so a plan can draw a value per user instead of sending one
    // id ten thousand times. `proofload-arbs` depends on core and nothing else,
    // so this arrives with no third-party jar behind it — which is why it can
    // live here rather than in a module of its own the way Kafka had to.
    api(project(":proofload-arbs"))
    implementation("org.snakeyaml:snakeyaml-engine:3.1.1")
}

tasks.test {
    val mainRuntime: FileCollection = configurations.runtimeClasspath.get()
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dproofload.plan.runtimeClasspath=" +
                    mainRuntime.joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}

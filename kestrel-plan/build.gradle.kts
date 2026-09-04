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
    api(project(":kestrel-core"))
    api(project(":kestrel-http"))
    implementation("org.snakeyaml:snakeyaml-engine:2.10")
}

tasks.test {
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dkestrel.plan.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}

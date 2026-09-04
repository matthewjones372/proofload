// A plan somebody wrote down, lowered into the values the Kotlin DSL builds.
//
// The model and the lowering sit here rather than in core because lowering is
// what makes them worth having, and lowering a `post: /orders` into an action
// needs an HTTP client. Core declares an `Action` without a protocol type and
// may not grow one, so the module that already carries the JDK's client is the
// one that can turn a path into a step.
dependencies {
    api(project(":kestrel-core"))
    api(project(":kestrel-http"))
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

// Keeps a run on disk so the next one can ask whether anything got worse. Core
// and the JDK only: a baseline that needed a JSON library would put one on the
// classpath of every consumer that wanted to compare two runs.
dependencies {
    api(project(":kestrel-core"))
}

tasks.test {
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dkestrel.baseline.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}

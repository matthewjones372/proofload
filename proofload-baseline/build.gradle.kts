// Keeps a run on disk so the next one can ask whether anything got worse. Core
// and the JDK only: a baseline that needed a JSON library would put one on the
// classpath of every consumer that wanted to compare two runs.
dependencies {
    api(project(":proofload-core"))
}

tasks.test {
    val mainRuntime: FileCollection = configurations.runtimeClasspath.get()
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dproofload.baseline.runtimeClasspath=" +
                    mainRuntime.joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}

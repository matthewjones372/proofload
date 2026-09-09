// The page is hand-written: no JSON library, no templating engine, no chart
// library. The report has to open from a `file://` URL on a locked-down
// network years from now, so everything it needs is in the one file it writes,
// and everything that writes it is in the standard library.
//
// `NoThirdPartyDependenciesTest` asserts that list rather than promising it.
plugins {
    // For `api`: a consumer holding a `RunResult` to report on needs core on
    // its own compile classpath, so core is part of this module's surface.
    `java-library`
}

dependencies {
    api(project(":proofload-core"))
}

tasks.test {
    // Hand the *main* runtime classpath to the test JVM. The test classpath
    // necessarily carries JUnit; only the main one is meant to be bare.
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dproofload.report.html.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}

// A contract in, a plan out. Pelican endpoint values today; an OpenAPI document
// through `pelican-import` next.
//
// Its own module rather than a `planFrom` inside `proofload-pelican`, which is
// what 0091 sketched. That module's job is the transport seam, and its
// dependency test promises a consumer nothing but core and `pelican-core`;
// giving it a plan generator would put an HTTP client and a YAML parser on the
// runtime classpath of everybody who only wanted their typed client to run
// inside a load test. This is build-time tooling, like `proofload-record`, and
// nothing here is reached while a run is measuring.
dependencies {
    api(project(":proofload-plan"))
    api(project(":proofload-openapi"))
    api("io.github.matthewjones372:pelican-core:1.0.0-RC1")
}

tasks.test {
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dproofload.contract.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}

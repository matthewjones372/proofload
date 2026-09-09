// An OpenAPI document in, a plan out.
//
// Apart from `proofload-contract` so that reading a document costs nothing from
// Pelican: a caller with a document and no Pelican service — which is most of
// them — should not take `pelican-core` to use `proofload from-openapi`. The
// contract module depends on this one for the half they share, which is turning
// a schema's facets into a value the service will accept.
dependencies {
    api(project(":proofload-plan"))
    implementation("org.snakeyaml:snakeyaml-engine:2.10")
}

tasks.test {
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dproofload.openapi.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}

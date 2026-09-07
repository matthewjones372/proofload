plugins {
    // So the dependencies on core and grpc are exported: this module's public
    // signatures are made of protobuf's descriptor types and gRPC's.
    `java-library`
}

// Beside `kestrel-grpc` rather than inside it.
//
// `kestrel-grpc` carries `grpc-api` and a caller's own generated stubs, which
// is the typed path and the faster one: a renamed field breaks their build.
// This module is for the caller who has a plan file and no stubs on the
// classpath, and buying that convenience costs protobuf's whole runtime — the
// descriptor types here, its JSON printer next, the reflection service after
// that. Nobody wanting the typed path should inherit that stack.
dependencies {
    api(project(":kestrel-core"))
    api(project(":kestrel-grpc"))

    // The descriptor types, and the JSON printer that turns what a plan wrote
    // into one of them. The reflection service arrives with the branch that
    // needs it, so each dependency claim is made where it can be argued with.
    api("com.google.protobuf:protobuf-java:4.33.0")
    api("com.google.protobuf:protobuf-java-util:4.33.0")
}

tasks.test {
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dkestrel.grpc.dynamic.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}

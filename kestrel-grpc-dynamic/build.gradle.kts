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

    // gRPC's own protobuf marshaller, so a dynamic call puts the same bytes on
    // the wire a generated stub would and this module is not the author of a
    // wire format. The `-lite` artefact rather than `grpc-protobuf`: the
    // marshaller is all that is wanted, and the full one drags protobuf's
    // `Any` support and the `com.google.api` protos with it.
    api("io.grpc:grpc-protobuf-lite:1.78.0")
}

dependencies {
    // A server these tests call, on the test classpath only. It answers with
    // `DynamicMessage` too, so nothing here needs generated code either — which
    // is the same claim the module makes, made twice.
    testImplementation("io.grpc:grpc-inprocess:1.78.0")

    // An engine to run the scenarios these tests build. Test-only: a module of
    // steps does not depend on the thing that runs them.
    testImplementation(project(":kestrel-engine"))
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

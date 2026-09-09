plugins {
    // So the dependencies on core and grpc are exported: this module's public
    // signatures are made of protobuf's descriptor types and gRPC's.
    `java-library`
}

// Beside `proofload-grpc` rather than inside it.
//
// `proofload-grpc` carries `grpc-api` and a caller's own generated stubs, which
// is the typed path and the faster one: a renamed field breaks their build.
// This module is for the caller who has a plan file and no stubs on the
// classpath, and buying that convenience costs protobuf's whole runtime — the
// descriptor types here, its JSON printer next, the reflection service after
// that. Nobody wanting the typed path should inherit that stack.
dependencies {
    api(project(":proofload-core"))
    api(project(":proofload-grpc"))

    // The descriptor types, and the JSON printer that turns what a plan wrote
    // into one of them. The reflection service arrives with the branch that
    // needs it, so each dependency claim is made where it can be argued with.
    api("com.google.protobuf:protobuf-java:4.36.1")
    api("com.google.protobuf:protobuf-java-util:4.36.1")

    // gRPC's own protobuf marshaller, so a dynamic call puts the same bytes on
    // the wire a generated stub would and this module is not the author of a
    // wire format. The `-lite` artefact rather than `grpc-protobuf`: the
    // marshaller is all that is wanted, and the full one drags protobuf's
    // `Any` support and the `com.google.api` protos with it.
    api("io.grpc:grpc-protobuf-lite:1.84.0")
    // The reflection stubs, for a target that will hand over its own
    // descriptors. It is the heaviest thing here: `grpc-core`, `grpc-protobuf`
    // and `proto-google-common-protos` arrive with it. Tolerable only because
    // a classpath that can actually send a gRPC call already carries a
    // transport, and every transport depends on `grpc-core` anyway — and
    // because the alternative is hand-writing gRPC's own wire protocol, whose
    // test would be written against the same hand-written descriptors it was
    // meant to check.
    api("io.grpc:grpc-services:1.84.0")
}

dependencies {
    // A server these tests call, on the test classpath only. It answers with
    // `DynamicMessage` too, so nothing here needs generated code either — which
    // is the same claim the module makes, made twice.
    testImplementation("io.grpc:grpc-inprocess:1.84.0")

    // Named rather than reached through `grpc-services`: the server here
    // attaches a schema descriptor to its service so reflection has something
    // to read, and that supplier is this artefact's type.
    testImplementation("io.grpc:grpc-protobuf:1.84.0")

    // An engine to run the scenarios these tests build. Test-only: a module of
    // steps does not depend on the thing that runs them.
    testImplementation(project(":proofload-engine"))
}

tasks.test {
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dproofload.grpc.dynamic.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}

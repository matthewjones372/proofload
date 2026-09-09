plugins {
    // So the dependency on core is exported: this module's public signatures
    // are made of core's types and gRPC's, and a caller must be able to name
    // the `Action` it is handed.
    `java-library`
}

// `grpc-api` and nothing else of gRPC's. Not `grpc-core`, not a transport, not
// `grpc-kotlin-stub`:
//
//   * a transport carries a thread model, and a caller who has stubs has
//     already chosen one — `ManagedChannelBuilder.forTarget` finds it by
//     service loader at runtime, on the caller's own classpath;
//   * `grpc-kotlin-stub` would put kotlinx-coroutines on every consumer's
//     classpath, and the seam here sits *under* the stub, so a caller's
//     coroutine stub is named, traced and bounded anyway.
//
// `api` rather than `compileOnly`, unlike Kotest in proofload-kotest: the
// signatures here are `MethodDescriptor` and `ManagedChannel`, so a consumer
// cannot call this module without them.
dependencies {
    api(project(":proofload-core"))
    api("io.grpc:grpc-api:1.78.0")

    // `grpc-stub` too, and only for `StreamObserver`: a streaming seam has to
    // speak the type a generated async stub is written against, and that type
    // lives here rather than in `grpc-api`. It adds nothing a gRPC caller does
    // not already have — generated code depends on it — and none of the
    // refusals move: still no transport, no protobuf runtime, no coroutines.
    api("io.grpc:grpc-stub:1.78.0")

    // A server this module's own tests can call, on the test classpath only.
    // Whether a cluster is sized right needs the caller's cluster; what an
    // in-process server proves is that a step is named, timed and recorded.
    testImplementation("io.grpc:grpc-inprocess:1.78.0")

    // A real transport, on the test classpath only, and for one test: that a
    // refused connection arrives as `UNAVAILABLE` rather than as a
    // `ConnectException` needs a socket, and an in-process server has none.
    // That this module needs to borrow one to write that test is the
    // dependency claim demonstrating itself.
    testImplementation("io.grpc:grpc-okhttp:1.78.0")

    // An engine to run the scenarios these tests build. Test-only:
    // a module of steps does not depend on the thing that runs them.
    testImplementation(project(":proofload-engine"))
}

tasks.test {
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dproofload.grpc.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}

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
// `api` rather than `compileOnly`, unlike Kotest in kestrel-kotest: the
// signatures here are `MethodDescriptor` and `ManagedChannel`, so a consumer
// cannot call this module without them.
dependencies {
    api(project(":kestrel-core"))
    api("io.grpc:grpc-api:1.78.0")

    // A server this module's own tests can call, on the test classpath only.
    // Whether a cluster is sized right needs the caller's cluster; what an
    // in-process server proves is that a step is named, timed and recorded.
    testImplementation("io.grpc:grpc-inprocess:1.78.0")
    testImplementation("io.grpc:grpc-stub:1.78.0")

    // An engine to run the scenarios these tests build. Test-only:
    // a module of steps does not depend on the thing that runs them.
    testImplementation(project(":kestrel-engine"))
}

tasks.test {
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dkestrel.grpc.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}

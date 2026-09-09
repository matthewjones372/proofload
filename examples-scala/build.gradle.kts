// Not published and not a library. `proofload-scala` has no `.api` dump — what
// BCV records of a Scala module is names no caller can type — so the gate is a
// compiled Scala source set, and a conversion or an extension that goes breaks
// the build here rather than in somebody's project.
//
// A module of its own rather than a source set inside `examples`, for the
// reason `examples-java` is one: those are Kotlin, compiled under conventions
// of their own.
plugins {
    scala
}

val zioVersion = "2.1.26"

dependencies {
    implementation(project(":proofload-scala"))

    // The spec beside the sample is the same gate for `proofload-zio-test`, and
    // it runs rather than only compiling: a consumer's project is where a
    // published module either works or does not.
    testImplementation(project(":proofload-zio-test"))
    testImplementation("dev.zio:zio_3:$zioVersion")
    testImplementation("dev.zio:zio-test_3:$zioVersion")
    testRuntimeOnly("dev.zio:zio-test-junit-engine_3:$zioVersion")
}

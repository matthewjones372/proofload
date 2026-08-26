// A leaf that compiles against Kotest without shipping it. This module is only
// ever used where Kotest is already on the test classpath, and its framework
// engine drags twenty-odd jars — byte-buddy, jna, classgraph — that a consumer
// would then inherit from a load-testing library rather than choosing.
//
// What it must not carry is the other framework module: if wiring a second
// framework needed anything from the first, "framework-agnostic" was
// decoration, and the dependency test beside this is where that gets caught.
dependencies {
    api(project(":kestrel-core"))
    api(project(":kestrel-engine"))
    compileOnly("io.kotest:kotest-framework-engine:6.2.4")

    // The specs here need a real Kotest to run against, and its JUnit platform
    // runner to be discovered by Gradle. Both are test-only: a consumer brings
    // their own.
    testImplementation("io.kotest:kotest-framework-engine:6.2.4")
    testImplementation("io.kotest:kotest-runner-junit5:6.2.4")
}

tasks.test {
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dkestrel.kotest.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}

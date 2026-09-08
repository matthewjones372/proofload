plugins {
    scala
    `java-library`
}

// A leaf that compiles against zio-test without shipping it. A spec that uses
// this module already has zio-test on its test classpath, and one that does not
// should not be handed an effect runtime by a load-testing library.
//
// What it must not carry is either of the other framework modules: if wiring a
// third framework needed anything from the first two, "framework-agnostic" was
// decoration, and `NoSecondStackTest` beside this is where that gets caught.
val zioVersion = "2.1.26"

dependencies {
    api(project(":kestrel-scala"))
    compileOnly("dev.zio:zio_3:$zioVersion")
    compileOnly("dev.zio:zio-test_3:$zioVersion")

    // The specs here need a real zio-test to run against, and its JUnit
    // platform engine to be discovered by Gradle. Both are test-only: a
    // consumer brings their own.
    testImplementation("dev.zio:zio_3:$zioVersion")
    testImplementation("dev.zio:zio-test_3:$zioVersion")
    testRuntimeOnly("dev.zio:zio-test-junit-engine_3:$zioVersion")
}

tasks.test {
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dkestrel.ziotest.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}

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
    api(project(":proofload-scala"))
    compileOnly("dev.zio:zio_3:$zioVersion")
    compileOnly("dev.zio:zio-test_3:$zioVersion")

    // The specs here need a real zio-test to run against, and its JUnit
    // platform engine to be discovered by Gradle. Both are test-only: a
    // consumer brings their own.
    testImplementation("dev.zio:zio_3:$zioVersion")
    testImplementation("dev.zio:zio-test_3:$zioVersion")
    testRuntimeOnly("dev.zio:zio-test-junit-engine_3:$zioVersion")

    // The outputs are effects here, and the modules that produce them are
    // `compileOnly` for the reason zio-test is: a spec that writes no report
    // should not inherit two modules for the privilege.
    compileOnly(project(":proofload-report-html"))
    compileOnly(project(":proofload-report-github"))
    testImplementation(project(":proofload-report-html"))
    testImplementation(project(":proofload-report-github"))
}

tasks.test {
    // `OutputsAreEffectsTest` reads `OutputsSpec` to check nothing in it wraps
    // a report by hand, which is the claim that entry is for.
    systemProperty("proofload.repoRoot", rootProject.projectDir.path)

    val mainRuntime: FileCollection = configurations.runtimeClasspath.get()
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dproofload.ziotest.runtimeClasspath=" +
                    mainRuntime.joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}

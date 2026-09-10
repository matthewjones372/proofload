plugins {
    // Kotlin is applied to every subproject by the root build; this module has
    // no Kotlin sources of its own, and Scala is applied here.
    scala
    // So `proofload-java` and the modules it exports are on a consumer's compile
    // classpath: every signature here is made of their types.
    `java-library`
}

// Declared in `buildSrc` so this module and `examples-scala` read one number,
// and so the number has somewhere to carry what it means. See `ScalaLts`.
val scalaVersion = ScalaLts.VERSION

// Over the Java facade rather than over core: the unmangling is written once,
// and a facade method missing from Java is missing from Scala in the same
// commit rather than two months later.
dependencies {
    api(project(":proofload-java"))
    api("org.scala-lang:scala3-library_3:$scalaVersion")

    // `compileOnly`, the way `proofload-zio-test` takes zio: a caller reaching
    // `result.markdown` already has the report module on its own classpath, and
    // one that only wanted Scala should not be handed two more jars. The
    // extensions resolve statically, so there is nothing to load until one is
    // called.
    compileOnly(project(":proofload-report-html"))
    compileOnly(project(":proofload-report-github"))

    testImplementation(project(":proofload-report-html"))
    testImplementation(project(":proofload-report-github"))
}

// Tests here are Scala and assert with JUnit rather than with Kotest. The
// matchers the rest of the build uses are Kotlin extension functions, which
// arrive in Scala as static calls on a generated class and stop reading like
// matchers at all.
tasks.test {
    // `FromScalaDocTest` reads the page and the source set it quotes, so both
    // are inputs: editing either re-runs the test rather than being told the
    // task is up to date.
    val page = rootProject.layout.projectDirectory.file("docs/from-scala.md")
    val gate = rootProject.layout.projectDirectory.dir("examples-scala/src")
    inputs.files(page).withPropertyName("theScalaPage")
    inputs.dir(gate).withPropertyName("theSourceSetItQuotes")
    systemProperty("proofload.repoRoot", rootProject.projectDir.path)

    // The floor, handed to the tests that hold the page and the emitted TASTy
    // to it. A literal in a test would be a third place to change.
    systemProperty("proofload.scalaVersion", scalaVersion)
    systemProperty("proofload.tastyMajor", ScalaLts.TASTY_MAJOR.toString())
    systemProperty("proofload.tastyMinor", ScalaLts.TASTY_MINOR.toString())

    // This module's own compiled output, which is the only place the version a
    // consumer actually hits can be read: the declared version catches a
    // deliberate bump, and the emitted TASTy catches everything.
    val emitted = layout.buildDirectory.dir("classes/scala/main")
    inputs.dir(emitted).withPropertyName("theTastyThisModulePublishes")
    systemProperty("proofload.classes", emitted.get().asFile.path)

    val mainRuntime: FileCollection = configurations.runtimeClasspath.get()
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dproofload.scala.runtimeClasspath=" +
                    mainRuntime.joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}

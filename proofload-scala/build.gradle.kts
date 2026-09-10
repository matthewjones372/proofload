plugins {
    // Kotlin is applied to every subproject by the root build; this module has
    // no Kotlin sources of its own, and Scala is applied here.
    scala
    // So `proofload-java` and the modules it exports are on a consumer's compile
    // classpath: every signature here is made of their types.
    `java-library`
}

// A published Scala library can only be read by a compiler at least as new as
// the one that built it: TASTy is forward-compatible, not backward. So this is
// the LTS line rather than the newest release, and moving it forward is a
// breaking change for every consumer on an older compiler.
//
// Dependabot moved this to 3.9.0 once, inside a nineteen-update group, and
// nothing failed: `docs/from-scala.md` still promised the LTS line while the
// published module needed a compiler almost nobody runs. `.github/dependabot.yml`
// now ignores this coordinate, and `ScalaVersionTest` beside this holds the
// build and the page to the same number, because a comment did not.
val scalaVersion = "3.3.8"

// Over the Java facade rather than over core: the unmangling is written once,
// and a facade method missing from Java is missing from Scala in the same
// commit rather than two months later.
dependencies {
    api(project(":proofload-java"))
    api("org.scala-lang:scala3-library_3:$scalaVersion")
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

    // The compiler this module is built by, handed to the test that holds the
    // page to it. A literal in the test would be a third place to change.
    systemProperty("proofload.scalaVersion", scalaVersion)

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

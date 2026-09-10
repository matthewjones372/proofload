// Not published and not a library: the one place every module meets, so the
// claim that they compose is a test rather than a README paragraph. A load
// test here runs against a JDK HttpServer, so it needs no network and no
// container.
// Two tests here measure wall-clock latency against a known target, and a
// machine running eight modules' tests at once is not a machine that can
// answer them: a 40 ms target measured 161 ms during a parallel build. They are
// tagged out of `test` and run on their own with `./gradlew timingTests`, which
// is the same reason AGENTS.md keeps a benchmark of the tool away from tests of
// the tool.
tasks.test {
    useJUnitPlatform { excludeTags("timing") }

    // `ModulesDocTest` reads the module layout and the document describing it,
    // so both are inputs: editing either re-runs the test rather than being
    // told the task is up to date. Declared as a file collection because the
    // document is allowed to be absent — that is a test failure with a message,
    // not a Gradle error about a missing input.
    inputs.files(rootProject.file("settings.gradle.kts"), rootProject.file("docs/modules.md"))
        .withPropertyName("theModuleLayoutAndItsDocumentation")
    systemProperty("proofload.repoRoot", rootProject.projectDir.path)

    // `LlmsTxtTest` reads the root file a model is handed and checks every
    // coordinate, link and line of Kotlin in it against this build, so editing
    // it re-runs the test rather than being told the task is up to date.
    inputs.files(rootProject.file("llms.txt"), rootProject.file("docs/for-agents.md"))
        .withPropertyName("theFilesAModelIsHanded")

    // `smoke/` is a separate Gradle build, so this one has no project object to
    // ask what it depends on. `SmokeProjectTest` reads the two files instead.
    val settings = rootProject.layout.projectDirectory.file("settings.gradle.kts")
    val smokeBuild = rootProject.layout.projectDirectory.file("smoke/build.gradle.kts")
    inputs.files(settings, smokeBuild).withPropertyName("smokeProjectCoverage")
    systemProperty("proofload.settings", settings.asFile.absolutePath)
    systemProperty("proofload.smokeBuild", smokeBuild.asFile.absolutePath)
}

val timingTests = tasks.register<Test>("timingTests") {
    group = "verification"
    description = "Tests that measure elapsed time, run alone so the machine is not the variable."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("timing") }
    maxParallelForks = 1
}

// The one command `.github/workflows/baseline.yml` runs, and the one a reader
// of `docs/cookbook.md` runs on a laptop to see what the job will say. Not
// wired into `check`: it is a load test against a real socket, which is the
// thing AGENTS.md keeps out of the same task graph as everything else.
tasks.register<JavaExec>("againstTheBaseline") {
    group = "verification"
    description = "Runs one simulation, compares it to the baseline beside it, and writes the job summary."
    mainClass.set("io.github.matthewjones372.proofload.examples.AgainstTheBaseline")
    classpath = sourceSets.main.get().runtimeClasspath
    // The baseline path below is relative, and a JavaExec resolves it against
    // its own project directory unless told otherwise. That wrote the run to
    // `examples/build/` while `baseline.yml` cached and uploaded the root's,
    // so the job never restored a baseline and failed uploading nothing.
    workingDir = rootDir
    // A runner somebody has already characterised names its floor instead of
    // spending thirty seconds measuring one; see docs/cookbook.md for what
    // that costs.
    providers.gradleProperty("proofload.resolution").orNull?.let { systemProperty("proofload.resolution", it) }
    // Held as locals so the argument provider closes over two `Provider`s
    // rather than over `providers`, which is the project, which the
    // configuration cache will not store. Still read at execution time, and a
    // `-P` that changes either one still invalidates the entry.
    val baseline = providers.gradleProperty("proofload.baseline").orElse("build/proofload/examples.proofload")
    val over = providers.gradleProperty("proofload.over").orElse("5000")
    argumentProviders.add(
        CommandLineArgumentProvider { listOf(baseline.get(), over.get()) },
    )
}

// Kover instruments every test task in a project it aggregates and `check`
// depends on `koverVerify`, so the tag alone leaves this task in the graph of a
// plain `./gradlew build`, beside the eight modules' tests it cannot be
// measured next to. Dropping it from instrumentation drops it from that graph,
// and the floor is unmoved: it covers lines the ordinary tests already reach.
extensions.configure<kotlinx.kover.gradle.plugin.dsl.KoverProjectExtension> {
    currentProject {
        instrumentation {
            disabledForTestTasks.add(timingTests.name)
        }
    }
}

dependencies {
    // Main rather than test: `OneRun` is a `main`, because the loop that runs a
    // simulation ten times is a shell for-loop around one, and a JVM cannot be
    // invoked ten times from inside a test class.
    implementation(project(":proofload-baseline"))
    implementation(project(":proofload-core"))
    implementation(project(":proofload-engine"))
    implementation(project(":proofload-http"))
    // The emitted Kafka source below is compiled here, which is the only thing
    // that can show `emit` prints Kotlin rather than a plausible-looking string.
    implementation(project(":proofload-kafka"))
    // The emitted drawn source below is compiled here, which is the only thing
    // that shows `emit` prints generators rather than a plausible string.
    implementation(project(":proofload-arbs"))
    // `AgainstTheBaseline` is a main too: the workflow's whole job is one JVM
    // invocation, and a job summary written from a test would be written by
    // whichever test ran last.
    implementation(project(":proofload-report-github"))
    testImplementation(project(":proofload-junit5"))
    // The emitted source below is compiled by `implementation`; this is the test
    // that keeps it identical to what the emitter writes today.
    testImplementation(project(":proofload-plan"))
    testImplementation(project(":proofload-report-html"))
}

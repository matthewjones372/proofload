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
    mainClass.set("io.github.matthewjones372.kestrel.examples.AgainstTheBaseline")
    classpath = sourceSets.main.get().runtimeClasspath
    // A runner somebody has already characterised names its floor instead of
    // spending thirty seconds measuring one; see docs/cookbook.md for what
    // that costs.
    providers.gradleProperty("kestrel.resolution").orNull?.let { systemProperty("kestrel.resolution", it) }
    argumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                providers.gradleProperty("kestrel.baseline").getOrElse("build/kestrel/examples.kestrel"),
                providers.gradleProperty("kestrel.over").getOrElse("5000"),
            )
        },
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
    implementation(project(":kestrel-baseline"))
    implementation(project(":kestrel-core"))
    implementation(project(":kestrel-engine"))
    implementation(project(":kestrel-http"))
    // `AgainstTheBaseline` is a main too: the workflow's whole job is one JVM
    // invocation, and a job summary written from a test would be written by
    // whichever test ran last.
    implementation(project(":kestrel-report-github"))
    testImplementation(project(":kestrel-junit5"))
    testImplementation(project(":kestrel-report-html"))
}

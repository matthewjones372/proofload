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

tasks.register<Test>("timingTests") {
    group = "verification"
    description = "Tests that measure elapsed time, run alone so the machine is not the variable."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("timing") }
    maxParallelForks = 1
}

dependencies {
    testImplementation(project(":kestrel-baseline"))
    testImplementation(project(":kestrel-core"))
    testImplementation(project(":kestrel-engine"))
    testImplementation(project(":kestrel-http"))
    testImplementation(project(":kestrel-junit5"))
    testImplementation(project(":kestrel-report-html"))
    testImplementation(project(":kestrel-report-github"))
}

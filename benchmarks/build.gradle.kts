// Not a library and not a test: a harness that measures what Kestrel itself
// costs. AGENTS.md keeps a benchmark of the tool apart from the tests of the
// tool and out of the coverage denominator, so this module is excluded from
// both, and from `apiCheck` — it publishes nothing.
dependencies {
    implementation(project(":kestrel-core"))
    implementation(project(":kestrel-engine"))
}

tasks.register<JavaExec>("ceiling") {
    group = "verification"
    description = "Finds the rate at which the generator stops keeping its own schedule."
    mainClass.set("io.github.matthewjones372.kestrel.benchmarks.CeilingKt")
    classpath = sourceSets.main.get().runtimeClasspath
    // The harness spawns a virtual thread per user, and the point is to see
    // where that stops keeping up rather than where the heap does.
    jvmArgs("-Xmx2g")
}

// Not a library and not a test: a harness that measures what Kestrel itself
// costs. AGENTS.md keeps a benchmark of the tool apart from the tests of the
// tool and out of the coverage denominator, so this module is excluded from
// both, and from `apiCheck` — it publishes nothing.
dependencies {
    implementation(project(":kestrel-core"))
    implementation(project(":kestrel-engine"))
    // The over-the-socket sweep sends the step a user sends. Measuring the
    // shipped client is the point of it, so the harness takes the module
    // rather than writing a client of its own.
    implementation(project(":kestrel-http"))
    // The Kafka sweep sends the step a user sends, for the same reason: what
    // is being weighed is the shipped adapter rather than one written here.
    implementation(project(":kestrel-kafka"))
}

tasks.register<JavaExec>("ceiling") {
    // A benchmark measures this machine, so it must not queue behind a test
    // run and must not make one queue behind it: a ceiling taken while
    // something else held the machine would be a measurement of the wait.
    systemProperty("kestrel.exclusive", "false")
    group = "verification"
    description = "Finds the rate at which the generator stops keeping its own schedule."
    mainClass.set("io.github.matthewjones372.kestrel.benchmarks.CeilingKt")
    classpath = sourceSets.main.get().runtimeClasspath
    // The harness spawns a virtual thread per user, and the point is to see
    // where that stops keeping up rather than where the heap does.
    jvmArgs("-Xmx2g")
}

tasks.register<JavaExec>("kafkaCeiling") {
    // A benchmark measures this machine, so it must not queue behind a test
    // run and must not make one queue behind it.
    systemProperty("kestrel.exclusive", "false")
    group = "verification"
    description = "Finds the rate at which the Kafka adapter stops keeping its own schedule."
    mainClass.set("io.github.matthewjones372.kestrel.benchmarks.KafkaCeilingKt")
    classpath = sourceSets.main.get().runtimeClasspath
    jvmArgs("-Xmx2g")
}

tasks.register<JavaExec>("timelineCost") {
    // A benchmark measures this machine, so it must not queue behind a test
    // run and must not make one queue behind it: a ceiling taken while
    // something else held the machine would be a measurement of the wait.
    systemProperty("kestrel.exclusive", "false")
    group = "verification"
    description = "Measures what a soak's per-second timeline costs, recording and frozen."
    mainClass.set("io.github.matthewjones372.kestrel.benchmarks.TimelineCostKt")
    classpath = sourceSets.main.get().runtimeClasspath
    // The figure being measured is hundreds of megabytes, and a heap that has
    // to collect to fit it is a heap measuring the collector.
    jvmArgs("-Xmx6g")
}

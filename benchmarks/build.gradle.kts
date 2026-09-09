// Not a library and not a test: a harness that measures what Proofload itself
// costs. AGENTS.md keeps a benchmark of the tool apart from the tests of the
// tool and out of the coverage denominator, so this module is excluded from
// both, and from `apiCheck` — it publishes nothing.
dependencies {
    implementation(project(":proofload-core"))
    implementation(project(":proofload-engine"))
    // The over-the-socket sweep sends the step a user sends. Measuring the
    // shipped client is the point of it, so the harness takes the module
    // rather than writing a client of its own.
    implementation(project(":proofload-http"))
    // The Kafka sweep sends the step a user sends, for the same reason: what
    // is being weighed is the shipped adapter rather than one written here.
    implementation(project(":proofload-kafka"))
}

tasks.register<JavaExec>("ceiling") {
    // A benchmark measures this machine, so it must not queue behind a test
    // run and must not make one queue behind it: a ceiling taken while
    // something else held the machine would be a measurement of the wait.
    systemProperty("proofload.exclusive", "false")
    group = "verification"
    description = "Finds the rate at which the generator stops keeping its own schedule."
    mainClass.set("io.github.matthewjones372.proofload.benchmarks.CeilingKt")
    classpath = sourceSets.main.get().runtimeClasspath
    // The harness spawns a virtual thread per user, and the point is to see
    // where that stops keeping up rather than where the heap does.
    jvmArgs("-Xmx2g")
}

tasks.register<JavaExec>("ceilingApart") {
    // A benchmark measures this machine, so it must not queue behind a test
    // run and must not make one queue behind it.
    systemProperty("proofload.exclusive", "false")
    group = "verification"
    description = "The over-a-socket sweep with the target in a JVM of its own."
    mainClass.set("io.github.matthewjones372.proofload.benchmarks.CeilingApartKt")
    classpath = sourceSets.main.get().runtimeClasspath
    // The generator's heap only. The target gets a JVM of its own and the
    // default heap with it, which is the point of the task.
    jvmArgs("-Xmx2g")
}

tasks.register<JavaExec>("kafkaCeiling") {
    // A benchmark measures this machine, so it must not queue behind a test
    // run and must not make one queue behind it.
    systemProperty("proofload.exclusive", "false")
    group = "verification"
    description = "Finds the rate at which the Kafka adapter stops keeping its own schedule."
    mainClass.set("io.github.matthewjones372.proofload.benchmarks.KafkaCeilingKt")
    classpath = sourceSets.main.get().runtimeClasspath
    jvmArgs("-Xmx2g")
}

tasks.register<JavaExec>("footprint") {
    // A footprint measured while something else held the machine is a
    // measurement of the neighbours, exactly as a ceiling is.
    systemProperty("proofload.exclusive", "false")
    group = "verification"
    description = "Measures what a run retains, and what it allocates per departure."
    mainClass.set("io.github.matthewjones372.proofload.benchmarks.FootprintKt")
    classpath = sourceSets.main.get().runtimeClasspath
    // Deliberately not -Xmx2g. The ceiling harness gives itself room so it can
    // find where the schedule breaks rather than where the heap does; this one
    // is asking what the heap actually holds, so it takes the JVM's default.
}

tasks.register<JavaExec>("timelineCost") {
    // A benchmark measures this machine, so it must not queue behind a test
    // run and must not make one queue behind it: a ceiling taken while
    // something else held the machine would be a measurement of the wait.
    systemProperty("proofload.exclusive", "false")
    group = "verification"
    description = "Measures what a soak's per-second timeline costs, recording and frozen."
    mainClass.set("io.github.matthewjones372.proofload.benchmarks.TimelineCostKt")
    classpath = sourceSets.main.get().runtimeClasspath
    // The figure being measured is hundreds of megabytes, and a heap that has
    // to collect to fit it is a heap measuring the collector.
    jvmArgs("-Xmx6g")
}

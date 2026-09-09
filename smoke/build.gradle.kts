plugins {
    kotlin("jvm") version "2.4.10"
}

// An untagged commit publishes `0.1.0-SNAPSHOT`, so the default matches what
// `publishToMavenLocal` installs today; `-PproofloadVersion=0.1.0` points the
// same test at the released artifacts.
val proofloadVersion: String = providers.gradleProperty("proofloadVersion").getOrElse("0.1.0-SNAPSHOT")

// Java 21 is the floor the published POMs name, so a consumer on 21 is the
// consumer this has to work for.
kotlin { jvmToolchain(21) }

dependencies {
    // Every published module, whether or not the test below imports it. A
    // module with a broken POM, a missing transitive dependency or a name
    // nobody publishes fails here, at resolution, and nothing inside the source
    // tree can fail in its place.
    testImplementation("io.github.matthewjones372:proofload-arbs:$proofloadVersion")
    testImplementation("io.github.matthewjones372:proofload-baseline:$proofloadVersion")
    testImplementation("io.github.matthewjones372:proofload-core:$proofloadVersion")
    testImplementation("io.github.matthewjones372:proofload-engine:$proofloadVersion")
    testImplementation("io.github.matthewjones372:proofload-export:$proofloadVersion")
    testImplementation("io.github.matthewjones372:proofload-cli:$proofloadVersion")
    testImplementation("io.github.matthewjones372:proofload-contract:$proofloadVersion")
    testImplementation("io.github.matthewjones372:proofload-openapi:$proofloadVersion")
    testImplementation("io.github.matthewjones372:proofload-mcp:$proofloadVersion")
    testImplementation("io.github.matthewjones372:proofload-plan:$proofloadVersion")
    testImplementation("io.github.matthewjones372:proofload-plan-kafka:$proofloadVersion")
    testImplementation("io.github.matthewjones372:proofload-grpc:$proofloadVersion")
    testImplementation("io.github.matthewjones372:proofload-grpc-dynamic:$proofloadVersion")
    testImplementation("io.github.matthewjones372:proofload-http:$proofloadVersion")
    testImplementation("io.github.matthewjones372:proofload-java:$proofloadVersion")
    testImplementation("io.github.matthewjones372:proofload-jdbc:$proofloadVersion")
    testImplementation("io.github.matthewjones372:proofload-websocket:$proofloadVersion")
    testImplementation("io.github.matthewjones372:proofload-junit5:$proofloadVersion")
    testImplementation("io.github.matthewjones372:proofload-kafka:$proofloadVersion")
    testImplementation("io.github.matthewjones372:proofload-kotest:$proofloadVersion")
    testImplementation("io.github.matthewjones372:proofload-otel:$proofloadVersion")
    testImplementation("io.github.matthewjones372:proofload-pelican:$proofloadVersion")
    testImplementation("io.github.matthewjones372:proofload-record:$proofloadVersion")
    testImplementation("io.github.matthewjones372:proofload-report-github:$proofloadVersion")
    testImplementation("io.github.matthewjones372:proofload-report-html:$proofloadVersion")
    testImplementation("io.github.matthewjones372:proofload-scala:$proofloadVersion")
    testImplementation("io.github.matthewjones372:proofload-zio-test:$proofloadVersion")

    // What a reader of the README already has in a test project, spelled out
    // because this one starts from nothing.
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.3")
    testImplementation("io.kotest:kotest-assertions-core:6.2.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

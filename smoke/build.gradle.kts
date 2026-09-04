plugins {
    kotlin("jvm") version "2.4.10"
}

// An untagged commit publishes `0.1.0-SNAPSHOT`, so the default matches what
// `publishToMavenLocal` installs today; `-PkestrelVersion=0.1.0` points the
// same test at the released artifacts.
val kestrelVersion: String = providers.gradleProperty("kestrelVersion").getOrElse("0.1.0-SNAPSHOT")

// Java 21 is the floor the published POMs name, so a consumer on 21 is the
// consumer this has to work for.
kotlin { jvmToolchain(21) }

dependencies {
    // Every published module, whether or not the test below imports it. A
    // module with a broken POM, a missing transitive dependency or a name
    // nobody publishes fails here, at resolution, and nothing inside the source
    // tree can fail in its place.
    testImplementation("io.github.matthewjones372:kestrel-baseline:$kestrelVersion")
    testImplementation("io.github.matthewjones372:kestrel-core:$kestrelVersion")
    testImplementation("io.github.matthewjones372:kestrel-engine:$kestrelVersion")
    testImplementation("io.github.matthewjones372:kestrel-export:$kestrelVersion")
    testImplementation("io.github.matthewjones372:kestrel-cli:$kestrelVersion")
    testImplementation("io.github.matthewjones372:kestrel-contract:$kestrelVersion")
    testImplementation("io.github.matthewjones372:kestrel-openapi:$kestrelVersion")
    testImplementation("io.github.matthewjones372:kestrel-plan:$kestrelVersion")
    testImplementation("io.github.matthewjones372:kestrel-grpc:$kestrelVersion")
    testImplementation("io.github.matthewjones372:kestrel-http:$kestrelVersion")
    testImplementation("io.github.matthewjones372:kestrel-jdbc:$kestrelVersion")
    testImplementation("io.github.matthewjones372:kestrel-websocket:$kestrelVersion")
    testImplementation("io.github.matthewjones372:kestrel-junit5:$kestrelVersion")
    testImplementation("io.github.matthewjones372:kestrel-kafka:$kestrelVersion")
    testImplementation("io.github.matthewjones372:kestrel-kotest:$kestrelVersion")
    testImplementation("io.github.matthewjones372:kestrel-otel:$kestrelVersion")
    testImplementation("io.github.matthewjones372:kestrel-pelican:$kestrelVersion")
    testImplementation("io.github.matthewjones372:kestrel-record:$kestrelVersion")
    testImplementation("io.github.matthewjones372:kestrel-report-github:$kestrelVersion")
    testImplementation("io.github.matthewjones372:kestrel-report-html:$kestrelVersion")

    // What a reader of the README already has in a test project, spelled out
    // because this one starts from nothing.
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.3")
    testImplementation("io.kotest:kotest-assertions-core:6.2.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

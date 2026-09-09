// The tools a program reaches Proofload through, over stdio.
//
// A shell over `proofload-cli`: every tool here is a call that command line
// already makes, so what a model can do is what a person at a terminal can do
// and there is no second behaviour to keep in step.
//
// The JSON-RPC framing is written here rather than taken from a library. It is
// a few hundred lines of line-delimited request and response, and the sibling
// repository's own MCP server is next door to read — but depending on it would
// tie this release train to that one for something neither library is about.
// A launcher, because a server nobody can start is a server nobody uses. The
// `application` plugin adds `installDist`, which writes a start script and the
// jars beside it — enough for a client's `command` to point at, with no fat jar
// to keep in step with the modules it shades.
plugins {
    application
}

application {
    mainClass.set("io.github.matthewjones372.proofload.mcp.ServerKt")
    applicationName = "proofload-mcp"
}

// The same class in the published jar's manifest, so a launcher resolving these
// coordinates needs no `--main`. Taken from `application` rather than repeated,
// because two spellings of one entry point is one that goes stale. This makes no
// fat jar and does not make `java -jar` work — a thin jar carries no classpath —
// it only saves a launcher that already resolves the POM from being told.
tasks.jar {
    manifest { attributes("Main-Class" to application.mainClass.get()) }
}

dependencies {
    api(project(":proofload-cli"))
    // The CLI keeps this one `implementation`, so this names it rather than
    // reaching through: `from_openapi` is this module's tool, not a borrowed one.
    implementation(project(":proofload-openapi"))
    // Named for the same reason: smoke and trace run one user, and the
    // engine is this module's dependency rather than one reached through
    // the command line.
    implementation(project(":proofload-engine"))
    // `status` answers a finished run with 0087's summary, which is this
    // module's document rather than one the command line lends it.
    implementation(project(":proofload-export"))
    // A finished run is kept as a file, so a caller polling its own run after a
    // restart is not told the run never existed. `proofload-baseline` is the
    // format this repository already writes a run in, and it is pure Kotlin
    // over core — no third-party jar arrives with it.
    implementation(project(":proofload-baseline"))
    // `report` writes the page a person opens. An agent reads the JSON
    // above; nobody gains from a model reading inlined SVG.
    implementation(project(":proofload-report-html"))
    // `summary` answers the third reader: the person watching the chat, who gets
    // neither the JSON an agent reads nor the page a browser opens. The renderer
    // is the one a job summary already uses, so there is no second one to drift.
    implementation(project(":proofload-report-github"))
    implementation("org.snakeyaml:snakeyaml-engine:3.1.1")
}

tasks.test {
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dproofload.mcp.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}

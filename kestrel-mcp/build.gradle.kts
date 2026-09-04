// The tools a program reaches Kestrel through, over stdio.
//
// A shell over `kestrel-cli`: every tool here is a call that command line
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
    mainClass.set("io.github.matthewjones372.kestrel.mcp.ServerKt")
    applicationName = "kestrel-mcp"
}

dependencies {
    api(project(":kestrel-cli"))
    // The CLI keeps this one `implementation`, so this names it rather than
    // reaching through: `from_openapi` is this module's tool, not a borrowed one.
    implementation(project(":kestrel-openapi"))
    // Named for the same reason: smoke and trace run one user, and the
    // engine is this module's dependency rather than one reached through
    // the command line.
    implementation(project(":kestrel-engine"))
    implementation("org.snakeyaml:snakeyaml-engine:2.10")
}

tasks.test {
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dkestrel.mcp.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}

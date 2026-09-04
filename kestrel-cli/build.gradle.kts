// The command line: a plan in, a verdict out, and an exit code a shell can
// branch on. Not a second product — every command here is a call the library
// already exposes, so what the CLI can do is what a Kotlin caller can do.
dependencies {
    api(project(":kestrel-core"))
    api(project(":kestrel-plan"))
    implementation(project(":kestrel-engine"))
    implementation(project(":kestrel-export"))
}

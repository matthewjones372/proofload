// Build logic that the configuration cache can serialise.
//
// `apiDocCheck` and `apiDocDump` used to call functions declared in the root
// `build.gradle.kts` from inside `doLast`. A lambda that calls a script-level
// function captures the script, the script holds the `Project`, and the
// configuration cache refuses to store one: `cannot serialize Gradle script
// object references`. Compiled here instead, the same code has no project
// behind it and a task action can close over it.
plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
}

// Not published and not a library. `kestrel-scala` has no `.api` dump — what
// BCV records of a Scala module is names no caller can type — so the gate is a
// compiled Scala source set, and a conversion or an extension that goes breaks
// the build here rather than in somebody's project.
//
// A module of its own rather than a source set inside `examples`, for the
// reason `examples-java` is one: those are Kotlin, compiled under conventions
// of their own.
plugins {
    scala
}

dependencies {
    implementation(project(":kestrel-scala"))
}

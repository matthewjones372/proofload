// Not published and not a library. `apiCheck` records the Kotlin surface and
// cannot see whether that surface is *callable* from Java — only a Java
// compiler knows — so the gate is a compiled Java source set, and deleting a
// facade method breaks the build here rather than in somebody's project.
//
// A module of its own rather than a source set inside `examples`: those are
// Kotlin, compiled under conventions of their own, and mixing the two makes
// both build files harder to read than a second small one.
dependencies {
    implementation(project(":proofload-java"))
}

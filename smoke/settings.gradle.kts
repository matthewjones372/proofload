// A build of its own, deliberately: run from this directory it can see none of
// the source tree beside it, so the only Kestrel it can compile against is one
// that came out of a repository. A composite build would substitute the
// projects back in and prove nothing about what was published.
rootProject.name = "kestrel-smoke"

dependencyResolutionManagement {
    repositories {
        // `./gradlew publishToMavenLocal` in the repository above installs the
        // version this resolves, so the release can be tried before it is one.
        // The last entry of spec 0029 runs this with the line deleted, which is
        // the only way to prove Central rather than a laptop.
        mavenLocal()
        mavenCentral()
    }
}

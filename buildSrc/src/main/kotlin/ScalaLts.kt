/**
 * The Scala compiler `proofload-scala` is published against.
 *
 * Not "the version we happen to build with": a published Scala library carries
 * its own TASTy, TASTy is forward-incompatible, and so this number is the
 * *floor* a consumer's compiler must clear. Raising it makes the module
 * unreadable to everyone below, with an error in their build that names TASTy
 * rather than Proofload.
 *
 * Declared here rather than in the root `build.gradle.kts` because two build
 * scripts read it, and a subproject reaching into the root script's properties
 * is the capture that keeps the configuration cache from storing a task.
 *
 * The promise is the LTS *line*, not the patch. Moving along 3.3.x is
 * TASTy-compatible and welcome; leaving it is a release decision.
 */
object ScalaLts {

    /** The newest patch on the Scala 3 LTS line. */
    const val VERSION = "3.3.8"

    /** The TASTy major version every Scala 3 release has emitted so far. */
    const val TASTY_MAJOR = 28

    /**
     * The highest TASTy minor this module is allowed to publish.
     *
     * Scala 3.x emits TASTy 28.x, so 3.3.8 emits 28.3 and the 3.9.0 that
     * shipped in rc3 emitted 28.9, which is what a 3.8 consumer refused.
     *
     * Written out rather than derived from [VERSION], and that is the whole
     * point of it. Derived, the two move together and the check is a
     * tautology: raise the compiler and the ceiling rises to meet it. Stated,
     * raising the compiler alone turns the build red here, and moving both is
     * the deliberate act of dropping every consumer below the new line.
     */
    const val TASTY_MINOR = 3
}

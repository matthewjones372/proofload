import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters

/**
 * How many Dokka generations may run at once, across every module.
 *
 * Dokka 2 runs each module's generator in a process of its own with a 2 GB heap
 * and `-XX:+AlwaysPreTouch`, so every one of them takes its full 2 GB of real
 * memory the moment it starts. With `org.gradle.parallel` that is one per
 * worker, and a worker per core: on the release runner it came to more memory
 * than the machine had, and the kernel killed the Gradle daemon halfway
 * through publishing `v0.1.0-rc5`, with no exception to say why.
 *
 * A shared service with a lease limit caps Dokka alone and leaves compilation
 * and tests as parallel as they were, which `--max-workers` would not.
 */
abstract class DokkaSlots : BuildService<BuildServiceParameters.None> {
    companion object {
        const val NAME = "dokkaSlots"

        /** Two generations: 4 GB beside a 1.5 GB daemon. */
        const val SLOTS = 2
    }
}

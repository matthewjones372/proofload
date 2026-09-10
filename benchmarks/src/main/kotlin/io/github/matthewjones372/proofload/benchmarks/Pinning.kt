package io.github.matthewjones372.proofload.benchmarks

/**
 * Disjoint processors for the generator and the target.
 *
 * The last confound in the apart sweep. Moving the target into a JVM of its own
 * took away its share of this process's heap, its collector and its JIT, and
 * left it competing for the same cores — so a row there is still two ends of a
 * measurement fighting over four processors, and a difference between that
 * table and the in-process one cannot be read as the client's.
 *
 * Best effort, and says so when it is not. A platform with no `taskset`, or one
 * that refuses the call, still produces the table with "not pinned" written
 * where the processors would be. A benchmark that quietly measured something
 * else is worse than one that says it could not.
 */
internal object Pinning {

    /** What each end was given, or null where nothing could be. */
    data class Pinned(val generator: String?, val target: String?) {

        /** What the table says it did, in the words a reader needs. */
        val described: String
            get() = if (generator == null || target == null) {
                "not pinned: generator and target shared all $PROCESSORS processors"
            } else {
                "generator on cpu $generator, target on cpu $target"
            }
    }

    /**
     * Splits the processors in two, pins this process to the first half and
     * hands the second to whatever target is started next.
     *
     * Two or three processors would leave one end with a single core and the
     * measurement would be of that, so anything under four is left alone.
     */
    fun apply(): Pinned {
        if (PROCESSORS < LEAST) return Pinned(null, null)

        val half = PROCESSORS / 2
        val generator = "0-${half - 1}"
        val target = "$half-${PROCESSORS - 1}"

        if (!pinSelfTo(generator)) return Pinned(null, null)

        // Read by `apart` when it builds the target's command line. A property
        // rather than a parameter because every caller of `apart` would
        // otherwise have to carry a processor set it has no opinion about.
        System.setProperty(TARGET_CPUS, target)
        return Pinned(generator, target)
    }

    private fun pinSelfTo(cpus: String): Boolean = runCatching {
        val pid = ProcessHandle.current().pid()
        ProcessBuilder("taskset", "-cp", cpus, "$pid")
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .start()
            .waitFor() == 0
    }.getOrDefault(false)

    /** Where the target's processor set is left for `apart` to read. */
    const val TARGET_CPUS = "proofload.targetCpus"

    private val PROCESSORS = Runtime.getRuntime().availableProcessors()

    /** Below this, half the machine is one core and the row measures that instead. */
    private const val LEAST = 4
}

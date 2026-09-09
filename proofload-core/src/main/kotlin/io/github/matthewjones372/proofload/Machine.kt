package io.github.matthewjones372.proofload

/**
 * What a run was measured on. A value rather than a lookup, so a comparison
 * across two machines is testable on one.
 */
data class Machine(val cores: Int, val jdk: String, val os: String, val arch: String) {

    override fun toString(): String = "$cores cores, JDK $jdk, $os $arch"

    companion object {

        /**
         * The machine this JVM is running on.
         *
         * Captured here rather than in a leaf module because every fact in it
         * comes from the JDK, so reading it adds no dependency to core — and a
         * value only a leaf module could fill would leave `against`, which is
         * core's, unable to see the one thing it has to warn about.
         */
        fun here(): Machine = Machine(
            cores = Runtime.getRuntime().availableProcessors(),
            jdk = property("java.vm.version"),
            os = property("os.name"),
            arch = property("os.arch"),
        )

        private fun property(name: String): String = System.getProperty(name).orEmpty()
    }
}

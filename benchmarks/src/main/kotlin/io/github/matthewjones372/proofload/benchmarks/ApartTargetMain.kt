package io.github.matthewjones372.proofload.benchmarks

import java.nio.file.Files
import java.nio.file.Path

/**
 * The target as its own program: where it is listening on stdout, then answers
 * until its input closes, then its counts into the file it was named.
 *
 * Started by [apart] and by nothing else. The base URL rather than the port,
 * so the two sides share no arithmetic about how one becomes the other.
 */
fun main(args: Array<String>) {
    val counts = Path.of(args.first())
    loopback { target ->
        println(target.baseUrl)
        System.out.flush()
        // Until the parent closes it.
        System.`in`.readBytes()
        Files.writeString(
            counts,
            servedLines(target.served(), target.connections()).joinToString(separator = "\n", postfix = "\n"),
        )
    }
}

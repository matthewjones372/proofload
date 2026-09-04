package io.github.matthewjones372.kestrel.mcp

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * When the thing answering was built.
 *
 * The launcher wraps built jars, so a code change nobody rebuilt leaves a
 * server answering with yesterday's tools and no sign that it is doing so. That
 * cost an hour of "why is the tool I just wrote not there".
 *
 * Read off the jar's own timestamp rather than stamped in at build time: a
 * generated resource holding a clock changes on every build, which invalidates
 * the build cache to tell somebody something the filesystem already knows.
 */
internal fun builtAt(): Instant? = runCatching {
    val at = Server::class.java.protectionDomain?.codeSource?.location?.toURI()?.let(Path::of) ?: return null
    Files.getLastModifiedTime(at).toInstant()
}.getOrNull()

/** For the `initialize` reply, where a client shows it, and for anyone wondering if they rebuilt. */
internal fun describedBuild(): String = builtAt()
    ?.let { "0.1.0, built ${WHEN.format(it)}" }
    ?: "0.1.0"

/** A marker for the class loader to find the jar by; nothing is read off it. */
private object Server

private val WHEN: DateTimeFormatter = DateTimeFormatter
    .ofPattern("yyyy-MM-dd HH:mm")
    .withZone(ZoneId.systemDefault())

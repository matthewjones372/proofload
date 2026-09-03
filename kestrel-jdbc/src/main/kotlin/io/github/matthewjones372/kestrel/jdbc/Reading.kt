package io.github.matthewjones372.kestrel.jdbc

import io.github.matthewjones372.kestrel.StepStats
import io.github.matthewjones372.kestrel.Timing

/**
 * How many rows this step's queries returned, or how many rows its updates
 * changed.
 *
 * The same number core keeps for every module that counts what came back, under
 * the name it has here: a select that ran ten times and returned a million rows
 * is a different finding from one that ran a million times, and `count` is the
 * executions.
 */
val StepStats.rows: Long get() = produced

/**
 * How long this step's users spent waiting for a connection, apart from what
 * the database then took.
 *
 * The number the whole module exists for. A hand-written step times the
 * checkout and the query together and calls the total the database's latency;
 * with them apart, a report can say the database answered in 3 ms and your
 * users waited 400 ms for a connection, which is a different bug with a
 * different fix.
 */
val StepStats.waitedForPool: Timing get() = queued

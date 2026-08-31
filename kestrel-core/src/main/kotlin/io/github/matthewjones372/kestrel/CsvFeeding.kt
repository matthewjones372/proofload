package io.github.matthewjones372.kestrel

/**
 * Fills each of [keys] from the column the header gave the same name, indexed
 * by the user's number.
 *
 * Wraps round at the end, as [feedFrom] does: a feeder that ran out would end
 * a load test for a reason that has nothing to do with the target.
 */
fun CsvFile.feeding(vararg keys: SessionKey<String>): Feeder {
    val filling = keys.map { key -> key to columnFor(key.name) }
    return fedByRow { row -> filling.fold(Session.empty) { session, (key, values) -> session.set(key, values[row]) } }
}

/**
 * Fills [key] from the column of the same name, reading each field with
 * [convert]. A CSV has no types, so the one that matters is named here rather
 * than inferred from data that can change between runs.
 */
fun <T : Any> CsvFile.feeding(key: SessionKey<T>, convert: (String) -> T): Feeder {
    val values = columnFor(key.name)
    return fedByRow { row -> Session.empty.set(key, convert(values[row])) }
}

/**
 * The column [name], or the reason there is not one. Thrown while the feeder
 * is built rather than while a user is fed, so a misspelled column ends the
 * run before it departs anything.
 */
private fun CsvFile.columnFor(name: String): List<String> = requireNotNull(column(name)) {
    "no column named '$name' to fill it from, and this file has $columns"
}

/**
 * A feeder over one session per row, all of them built here so that feeding a
 * user is an index into a list — the path a departure takes allocates nothing
 * and reads no file.
 */
private fun CsvFile.fedByRow(session: (Int) -> Session): Feeder {
    val sessions = List(rows, session)
    return if (sessions.isEmpty()) Feeder.empty
    else Feeder { user -> sessions[(user % sessions.size).toInt()] }
}

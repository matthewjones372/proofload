# 0083 — Database steps

## Problem

The CHANGELOG says it plainly: no database steps. A team load-testing a service
almost always wants to know what the database under it did, and often wants to
load-test the database directly — a connection pool that saturates at 40
connections is the ceiling the whole service hits, and it is invisible from the
HTTP side except as latency nobody can attribute.

What they do today is `exec(name) { }` with their own JDBC call inside, which
works and is what `README.md` recommends. What that costs them is everything
this repository put in a protocol module: a reason with a type (0063) instead of
`Threw(SQLException)`, a pool whose limits are counted rather than guessed, a
step named for the statement rather than for a string somebody typed, and the
knowledge that a connection checkout is not a query.

That last one is the measurement problem. `connection.prepareStatement(...)`
blocks while the pool has nobody free, and a hand-written step times the wait
and the query together and calls the total the database's latency. It is
`behind` all over again, one layer down: the generator's own queueing reported
as the target's speed.

## Not doing

- **No ORM, no query builder, no schema.** A statement is a string the caller
  wrote, as a path is.
- **No driver.** `kestrel-jdbc` carries `java.sql` and nothing else; the caller
  brings PostgreSQL or MySQL, as they bring a gRPC transport (0071).
- **No pool.** A caller hands in a `DataSource`, which is the thing their
  service uses and the thing whose limits are being measured. A pool built here
  would be a different pool from the one in production.
- **No result mapping.** Rows counted, never read into objects. 0060 refused
  the same thing for Kafka payloads, for the same reason: a mapper on the timed
  path is a measurement of the mapper.
- **No transactions across steps.** A connection held across a step is a
  connection held across a think time, which is a pool exhausted by a scenario
  rather than by load.

## Shape

```kotlin
val orders = jdbc.on(dataSource)

val reading = scenario("reading") {
    exec(byId, orders.query("select * from orders where id = ?").binding { it[orderId] })
    exec(insert, orders.update("insert into orders(id, sku) values (?, ?)").binding { ... })
}

result[byId].rows          // rows the query returned, counted
result[byId].waitedForPool // how long the checkout took, beside the query
```

- `query` reads and counts rows; `update` reports the update count. Both are
  one sample per execution, timed from the statement rather than from the
  connection checkout.
- **The pool wait is its own number.** A connection this user waited for is
  time it spent queueing for the generator's own resource, and putting it in
  the query's latency is coordinated omission with a different name. It is
  recorded the way `behind` is: beside the measurement, not inside it.
- Reasons with types: `SqlState(code)` rather than `Threw(SQLException)`, so a
  deadlock, a unique violation and a timeout are three rows in a report rather
  than one.
- `binding { }` reads the session, so a query is per user for the reason 0079
  gave: ten thousand identical selects measure a query cache.

## Why this shape

**A `DataSource`, handed in.** The pool is the thing under test as often as the
database is, and a pool this module built would have different limits from the
one the service runs. Handed in, a run can point at the caller's own HikariCP
with its own settings and measure what production will do.

**The checkout is not the query, and the split is the whole point.** A
hand-written step cannot separate them without writing this module. With them
separated, a report can say *the database answered in 3 ms and your users
waited 400 ms for a connection*, which is a different bug with a different fix,
and the one a team is more often actually hitting.

**Rows counted, not read.** `ResultSet.next()` in a loop with nothing in the
body is the honest measurement of "the database sent this much"; anything more
measures the JDBC driver's object mapping, which is not what anybody is asking
about. A caller who needs a value uses `exec` and their own client, exactly as
they do today.

**Blocking, on the user's own virtual thread.** JDBC is blocking and JDK 21
pins a carrier on a synchronized block, which several drivers still use. This
is a real hazard and the honest answer is to say so rather than to claim
otherwise: the module documents which drivers unmount cleanly, and the
injector's own limits (0065) already report a starved carrier pool.

## Stack

- [ ] **`spec-0083-module`** — `kestrel-jdbc`, `jdbc.on(dataSource)`, `query`
      and `update` as steps, against an in-memory database on the test
      classpath only.
      Done when: a select is one row named for the statement, an update reports
      its count, the runtime classpath is core and `java.sql`, and no driver is
      on it.
- [ ] **`spec-0083-pool`** — the connection checkout timed and reported beside
      the query rather than inside it.
      Done when: a pool of one under ten concurrent users reports a query
      latency that does not grow with the contention, and a pool wait that
      does.
- [ ] **`spec-0083-reasons`** — `SqlState`, so a deadlock is not a stack trace.
      Done when: a unique violation, a timeout and a syntax error are three
      reasons in a report, each naming its SQLSTATE.
- [ ] **`spec-0083-docs`** — the cookbook page, the `docs/modules.md` row, and
      the carrier-pinning note.
      Done when: the page says which part of the number is the pool's.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

A run against a pool smaller than its user count reports the queueing as
queueing.

## Open questions

1. **Which in-memory database do the tests use?** H2 and HSQLDB are both test
    classpath only. Recommend H2 for its Postgres compatibility mode, so the
    statements in the tests look like statements somebody would write.
2. **Should a batch be one sample or many?** `addBatch`/`executeBatch` is one
    round trip carrying N statements. Recommend one sample, named as a batch —
    0075's rule read the other way: what left once is one departure.
3. **Does the pool wait belong in `behind`?** It is the same kind of number —
    the generator's own queueing — but `behind` means lateness against a
    schedule and a closed run has none. Recommend its own field, and a page
    note beside the existing one.
4. **Can a step hold a transaction?** Refused above, and somebody will want it
    for a realistic write path. Recommend leaving it refused until a spec can
    say what happens to a connection held across a `pause`.

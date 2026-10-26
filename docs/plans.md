# Plans, OpenAPI and recordings

A scenario does not have to start as Kotlin. This page covers the other
starting points:

- a **plan**: a YAML or JSON file describing a scenario, its load and its goals,
  which runs from the command line or from Kotlin;
- an **OpenAPI document** or **Pelican endpoints**, from which Proofload writes a
  first-draft plan;
- a **HAR recording**, from which Proofload writes Kotlin source.

All of them produce the same `Simulation` values the Kotlin DSL builds.

## A plan

`proofload-plan` reads `plan/1`:

```yaml
proofload:  plan/1
baseUrl:  https://orders.internal
scenario: checkout
steps:
  - name: browse
    get:  /products
  - name: place order
    post: /orders
    body: '{"cart":"1 anvil"}'
    expecting: 201
load:
  rate: 50/s
  over: 1m
goals:
  - step: place order
    p99:  200ms
```

| Key | What it holds |
|---|---|
| `proofload` | the format version, `plan/1` |
| `baseUrl` | the base URL for request steps |
| `brokers` | the Kafka bootstrap servers for produce and completion steps |
| `scenario` | the scenario's name |
| `steps` | the journey, in order |
| `load` | `rate` and `over`; or `from`, `to` and `over` for a ramp; or `stages`, a list of either |
| `goals` | each a `step` and one of `p50`, `p95`, `p99` or `p999`; or `failureRate` (such as `"1%"`), with `step` optional |
| `draw` | per-user generated values, filled into `{name}` in paths and bodies ([data.md](data.md#the-same-thing-in-a-plan)) |
| `seed` | the seed for `draw` |

A request step has `name`, one of `get`, `post`, `put`, `patch`, `delete` or
`head` with the path, and optionally `headers`, `body`, `expecting` (default
200), `declared` (statuses the target documents, which still fail but under
their own reason) and `pauseAfter`.

A key the format does not know is refused, and every error names its line.
A plan has no `keptSchedule` goal; the CLI reports the schedule through its
exit code instead ([below](#the-command-line)).

### Kafka steps

A step that carries `produce` is a Kafka record, and one that carries
`completes` is the answer to an earlier produce step, read off another topic:

```yaml
proofload:  plan/1
brokers:  localhost:9092
scenario: orders
steps:
  - name: place order
    produce: orders
    key: anvil-1
    body: '{"cart":"1 anvil"}'
    settings:
      acks: all
  - name: confirmed
    completes: place order
    on: order-confirmations
    by: correlation-id
    group: proofload-bench
    within: 30s
load:
  rate: 500/s
  over: 1m
goals:
  - step: confirmed
    p99: 2s
```

`within` is required. The correlation id is the user's number. Kafka bodies in a
plan are fixed: `draw` and `{name}` templating apply to request steps only.
Reading Kafka steps needs `proofload-plan-kafka` beside `proofload-plan`.
[protocols.md](protocols.md#kafka) says what each step times.

A plan cannot name a gRPC, WebSocket, server-sent event or JDBC step.

### From Kotlin

```kotlin
import io.github.matthewjones372.proofload.plan.asSimulation
import io.github.matthewjones372.proofload.plan.readPlan

val simulation = readPlan(Path.of("checkout.yaml")).asSimulation()
val result = proofload.run(simulation)
```

Pass `kafkaLowerings` from `proofload-plan-kafka` to `asSimulation` for a plan
with Kafka steps.

### When a plan outgrows the format

`emit` prints a plan as the Kotlin it is equivalent to, seeds included, to edit
and commit from then on:

```bash
proofload emit checkout.yaml --package com.acme.load
```

## The command line

`proofload-cli` runs plans without a Kotlin build:

```
proofload validate <plan>          parse it, resolve it, send nothing
proofload preview  <plan>          say what it would send, and send none of it
proofload run      <plan> [--json] run it; the exit code is the verdict
proofload emit     <plan> [--package p]  print it as the Kotlin it was equivalent to
proofload from-openapi <doc> [--base-url u]  read a document and write the plan it describes
```

`proofload` here stands for however you launch the CLI. The published jar names
its main class, so a launcher that resolves Maven coordinates can start it, for
example [JBang](https://www.jbang.dev):

```bash
jbang io.github.matthewjones372:proofload-cli:0.1.0-rc5 preview checkout.yaml
jbang io.github.matthewjones372:proofload-cli:0.1.0-rc5 run checkout.yaml
```

`java -jar` does not work, because the jar carries no classpath.

Only `run` sends load. `preview` and `run` are both held to a `proofload.toml`
in the working directory if there is one ([allowance.md](allowance.md)).
`run --json` prints the [run document](exporting.md#the-run-itself-as-a-document)
instead of a summary.

| Exit code | Meaning |
|---|---|
| 0 | every goal met, or there were no goals |
| 1 | a goal missed |
| 2 | the generator fell behind or lost ground; this outranks a missed goal |
| 3 | refused by the allowance |
| 4 | unusable: bad arguments or an unreadable plan |

The CLI judges goals over the whole run, not over the steady segment. A
malformed `proofload.toml`, or a plan or document file that does not exist,
currently ends the process with an uncaught exception and exit code 1, which a
script would read as a missed goal.

The same operations are available to an MCP client through `proofload-mcp`
([mcp.md](mcp.md)).

## From an OpenAPI document

`proofload-openapi` reads an OpenAPI document (YAML or JSON) and writes a plan:

```bash
proofload from-openapi orders.yaml --base-url https://orders.internal
```

What it writes is a starting point to edit:

- one step per `GET` operation;
- `expecting` is the first 2xx status the operation documents, and its 4xx and
  5xx statuses are `declared`;
- a path parameter with an `enum` becomes a `oneOf` draw over every value, and
  one with a numeric `minimum` and `maximum` becomes a `uniform` draw over that
  range. Any other parameter gets one legal value, because a range the contract
  does not state would be invented;
- a load of 1 a second for 10 seconds, and a placeholder p99 goal of one second
  per step.

`--base-url` is needed only when the document names no server.

## From Pelican endpoints

`proofload-contract` writes the same kind of first draft from
[Pelican](https://github.com/matthewjones372/pelican) endpoint values, with
`planFrom(endpoints, baseUrl)`: one step per `GET` endpoint in the order they
were declared, path parameters filled with one legal value, a smoke-sized load
and a placeholder p99 goal per step. It is build-time tooling.

## From a HAR recording

If you have the flow in a browser or a proxy, export a HAR file and read it into
Kotlin source with `proofload-record`. Its main class is
`io.github.matthewjones372.proofload.record.MainKt`; the published jar does not
name it, so a launcher has to be told it. From a clone of this repository:

```bash
./gradlew :proofload-record:run --args="checkout.har --package com.acme.load --out src/test/kotlin"
```

```
proofload: 2 steps from 3 recorded requests, at src/test/kotlin/com/acme/load/Checkout.kt
proofload: captured orderId
proofload: 1 credential header(s) dropped, each left as a TODO
```

The output is Kotlin source to edit and commit, not a scenario read at run time.
A recording is a first draft.

What it does with the recording:

- **One `exec` per request**, named `METHOD /path`, in the order they were made.
- **A value one answer produced and a later request used becomes a `capture`
  and a `{name}`**, so the generated scenario does not hard-code an id from the
  recording. The nearest earlier response wins when a value appears twice. A
  value too short to be sure of gets a comment instead of a capture.
- **Runs of one path differing by a segment become one step**, with a comment
  saying how many it stood for.
- **Static assets are excluded.** `--include` and `--exclude` take a regex over
  the path, and `--scenario` names the scenario.
- **Credentials are removed**, with no flag to keep them:
  - credential headers (`authorization`, `cookie`, `set-cookie`, `x-api-key`,
    and any header whose value parses as a JWT) are replaced by a `TODO(...)`
    naming the header, which throws when the scenario is built, so it cannot
    run until you deal with it;
  - credential query parameters are dropped, with a comment naming them;
  - secret-looking fields and JWTs in request bodies become `REDACTED`.

  Put a real credential back, or fetch one outside the measured path with
  [`refreshing`](data.md#a-token-that-expires-mid-run).
- **Think time is a comment.** The gaps between recorded requests are printed
  and commented out; the load profile is yours to state.

It does not record traffic itself. Browsers, proxies such as Charles and
mitmproxy, and most API clients export HAR.

[`docs/examples/checkout.har`](examples/checkout.har) is a sample recording,
and `proofload-record/src/test/.../generated/Checkout.kt` is what it generates.
The build compiles that output.

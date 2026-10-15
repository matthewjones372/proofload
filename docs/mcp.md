# Proofload over MCP

`proofload-mcp` is a stdio MCP server. Its tools are the operations
`proofload-cli` performs on [plans](plans.md), plus tools for polling a run and
reading its results, so a model can write a plan, check it, send a single
request per step, and then run it within the limits of the machine's
[allowance](allowance.md).

## Starting it

Every module is on Maven Central, so a launcher that resolves coordinates starts
the server without a clone or a build:

```bash
jbang io.github.matthewjones372:proofload-mcp:0.1.0-rc4
```

The published jar names its main class, so the coordinate is enough.
[Coursier](https://get-coursier.io) works too: with `0.1.0-rc4`, run
`cs launch io.github.matthewjones372:proofload-mcp:0.1.0-rc4 -M io.github.matthewjones372.proofload.mcp.ServerKt`
([versions.md](versions.md) has the details).

jbang writes its own progress to stderr, so stdout carries nothing but JSON-RPC
and a client parses it as-is.

`java -jar` does not work: the jar carries no classpath, so whatever starts it
has to resolve the POM.

Two more routes need no launcher. The release download needs no account. The
container image's GHCR package is private, so `docker` needs
`docker login ghcr.io`.

A download, for anyone who wants no launcher. It carries `bin/proofload-mcp` and
`bin/proofload-mcp.bat`, so it is also the Windows answer:

```bash
curl -LO https://github.com/matthewjones372/proofload/releases/download/v0.1.0-rc4/proofload-mcp-0.1.0-rc4.zip
unzip proofload-mcp-0.1.0-rc4.zip
claude mcp add proofload -- "$PWD/proofload-mcp-0.1.0-rc4/bin/proofload-mcp"
```

A container, for anyone who wants no JDK either:

```bash
claude mcp add proofload -- docker run -i --rm -v "$PWD:/work:ro" ghcr.io/matthewjones372/proofload-mcp:0.1.0-rc4
```

`-i` and no `-t`: the protocol is stdin and stdout, and a TTY would corrupt it.

Nothing else is needed to start. `benchmark`, `plan_schema`, `validate`, `preview`,
`smoke` and `trace` all work as they are, because the plan's shape bounds them
rather than its rate.

**`run` needs an allowance in the container.** Elsewhere an absent
`proofload.toml` means no limits. The image sets `PROOFLOAD_REQUIRE_ALLOWANCE`,
so `run` refuses until it finds a `proofload.toml` in the directory it was
started from. Write one there and the mount above picks it up. A local install
does not require one.

**Mount the directory, not the file.** With `-v "$PWD/proofload.toml:/work/proofload.toml:ro"`,
if the file does not exist yet Docker creates a *directory* of that name in your
working directory, which cannot be read as an allowance and has to be deleted by
hand. Mounting the directory avoids that, and makes plans on disk readable too.

`report` writes into the container, where its path is no use to you. `summary`
answers with the run itself and needs no filesystem, which is the one to reach for
here; mount a directory if you want the page as well.

Working on Proofload itself, build it:

```bash
./gradlew :proofload-mcp:installDist
```

That writes `proofload-mcp/build/install/proofload-mcp/bin/proofload-mcp`, a start
script with the jars beside it.

Either way it speaks line-delimited JSON-RPC on stdin and stdout, so it is
testable with a pipe before any client is involved:

```bash
printf '%s\n' '{"jsonrpc":"2.0","id":1,"method":"tools/list"}' \
  | proofload-mcp/build/install/proofload-mcp/bin/proofload-mcp
```

## Connecting a client

Point the client's `command` at that script. For Claude Code:

```bash
claude mcp add proofload -- /absolute/path/to/proofload/proofload-mcp/build/install/proofload-mcp/bin/proofload-mcp
```

For a client configured by file, the same thing as an entry:

```json
{
  "mcpServers": {
    "proofload": {
      "command": "/absolute/path/to/proofload/proofload-mcp/build/install/proofload-mcp/bin/proofload-mcp"
    }
  }
}
```

The path has to be absolute: a client starts the server from its own working
directory, not yours.

## The tools

| Tool | Does | Sends |
|---|---|---|
| `benchmark` | **start here**: a target in, a plan out, previewed and smoked | one request per step |
| `plan_schema` | the shape of a `plan/1` document, with four worked plans | nothing |
| `validate` | parses a plan, resolves its steps and goals, names the line of anything wrong | nothing |
| `preview` | what the plan would send: users, requests, window, peak rate, hosts | nothing |
| `from_openapi` | reads an OpenAPI document, writes the plan it describes | nothing |
| `smoke` | one request per step, so a typo is found here rather than at three thousand a second | one request per step |
| `trace` | walks one user and says what each step sent and what came back | one journey |
| `run` | starts the run, returns a `runId`, does not wait | **the load the plan asks for** |
| `status` | what a run is doing, or the verdict and remedy of a finished one | nothing |
| `explain` | the full document for a finished run: every step, the timeline | nothing |
| `report` | writes the run's self-contained HTML page, returns its path | nothing |
| `summary` | what the run measured, as markdown a person reads in the chat | nothing |
| `write_spec` | writes the reasoning, the measured baseline and the plan as one markdown file to commit | nothing |
| `list_runs` | every run this server started, newest first | nothing |
| `compare` | one finished run against another: better, worse, or cannot tell | nothing |

**Start with `benchmark`.** `benchmark` takes a target (an OpenAPI document, a plan you already
have, or just a base URL) and does the whole safe half in one call: writes the
plan, validates it, previews it against the allowance, and sends one request per
step.

```
benchmark {"baseUrl": "http://localhost:8731"}
```

```
proofload:  plan/1
baseUrl:  http://localhost:8731
scenario: smoke
steps:
  - name: root
    get: /
load:
  rate: 1/s
  over: 10s

Running this would send 10 requests over 10s
peaking at 1.0/s
to localhost.

One request per step, already sent:
1 requests, 1 ok, 0 failed
  root: 1 sent

Nothing above sent load. Call `run` with this plan to do that.
```

It also says what it is guessing, and asks:

```
This plan is guessing. Ask whoever wants the benchmark:
  - This only sends `GET /`, because a base URL is all it was given. Which paths
    actually matter: a journey, a hot endpoint, a slow one?
  - The rate is a placeholder, one a second, because nothing said otherwise.
    What does this see at peak, and over how long?
```

Every question is derived from that plan and that smoke test. A credential is
asked about only if the target answered 401; paths are asked about because a
bare URL was all it got. A plan with a rate and goals somebody chose is asked
nothing.

Edit the plan with the answers, set the rate, and pass it to `run`. The tool
that prepares a plan never sends its load.

**Ask `plan_schema`** when you want to write a plan by hand. It returns the shape
the parser enforces, with worked examples.

**A request plan can draw its own values.** A `draw` block names a generator per
key, and `{key}` in a path or a body is filled per user from it: `uniform`,
`zipf`, `oneOf`, `digits` and `uuids`, with `zipf` the shape real traffic has: a
few keys asked for constantly and a long tail asked for once. One id repeated
measures one row and one cache line, and cardinality and skew are what move a
p99, so `from_openapi` derives the draws from the contract's own schemas rather
than substituting one legal id: `minimum: 1, maximum: 500` becomes
`{uniform: {from: 1, to: 500}}`, and an `enum` becomes every value it lists
rather than the first. A parameter the contract does not bound is substituted
instead, because inventing a range it never stated would be inventing a
cardinality.

```yaml
draw:
  sku:    {zipf: {keys: 1000000, skew: 1.1}}
  region: {oneOf: [emea, apac, amer]}
steps:
  - name: open product
    get: '/regions/{region}/products/{sku}'
    declared: [404]
```

`plan_schema` carries the whole vocabulary and a worked plan for it.

Every tool's description states what it sends. `plan_schema`, `validate`,
`preview` and `from_openapi` send nothing, so a caller can iterate against the
parser.

`smoke` and `trace` are bounded by the plan's shape rather than its rate: a plan
asking for five thousand a second for ten minutes still sends one request per
step. When a run is full of 400s, `trace` shows what was actually sent.

## A plan whose steps are topics

A step names which protocol it is by which key it carries, so a plan is not
tied to HTTP:

```yaml
proofload:  plan/1
brokers:  localhost:9092
scenario: orders
steps:
  - name: place order
    produce: orders
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

`produce` is the publish, timed as deep as `acks` makes it. `completes` is the
answer arriving on another topic, recorded as the round trip on a row of its
own. `within` is required. [plans.md](plans.md#kafka-steps) has the format.

`baseUrl` and `brokers` are both optional, and a plan may mix requests and
topics: a journey that is a request and then a record is one journey.

The correlation is the user's number, which is unique per departure and is the
only value a *record* has without a lambda: `draw` and `{key}` templating are a
request-plan feature, so every record from one step carries the same key and the
same body. Where per-user variety matters there, `emit` prints the Kotlin and a
feeder goes there.

A broker is a host. `preview` names every entry of the bootstrap list, and an
allowance that does not permit the cluster refuses the plan the way it refuses
a URL, because shared infrastructure is exactly what a fence is for.

The Kafka client comes from `proofload-plan-kafka`, which the CLI and the MCP
server include; `proofload-plan` alone carries no Kafka client.

## What it will refuse

A plan that does not parse comes back as a tool result with `isError` set and
the parser's message in it: the line, and the keys that were allowed.

`preview` also consults the machine's `proofload.toml`, so a plan over the rate,
window, request count or host list this machine permits is refused before
anything is sent. See [allowance.md](allowance.md), and read the paragraph there
about a fence not being a sandbox.

A method the server does not serve is a JSON-RPC *error*, not a tool result, so
a client can tell a call it cannot make from a tool that ran and refused.

## Running one

`run` returns as soon as the run is underway, with an id and what it is about to
send:

```json
{"runId":"r-1","sending":"3000 requests over 1m to orders.internal"}
```

Poll `status` with that id. `run` does not block for the length of the run,
because a long tool call is likely to time out and be retried.

One run at a time: a second `run` while one is sending is refused with
`r-1 is still sending`.

Runs live in the server's memory and are lost when it stops. Use `report` or
`write_spec` to keep anything on disk.

## Three readers, three artefacts

`explain` returns JSON for a program, `report` returns the path of the HTML page
for a person with a browser, and `summary` returns markdown for a person reading
the chat. `status` returns the [run document](exporting.md#the-run-itself-as-a-document),
with the verdict and the remedy.

`summary` is the markdown a GitHub job summary carries, from the same renderer,
followed by a text histogram for the steps worth looking at:

```
checkout  p50 10.0ms  p99 2.00s
 10ms  ############  900
100ms
   1s  #  100
```

Nine in ten answered in 10 ms and the rest took two seconds. A p99 of 2 s alone
would suggest a uniformly slow step; the shape shows a fast path and a slow one.
Bars are the counted buckets grouped by decade, with the count beside each, and
an empty decade is left blank.

`compare` answers better, worse, or cannot tell, so noise is not reported as a
regression.

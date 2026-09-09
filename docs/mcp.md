# Proofload over MCP

A stdio server whose every tool is a call `proofload-cli` already makes. What a
program can do here is what a person at a terminal can do, and there is no
second behaviour to keep in step.

## Starting it

Every module is on Maven Central, so a launcher that resolves coordinates starts
the server without a clone or a build:

```bash
jbang --main io.github.matthewjones372.proofload.mcp.ServerKt io.github.matthewjones372:proofload-mcp:0.1.0-rc1
```

Pass `--main` until the next release. `0.1.0-rc1` was published before the jar
carried a `Main-Class`, and omitting it does not fail cleanly: jbang asks which
class to run in a pop-up dialog, which a client waiting on stdout reads as a
hang. From the release after `0.1.0-rc1` the coordinate on its own is enough.

[Coursier](https://get-coursier.io) is the same shape:
`cs launch io.github.matthewjones372:proofload-mcp:0.1.0-rc1`.

jbang writes its own progress to stderr, so stdout carries nothing but JSON-RPC
and a client parses it as-is.

`java -jar` is not one of the ways. There is no fat jar — nothing has to be kept
in step with the modules it would have shaded — so the jar carries no classpath
and whatever starts it has to resolve the POM. That is the whole reason a
launcher is named here rather than a download.

Two more routes arrive with the release after `0.1.0-rc1`, which is the first one
the workflow builds them in — neither exists for `0.1.0-rc1` itself.

A download, for anyone who wants no launcher. It carries `bin/proofload-mcp` and
`bin/proofload-mcp.bat`, so it is also the Windows answer:

```bash
curl -LO https://github.com/matthewjones372/proofload/releases/download/vVERSION/proofload-mcp-VERSION.zip
unzip proofload-mcp-VERSION.zip
claude mcp add proofload -- "$PWD/proofload-mcp-VERSION/bin/proofload-mcp"
```

A container, for anyone who wants no JDK either:

```bash
claude mcp add proofload -- docker run -i --rm \
  -v "$PWD/proofload.toml:/work/proofload.toml:ro" \
  ghcr.io/matthewjones372/proofload-mcp:VERSION
```

`-i` and no `-t`: the protocol is stdin and stdout, and a TTY would corrupt it.

**The mount is not optional in the image.** `docs/allowance.md` says an absent
allowance means no limits, which is the right default for someone who installed a
load generator themselves and the wrong one for an image a model drives — so the
image sets `PROOFLOAD_REQUIRE_ALLOWANCE` and `run` refuses without a fence.
`preview`, `smoke` and `trace` need no mount, because the plan's shape bounds
them rather than its rate. A local install is unaffected and behaves as it always
has.

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
| `benchmark` | **start here** — a target in, a plan out, previewed and smoked | one request per step |
| `plan_schema` | the shape of a `plan/1` document, with four worked plans | nothing |
| `validate` | parses a plan, resolves its steps and goals, names the line of anything wrong | nothing |
| `preview` | what the plan would send — users, requests, window, peak rate, hosts | nothing |
| `from_openapi` | reads an OpenAPI document, writes the plan it describes | nothing |
| `smoke` | one request per step, so a typo is found here rather than at three thousand a second | one request per step |
| `trace` | walks one user and says what each step sent and what came back | one journey |
| `run` | starts the run, returns a `runId`, does not wait | **the load the plan asks for** |
| `status` | what a run is doing, or the verdict and remedy of a finished one | nothing |
| `explain` | the full document for a finished run — every step, the timeline | nothing |
| `report` | writes the run's self-contained HTML page, returns its path | nothing |
| `summary` | what the run measured, as markdown a person reads in the chat | nothing |
| `list_runs` | every run this server started, newest first | nothing |
| `compare` | one finished run against another: better, worse, or cannot tell | nothing |

**Start with `benchmark`.** Everything else in this table is a verb on
Proofload's own model, which is the shape a library has rather than the shape a
question has. Nobody asks to validate a plan; they ask whether their service
holds up. `benchmark` takes a target — an OpenAPI document, a plan you already
have, or just a base URL — and does the whole safe half in one call: writes the
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
    actually matter — a journey, a hot endpoint, a slow one?
  - The rate is a placeholder — one a second, because nothing said otherwise.
    What does this see at peak, and over how long?
```

Every question is derived from that plan and that smoke, not read off a list. A
credential is asked about because the target answered 401, not because targets
often need one; paths are asked about because a bare URL was all it got. A plan
somebody wrote themselves, with a rate and a goal they chose, is asked nothing —
a fixed list would query a decision that has already been made, and get ignored
on the plan where it mattered.

Edit the plan with the answers, raise the rate on purpose, and pass it to `run`. The load is
never sent by the tool that prepared it, so the gate the allowance exists to
create is the only way through rather than a step you have to remember.

**Ask `plan_schema`** when you want to write a plan by hand. It is the tool the others depend on: a caller who
can ask for the format writes a valid plan on the first attempt rather than a
plausible one, and what comes back is the shape the parser enforces rather than
documentation about it.

**A request plan can draw its own values.** A `draw` block names a generator per
key, and `{key}` in a path or a body is filled per user from it: `uniform`,
`zipf`, `oneOf`, `digits` and `uuids`, with `zipf` the shape real traffic has — a
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

Every tool's description states what it sends before you have to find out. The
first four send nothing, so they are free to call and free to get wrong — which
is the point, because it lets a caller iterate against a parser instead of
guessing.

`smoke` and `trace` are the debug loop, and both are bounded by the plan's shape
rather than its rate: a plan asking for five thousand a second for ten minutes
still sends one request per step. A plan answering 400s produces a run full of
them and the run says only that they were 400s; `trace` says what was actually
sent. Finding a typo in a path by firing three thousand requests is the other
half of the same mistake.

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
answer arriving somewhere else, and what it records is the round trip — a row
of its own rather than folded into the publish, because folding them reports a
round trip as though it were a write. `within` has no default: a run that waits
forever for an answer that never comes reports no failure and no number.

`baseUrl` and `brokers` are both optional, and a plan may mix requests and
topics — a journey that is a request and then a record is one journey.

The correlation is the user's number, which is unique per departure and is the
only value a *record* has without a lambda: `draw` and `{key}` templating are a
request-plan feature, so every record from one step carries the same key and the
same body. Where per-user variety matters there, `emit` prints the Kotlin and a
feeder goes there.

A broker is a host. `preview` names every entry of the bootstrap list, and an
allowance that does not permit the cluster refuses the plan the way it refuses
a URL — shared infrastructure is exactly what a fence is for.

The client arrives with the module that reads plans rather than with
`proofload-plan` itself: a project taking `proofload-plan` to read a plan of
requests gets no Kafka on its classpath. [modules.md](modules.md) has the row.

## What it will refuse

A plan that does not read comes back as a tool result with `isError` set and the
parser's own sentence in it — the line, and the keys that were allowed. That is
what a caller correcting itself needs; a stack trace buries it.

`preview` also consults the machine's `proofload.toml`, so a plan over the rate,
window, request count or host list this machine permits is refused before
anything is sent. See [allowance.md](allowance.md), and read the paragraph there
about a fence not being a sandbox.

A method the server does not serve is a JSON-RPC *error*, not a tool result. A
client has to be able to tell a call it cannot make from a tool that ran and
said no; confusing the two makes it retry something that will never work.

## Running one

`run` returns as soon as the run is underway, with an id and what it is about to
send:

```json
{"runId":"r-1","sending":"3000 requests over 1m to orders.internal"}
```

Poll `status` with that id. A ten-minute run inside one tool call is a dead
connection, a retry, and a second ten-minute run against the same target, which
is why the two are separate.

One run at a time. A second `run` while one is sending is refused by name —
`r-1 is still sending` — rather than handed an id for a run that never started
and could be polled forever.

Runs live in the server's memory and are lost when it stops. That is the honest
scope for a server a client starts and stops; anything meant to survive goes to
disk, which is what `report` will be for.

## Three readers, three artefacts

`explain` returns JSON, `report` returns a path, and `summary` returns markdown.
That split is the point: an agent reads the document, a person with a browser
opens the page, and a person watching the chat reads the summary. Handing a model
the page's bytes would be handing it inlined SVG to no purpose, and handing a
person the JSON would be handing them the thing the charts were made from —
which is what watching `status` does, and the reason `summary` exists.

`summary` is the markdown a GitHub job summary already carries, from the same
renderer. A chat-only one would be a second behaviour to keep in step, and the
one thing that reader loses against the page is the distribution — so the
markdown draws it, for the steps worth looking at:

```
checkout  p50 10.0ms  p99 2.00s
 10ms  ############  900
100ms
   1s  #  100
```

Nine in ten answered in 10 ms and the rest took two seconds. The p99 alone is
that of a step which is merely slow; the shape is what says otherwise. Bars are
the counted buckets collapsed to decades, the count printed beside each so the
bar never has to be trusted, and a decade that counted nothing left blank rather
than smoothed.

`compare` answers in three, not two. "Cannot tell" is the one that matters: a
comparison that only ever says better or worse will say one of them about noise,
and a caller acting on that chases a regression nobody introduced.

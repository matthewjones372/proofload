# 0092 — Proofload over MCP

## Problem

0089 makes a plan a file and 0087 makes a result a document, which is most of
what a program needs. What is missing is the door. A caller driving Proofload
today shells out, guesses the flags, and reads whatever lands on stdout — and a
caller that is a model has the further problem that it does not know the plan
format, so its first attempt is a syntactically valid file describing a tool
that does not exist.

Four things have to be true for that loop to close: the format has to be
askable rather than remembered, a plan has to be checkable without sending
anything, a plan that is answering 400s has to be debuggable without a load
run, and a run that takes ten minutes must not be a call that blocks for ten
minutes.

## Not doing

- **No HTTP transport, no auth, no multi-tenancy.** Local stdio, one caller,
  the machine's own limits file. A hosted load-generator API is a different
  product with a different threat model.
- **No new capability.** Every tool is 0087, 0088, 0089 or 0091 behind a name.
  If a tool needs a feature none of those built, it does not ship.
- **No confirmation prompt.** Asking the human is the client's job, and the
  preview is what it asks with. The server's job is to refuse what 0088 refuses,
  whatever the client did.
- **No live streaming of a run.** 0081 pushes snapshots; a status poll is
  enough here and a subscription is a second protocol to keep alive.

## Shape

`proofload-mcp`, a stdio MCP server over the CLI's own entry points:

| Tool | Does | Sends |
|---|---|---|
| `benchmark` | a target in, a plan out — generated, validated, previewed and smoked, ready to `run` | one request per step |
| `plan_schema` | returns `plan/1`, the subset, and two worked plans | nothing |
| `validate` | parses a plan, resolves steps and goals, names the error | nothing |
| `preview` | 0088: hosts, request count, duration, users needed | nothing |
| `from_openapi` | 0091: a document in, a plan out | nothing |
| `smoke` | one request per step, so a typo is not found at three thousand a second | one per step |
| `trace` | 0058: one user walked, with URL, headers, body, status and captures | one journey |
| `run` | starts a run, returns a `runId` | **the load** |
| `status` | the countdown from 0064, or the 0087 summary when done | nothing |
| `explain` | the 0087 `Full` document for a finished run | nothing |
| `report` | the path of the run's self-contained HTML page, for a person to open | nothing |
| `list_runs` | what is in the workspace, newest first, with each verdict | nothing |
| `compare` | 0038 against a baseline: better, worse, or cannot tell | nothing |

```
run   { "plan": "<yaml>" }        -> { "runId": "r-7", "sending": "3,000 requests over 1m" }
status{ "runId": "r-7" }          -> { "state": "running", "remaining": "38s" }
status{ "runId": "r-7" }          -> { "state": "done", "verdict": "behind", "remedy": "..." }
```

Every tool states what it will send before it sends it, and only `run` sends
load: `smoke` sends one request per step and `trace` walks one user, both
bounded by the plan rather than by its rate. Everything else is free to call
and free to get wrong.

## Why this shape

**`benchmark` is the tool a request actually arrives as.** The rest of this
table is a verb per step of Proofload's own model, which is the shape a library
has and not the shape a question has. Nobody asks to validate a plan; they ask
whether their service holds up. Answering that through this table takes seven
calls in an order the caller has to infer, and inferring it wrongly is how a
run gets fired before anyone previewed it.

So one tool takes a target — an OpenAPI document, a base URL, or a plan
somebody wrote — and does the whole safe half: generates the plan, validates it,
previews it against the allowance, and smokes one request per step. It answers
with the plan, what running it would send, and what the smoke found. It does not
send load. `run` still does that, and the gate the allowance exists to create
stops being something a caller has to remember and becomes the only way through.

The primitives stay. A caller that has a plan already should not have to hand it
to a tool that would regenerate one, and `trace` answers a question `benchmark`
does not ask. But they stop being the front door.

`plan_schema` is the tool that makes the rest work. A caller that can ask for
the format writes a valid plan on the first attempt instead of a plausible one,
and the schema it gets back is the schema the parser enforces rather than
documentation about it. 0090's `llms.txt` covers the caller who reads the
repository; this covers the caller who never sees it.

`trace` and `smoke` are the difference between an agent that iterates and one
that guesses. A plan whose bodies are being rejected produces a run full of
400s, and the run says only that they were 400s; 0058 already walks a single
user and prints what was actually sent, which is the answer. Firing three
thousand requests to discover a typo in a path is the other half of the same
mistake, and one request per step finds it.

`report` exists because the two readers want different artefacts. An agent
reads 0087's JSON; a person opens 0006's page, which is one self-contained file
and cannot usefully be read by a model. Returning the path rather than the bytes
keeps the distinction honest — nothing gains from a language model reading
inlined SVG.

Splitting `run` from `status` is not politeness about timeouts, it is the only
shape that works: a ten-minute run inside one tool call is a dead connection, a
retry, and a second ten-minute run against the same target. A `runId` also
means 0050's one-run-at-a-time lock has something to refuse a second `run`
with, by name.

The protocol should be **hand-written stdio JSON-RPC in the leaf module**.
`pelican-mcp-server` already implements this next door and depending on it is
the obvious alternative — recommend against, narrowly: the framing is a few
hundred lines, and a cross-repository dependency for it ties Proofload's release
to Pelican's for something neither library is about. Read that code; do not
link it. Where 0091 depends on `pelican-import`, the thing being borrowed is a
parser worth thousands of lines and a shared schema model — the trade goes the
other way.

## Stack

- [x] **`spec-0092-server`** — the module, stdio framing, tool registration,
      and `plan_schema`.
      Done when: an initialise and a `tools/list` round-trip over a pipe in a
      test, with no process spawned.
- [x] **`spec-0092-benchmark`** — `benchmark`, over the tools below it.
      Done when: one call takes an OpenAPI document and answers with a plan, its
      preview and its smoke, having sent one request per step and no load.
- [x] **`spec-0092-read-only`** — `validate`, `preview`, `from_openapi`.
      Done when: a test asserts no socket is opened by any of the three,
      against a counting `HttpServer`.
- [x] **`spec-0092-debugging`** — `smoke` and `trace` over 0058's walk.
      Done when: `smoke` on a four-step plan sends exactly four requests against
      a counting `HttpServer`, and `trace` returns the body it actually sent.
- [x] **`spec-0092-run`** — `run`, `status`, the run registry and the 0088
      refusal path.
      Done when: a `run` over the limits returns the refusal as a result rather
      than an error, and a second concurrent `run` is refused by name.
- [x] **`spec-0092-results`** — `explain`, `report`, `list_runs` and `compare`.
      Done when: `compare` on two runs of the same plan returns 0038's third
      answer, "cannot tell", where the intervals overlap, and `report` returns a
      path that opens.
- [x] **`spec-0092-docs`** — `docs/mcp.md`: the config block, the tool table,
      and what the server will refuse.
      Done when: the config block is copied into a client and the tools appear.

## Acceptance

```bash
./gradlew build
./gradlew :proofload-mcp:installDist
echo '{"jsonrpc":"2.0","id":1,"method":"tools/list"}' | build/install/proofload-mcp/bin/proofload-mcp
```

## Open questions

> Answered 2026-09-04, each on the recommendation in its own bullet. The
> reasoning is left standing rather than deleted: a decision is easier to
> reopen when the alternative it beat is still written down.

- **Does `run` accept a plan inline, a path, or both?** Recommend both, with
  inline the documented default: a caller that has to write a file first needs
  a filesystem contract nobody specified.
- **Where do finished runs live?** Recommend a workspace directory with the
  HTML report and the JSON beside each `runId`, so `explain` is a read and the
  human has a page to open. Purge policy is an open question of its own.
- **Should `benchmark` also start the run, given `confirm: true`?** Recommend
  no. It would make the whole thing one call, and it would put the decision to
  send load inside a tool whose name says it is preparing to. The allowance is a
  fence against a caller that did not mean it; a flag that steps over the fence
  in the same breath as building it is the fence being decorative.
- **Does `smoke` need its own plan, or does it read the run's?** Recommend the
  run's, at one request per step: a second plan format for smoking is a second
  thing to keep in step with `plan/1`.
- **Should `sustainable` (0031) be a tool?** Recommend not in the first
  version. A capacity search is a long sequence of runs, and the ceiling it
  needs is the one thing 0088 is least sure of.
- **Does the server refuse to start with no limits file?** Recommend yes, and
  this is where 0088's "unlimited by default" is deliberately reversed: a
  library called by a person may assume competence, a server driven by a
  program may not.

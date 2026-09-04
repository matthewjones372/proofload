# 0092 — Kestrel over MCP

## Problem

0089 makes a plan a file and 0087 makes a result a document, which is most of
what a program needs. What is missing is the door. A caller driving Kestrel
today shells out, guesses the flags, and reads whatever lands on stdout — and a
caller that is a model has the further problem that it does not know the plan
format, so its first attempt is a syntactically valid file describing a tool
that does not exist.

Three things have to be true for that loop to close: the format has to be
askable rather than remembered, a plan has to be checkable without sending
anything, and a run that takes ten minutes must not be a call that blocks for
ten minutes.

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

`kestrel-mcp`, a stdio MCP server over the CLI's own entry points:

| Tool | Does | Sends requests |
|---|---|---|
| `plan_schema` | returns `plan/1`, the subset, and two worked plans | no |
| `validate` | parses a plan, resolves steps and goals, names the error | no |
| `preview` | 0088: hosts, request count, duration, users needed | no |
| `from_openapi` | 0091: a document in, a plan out | no |
| `run` | starts a run, returns a `runId` | **yes** |
| `status` | the countdown from 0064, or the 0087 summary when done | no |
| `explain` | the 0087 `Full` document for a finished run | no |
| `compare` | 0038 against a baseline: better, worse, or cannot tell | no |

```
run   { "plan": "<yaml>" }        -> { "runId": "r-7", "sending": "3,000 requests over 1m" }
status{ "runId": "r-7" }          -> { "state": "running", "remaining": "38s" }
status{ "runId": "r-7" }          -> { "state": "done", "verdict": "behind", "remedy": "..." }
```

Exactly one tool sends a request, and it says what it is about to send in its
own result. Everything else is free to call and free to get wrong.

## Why this shape

`plan_schema` is the tool that makes the rest work. A caller that can ask for
the format writes a valid plan on the first attempt instead of a plausible one,
and the schema it gets back is the schema the parser enforces rather than
documentation about it. 0090's `llms.txt` covers the caller who reads the
repository; this covers the caller who never sees it.

Splitting `run` from `status` is not politeness about timeouts, it is the only
shape that works: a ten-minute run inside one tool call is a dead connection, a
retry, and a second ten-minute run against the same target. A `runId` also
means 0050's one-run-at-a-time lock has something to refuse a second `run`
with, by name.

The protocol should be **hand-written stdio JSON-RPC in the leaf module**.
`pelican-mcp-server` already implements this next door and depending on it is
the obvious alternative — recommend against, narrowly: the framing is a few
hundred lines, and a cross-repository dependency for it ties Kestrel's release
to Pelican's for something neither library is about. Read that code; do not
link it. Where 0091 depends on `pelican-import`, the thing being borrowed is a
parser worth thousands of lines and a shared schema model — the trade goes the
other way.

## Stack

- [ ] **`spec-0092-server`** — the module, stdio framing, tool registration,
      and `plan_schema`.
      Done when: an initialise and a `tools/list` round-trip over a pipe in a
      test, with no process spawned.
- [ ] **`spec-0092-read-only`** — `validate`, `preview`, `from_openapi`.
      Done when: a test asserts no socket is opened by any of the three,
      against a counting `HttpServer`.
- [ ] **`spec-0092-run`** — `run`, `status`, the run registry and the 0088
      refusal path.
      Done when: a `run` over the limits returns the refusal as a result rather
      than an error, and a second concurrent `run` is refused by name.
- [ ] **`spec-0092-results`** — `explain` and `compare`.
      Done when: `compare` on two runs of the same plan returns 0038's third
      answer, "cannot tell", where the intervals overlap.
- [ ] **`spec-0092-docs`** — `docs/mcp.md`: the config block, the tool table,
      and what the server will refuse.
      Done when: the config block is copied into a client and the tools appear.

## Acceptance

```bash
./gradlew build
./gradlew :kestrel-mcp:installDist
echo '{"jsonrpc":"2.0","id":1,"method":"tools/list"}' | build/install/kestrel-mcp/bin/kestrel-mcp
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
- **Should `sustainable` (0031) be a tool?** Recommend not in the first
  version. A capacity search is a long sequence of runs, and the ceiling it
  needs is the one thing 0088 is least sure of.
- **Does the server refuse to start with no limits file?** Recommend yes, and
  this is where 0088's "unlimited by default" is deliberately reversed: a
  library called by a person may assume competence, a server driven by a
  program may not.

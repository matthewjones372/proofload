# Kestrel over MCP

A stdio server whose every tool is a call `kestrel-cli` already makes. What a
program can do here is what a person at a terminal can do, and there is no
second behaviour to keep in step.

## Starting it

```bash
./gradlew :kestrel-mcp:installDist
```

That writes `kestrel-mcp/build/install/kestrel-mcp/bin/kestrel-mcp`, a start
script with the jars beside it. No fat jar, so nothing has to be kept in step
with the modules it would have shaded.

It speaks line-delimited JSON-RPC on stdin and stdout, so it is testable with a
pipe before any client is involved:

```bash
printf '%s\n' '{"jsonrpc":"2.0","id":1,"method":"tools/list"}' \
  | kestrel-mcp/build/install/kestrel-mcp/bin/kestrel-mcp
```

## Connecting a client

Point the client's `command` at that script. For Claude Code:

```bash
claude mcp add kestrel -- /absolute/path/to/kestrel/kestrel-mcp/build/install/kestrel-mcp/bin/kestrel-mcp
```

For a client configured by file, the same thing as an entry:

```json
{
  "mcpServers": {
    "kestrel": {
      "command": "/absolute/path/to/kestrel/kestrel-mcp/build/install/kestrel-mcp/bin/kestrel-mcp"
    }
  }
}
```

The path has to be absolute: a client starts the server from its own working
directory, not yours.

## The tools

| Tool | Does | Sends |
|---|---|---|
| `plan_schema` | the shape of a `plan/1` document, with two worked plans | nothing |
| `validate` | parses a plan, resolves its steps and goals, names the line of anything wrong | nothing |
| `preview` | what the plan would send — users, requests, window, peak rate, hosts | nothing |
| `from_openapi` | reads an OpenAPI document, writes the plan it describes | nothing |
| `smoke` | one request per step, so a typo is found here rather than at three thousand a second | one request per step |
| `trace` | walks one user and says what each step sent and what came back | one journey |
| `run` | starts the run, returns a `runId`, does not wait | **the load the plan asks for** |
| `status` | what a run is doing, or the verdict and remedy of a finished one | nothing |

**Ask `plan_schema` first.** It is the tool the others depend on: a caller who
can ask for the format writes a valid plan on the first attempt rather than a
plausible one, and what comes back is the shape the parser enforces rather than
documentation about it.

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

## What it will refuse

A plan that does not read comes back as a tool result with `isError` set and the
parser's own sentence in it — the line, and the keys that were allowed. That is
what a caller correcting itself needs; a stack trace buries it.

`preview` also consults the machine's `kestrel.toml`, so a plan over the rate,
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

## Not here yet

`explain`, `report`, `list_runs` and `compare` are specified in
[0092](../specs/0092-kestrel-over-mcp.md) and not built.

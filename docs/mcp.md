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

**Ask `plan_schema` first.** It is the tool the others depend on: a caller who
can ask for the format writes a valid plan on the first attempt rather than a
plausible one, and what comes back is the shape the parser enforces rather than
documentation about it.

Every tool's description states what it sends before you have to find out. All
four of the ones above send nothing, so they are free to call and free to get
wrong — which is the point, because it lets a caller iterate against a parser
instead of guessing.

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

## Not here yet

`smoke`, `trace`, `run`, `status`, `explain`, `report`, `list_runs` and
`compare` are specified in
[0092](../specs/0092-kestrel-over-mcp.md) and not built. **Nothing here sends
load yet**: the server can write and check a plan, and cannot run one.

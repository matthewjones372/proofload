# 0110 — a run the chat can read

## Problem

`docs/mcp.md` has a section called "Two readers, two artefacts": `explain`
returns JSON for the agent, `report` returns a path for a person with a browser.
Nobody serves the third reader — the person watching the chat. What they get is
`status`, which is JSON, and that section already says why that is wrong:
"handing a person the JSON would be handing them the thing the charts were made
from". `Density.Summary` describes itself as "small enough to hold in a prompt, a
comment or a chat message", and it is still JSON.

The renderer for that reader is already written and published.
`proofload-report-github` exposes `RunResult.markdown()` — 639 lines with its own
goldens — described as "a table GitHub renders and a terminal still reads: no
colour, no emoji, and the columns padded so the numbers line up". Nothing over
MCP can reach it: that module is neither a dependency of `proofload-mcp` nor on
its allowed runtime classpath.

## Not doing

- **Not inlining the HTML page.** `Results.kt` already argues against it, and
  106 KB of self-contained page is ~30k tokens for something read with eyes.
- **Not a new content type.** `{"type":"text"}` is enough; chats render
  markdown. `Protocol.kt` is untouched.
- **Not a second renderer.** The chat gets the same function CI gets, or it
  drifts from it.
- **Not the distribution.** Bars are 0111.
- **Not touching `explain`, `status` or the page.**

## Shape

```
summarise {"runId": "r-3f9c1a04"}
```

answers with what a job summary gets: the lost and behind warnings, the step
table, stages, failures and totals — the run, for a person, in the channel they
are already looking at.

```kotlin
// Results.kt — the whole change
internal fun summarised(registry: Registry, id: String?): String =
    onFinished(registry, id) { content(it.markdown()) }
```

## Why this shape

A separate tool rather than `report {"as": "markdown"}`: `report`'s row promises
it "writes the page and returns its path", and this writes nothing, so `Sends`
stays one row per verb. The alternative is an argument on `report`, which keeps
the tool count down and makes one row describe two verbs; recommended against.

`markdown()` is reused rather than reimplemented because a chat-only renderer is
the "second behaviour to keep in step" that `proofload-mcp`'s build file warns
about in its first comment.

## Stack

- [ ] **`spec-0110-markdown-over-mcp`** — `proofload-mcp` takes
      `proofload-report-github`, its allowed classpath gains it, and a
      `summarise` tool returns `markdown()`.
      Done when: `summarise` returns exactly `markdown()`'s bytes for a finished
      run, and `NoThirdPartyDependenciesTest` still passes.
- [ ] **`spec-0110-docs`** — "Two readers, two artefacts" becomes three, in
      `docs/mcp.md`, and the tool table gains the row.
      Done when: the table names what `summarise` sends, which is nothing.

## Acceptance

```bash
./gradlew build
./gradlew :proofload-mcp:installDist
printf '%s\n' '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}' \
  '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"summarise","arguments":{"runId":"RUN"}}}' \
  | ./proofload-mcp/build/install/proofload-mcp/bin/proofload-mcp
```

## Open questions

1. **`summarise`, or an argument on `report`?** Recommended: the separate tool,
   per Why this shape. Say if you would rather keep the tool list short.
2. **British spelling in a tool name?** Every other tool is one plain word.
   Recommended: `summary`, to sidestep it entirely.
3. **Does `markdown()` need the `Comparison` and `Floor` arguments here?** It
   takes both, and the server holds a registry that could supply a baseline.
   Recommended: pass neither in this spec — a comparison is what `compare` is
   for, and adding it here duplicates that verb.

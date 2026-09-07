# 0098 — A benchmark worth committing

## Problem

Everything 0092 produces is ephemeral. A plan is written into a chat, run, and
lost; the next person asks the same questions, picks slightly different answers,
and gets a number nobody can compare to the last one. The tool that exists to
stop a load test being a guess leaves no artefact saying what was measured or
why those numbers were the ones asked for.

The repository already believes the opposite of this about its own work. Nothing
here is built without a spec somebody edited and committed, because arguing with
a page is cheap and arguing with six thousand lines is not. A benchmark is the
same shape of thing: a decision about what matters, made once, disagreed with
later.

And the decisions are the expensive part. The plan is four lines of YAML; that
`/search` is the endpoint worth testing, that 200 a second is what it sees at
peak, that 250 ms is the p99 anyone cares about — those took a conversation, and
today they survive only in one.

## Not doing

- **Not a second plan format.** The plan is `plan/1`, embedded as it is. A
  document that restates it in prose is two things to keep in step.
- **No new measurement.** Everything here is a run that already happened.
- **Not a report.** 0006's page is what a run produced; this is what a team
  decided to keep asking.
- **No scheduling, no CI wiring.** Whether this runs nightly is a team's
  business and 0030 already writes down how.

## Shape

A tool that turns an answered conversation into a file worth committing:

```
write_spec {"runId": "r-1", "into": "benchmarks/search.md"}
```

```markdown
# Benchmark: search

## What this measures

`GET /search` at 200/s for 30s, against the products endpoint as a control.

## Why these numbers

- `/search` is the only endpoint with a distribution rather than a constant, so
  it is the only one where a percentile says anything.
- 200/s is what it sees at peak. (Asked; answered by whoever wanted this.)
- p99 under 250 ms is the target. (Asked; answered.)

## The baseline, measured 2026-09-04

| step | p50 | p99 |
|---|---|---|
| listProducts | 11.3 ms | 15.1 ms |
| search | 196 ms | 356 ms |

1,800 requests, 0 failed. The generator kept its schedule.

## The plan

```yaml
kestrel:  plan/1
...
```

## What this does not cover

Writes. Authentication — `/account` answers 401 and no credential was supplied.
```

The numbers come off the run, the plan comes off the plan, and the prose is what
the caller answered when the tool asked. A section it has no answer for says so
rather than being omitted, because an unanswered question is the useful half of
a first draft.

## Why this shape

Markdown with the plan embedded, rather than a plan with comments: the reasoning
is the part worth reviewing and a YAML comment is where reasoning goes to be
skipped. It also means a diff shows an argument changing, which is what anybody
reviewing a benchmark is actually looking at — a rate moving from 200 to 500 is
a claim about production, not a config change.

The alternative is writing the plan alone and letting the reasoning live in a
commit message. Recommend against: the commit message is read once, and the
question "why is this the rate" is asked every time somebody changes it.

## Stack

- [x] **`spec-0098-write`** — `write_spec` over a finished run and its plan.
      Done when: a document names the run's own measured percentiles, embeds a
      plan `validate` accepts, and states what it does not cover.
- [x] **`spec-0098-answers`** — the answered questions carried into it, so the
      prose is the caller's rather than invented.
      Done when: a spec written without answers says which questions are open
      rather than inventing a reason.
- [x] **`spec-0098-again`** — `run` taking a committed spec, so the file is
      executable and not only readable.
      Done when: a spec written on one day runs unedited on another and compares
      against the baseline it records.

## Acceptance

```bash
./gradlew build
```

## Open questions

- **Does it write the file, or hand back the text?** Recommend writing it, with
  the path in the answer. A caller that has to paste a document into a file will
  paste it once and never again.
- **Where does the baseline live — in the prose, or in a baseline file?**
  Recommend the prose for reading and 0021's baseline file for comparing, and
  say in the document which is which. A table nobody can diff numerically is
  documentation; a baseline nobody can read is not a decision.
- **Does `write_spec` refuse a run that fell behind?** ~~Recommend refusing.~~
  **Reversed while building it.** Refusing outright makes the tool unusable on
  any machine with ordinary jitter — a short run against a fast target is
  routinely a few percent behind, and a benchmark you cannot record on a laptop
  is one nobody writes. It also contradicts how this codebase treats every other
  unreliable number: `Tail.Absent` carries its reason, `Tell.CannotTell` carries
  what would change it, `Measurement.Absent` says why there was nothing to
  measure. None of them withhold.

  So: **`lostGround` refuses** — the load that left was not the load the profile
  named, which makes the plan itself wrong and there is nothing worth recording.
  **`fellBehind` writes, and says so in the document**, above the table, in the
  words the remedy already uses. A recorded caveat is not a wrong number
  committed; it is the thing this repository does everywhere else.

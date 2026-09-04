# 0088 — A run nothing fires by accident

## Problem

Nothing in Kestrel bounds what a run may do. `checkout.at(50000.perSecond, over
= 8.hours)` against a hostname is a legal value, and the first thing that tells
anyone it was wrong is the target. A typo does it; so does a caller that is not
a person — 0089 and 0092 hand the rate and the URL to a program, and a load
generator driven by a program with no ceiling is a denial-of-service tool with
good manners.

The pieces of an answer already exist and are not connected: `profile
.userCount()` is 0001's claim that a scenario is a value you can ask questions
of before running it, and 0064 already says how long a run will take. Nobody
has to fire a request to know what a plan would send. Nothing makes them look.

## Not doing

- **Not a security boundary.** This stops accidents and mistakes, not a hostile
  caller with the jar. A limits file is a fence, not a sandbox, and the spec
  says so where anyone would assume otherwise.
- **No credentials, no allowlist of paths, no per-host rate policy.** One
  ceiling and one host list.
- **No change to what a run measures.** A refused run measures nothing; it is
  not a void rung.
- **No prompting.** A library does not ask a question on stdin. The refusal is
  a value; deciding what to do with it is the caller's.

## Shape

A file a human commits, next to the build:

```toml
# kestrel.toml — what a run on this machine may do
hosts       = ["localhost", "*.staging.internal"]
maxRate     = "500/s"
maxDuration = "10m"
maxRequests = 2_000_000
```

And a plan you can ask before you fire it:

```kotlin
import io.github.matthewjones372.kestrel.Allowance
import io.github.matthewjones372.kestrel.preview

val plan = checkout.at(50.perSecond, over = 1.minutes)

when (val asked = plan.preview(Allowance.fromFile())) {
    is Preview.Allowed -> println(asked.requests)  // 3_000, over 1m, to orders.internal
    is Preview.Refused -> println(asked.reason)    // OverRate(asked = 50/s, allowed = 20/s)
}
```

Running within one is a **second entry point**, not a changed one:

```kotlin
when (val ran = kestrel.runWithin(Allowance.fromFile(), plan)) {
    is Ran.Result  -> ran.result.writeHtmlReport(path)
    is Ran.Refused -> println(ran.reason)
}
```

Absent file means unlimited, and a preview says so in as many words: a tool that
fails closed on a machine with no config is a tool people delete the config to
use.

## Why this shape

The preview is the valuable half. A ceiling only refuses; a preview lets a
caller — or a person reading what a caller proposes — see *3,000 requests over
one minute to orders.internal* and decide, which is the whole confirm-before-
you-fire pattern that makes a load generator safe to hand to a program. It
costs nothing to compute, because 0001 made the plan a value.

Refusal as a value rather than an exception follows the repo's rule: a caller
was promised this could happen, so it is in the return type. The alternative —
throwing — reads better at a call site that has no intention of handling it,
and is wrong for the caller that does.

**`Allowance`, not `Limits`.** `Limits` is taken: 0065 named the injector's own
ceilings that, the descriptors and ports it ran *into* while measuring. This is
the opposite direction — what an operator permits a run to do before it starts —
and two types called the same thing in one package, one of them observed and one
declared, is a confusion nobody would untangle twice.

**`runWithin` beside `run`, rather than a `run` that changes shape.** The
refusal has to be a value, and `run` returns a `RunResult`; widening it to a sum
would delete lines from `kestrel-engine`'s `.api`, which is the one change
AGENTS.md calls a pull request that breaks somebody. A second entry point costs
one method and leaves every existing caller compiling. The alternative — `run`
throwing on refusal — is rejected for the reason the repo already gives: a
caller was promised this could happen, so it belongs in the return type.

TOML wants a parser, which core may not have. Recommend a hand-read
`key = value` subset in core rather than a dependency or a leaf module: four
keys and a list, and a malformed file refused by name. The alternative is JSON,
which nobody wants to hand-edit with a comment in it.

## Stack

- [ ] **`spec-0088-allowance`** — `Allowance`, the file subset, and the refusal
      reasons as a sealed type.
      Done when: a malformed file names the bad line, and an absent file yields
      `Allowance.none`.
- [ ] **`spec-0088-preview`** — `preview(allowance)` over a plan: hosts, request
      count, duration, peak rate, and the users it would need.
      Done when: a preview of a staged plan reports the peak rate of the tallest
      stage, not the mean.
- [ ] **`spec-0088-enforcement`** — `kestrel.runWithin(allowance, plan)`
      returning `Ran.Refused` before the first departure, with `run` untouched.
      Done when: a refused run sends nothing, provable against a JDK
      `HttpServer` that counts requests.
- [ ] **`spec-0088-docs`** — `docs/allowance.md` and the honest paragraph about
      what a fence is not.
      Done when: the page says "not a sandbox" in the first screen.

## Acceptance

```bash
./gradlew build
./gradlew :examples:refusedRun   # exits non-zero, zero requests observed
```

## Open questions

> Answered 2026-09-04, each on the recommendation in its own bullet. The
> reasoning is left standing rather than deleted: a decision is easier to
> reopen when the alternative it beat is still written down.

- **Does `run` itself consult an allowance?** Recommend no. A library call a
  person wrote is a person's decision; the fence is for the callers that are
  programs, and they use `runWithin`. Making `run` consult a file it was never
  passed is action at a distance in the one method everything goes through.
- **Does an absent file mean unlimited or loopback-only?** Recommend
  unlimited, with the preview saying "no limits file found". Failing closed by
  default trains people to delete the fence.
- **Is `hosts` matched on the URL host or the resolved address?** Recommend the
  host as written: resolving invites a DNS call on a path that has not started
  measuring yet, and the string is what a reviewer reads.
- **Should limits also cap injector count under 0070?** Recommend not in this
  spec — a distributed run multiplies the ceiling and that is a real hole, but
  it is a second spec with the coordinator question in it.

# What a run on this machine may do

A rate is a number, and a number arrives from somewhere. Somebody types it,
or a script computes it, or — under [0089](../specs/0089-a-plan-without-a-compiler.md)
and [0092](../specs/0092-proofload-over-mcp.md) — a program writes it into a plan
file. `checkout.at(50000.perSecond, over = 8.hours)` is a legal value whichever
of those wrote it, and without a fence the first thing that says it was wrong
is the target.

## This is a fence, not a sandbox

Read this paragraph before the rest of the page.

An `Allowance` stops a mistake: a typo, a misplaced decimal point, a program
that computed a rate nobody read. It does not stop somebody holding the jar. A
caller can construct its own `Allowance`, or call `run` instead of `runWithin`,
and nothing here prevents either — they are ordinary public API.

If what you need is a guarantee rather than a guard rail, the boundary belongs
somewhere this tool cannot reach around: a network policy, a firewall, a
credential the load generator does not hold.

## The file

`proofload.toml`, beside the build, committed by a person:

```toml
# what a run on this machine may do
hosts       = ["localhost", "*.staging.internal"]
maxRate     = "500/s"
maxDuration = "10m"
maxRequests = 2_000_000
```

Every key is optional and an absent one bounds nothing. `*.staging.internal`
covers a subdomain and not the domain itself. A malformed value names its line
rather than quietly defaulting.

**No file means no limits.** A tool that fails closed on an unconfigured
machine is one people delete the configuration to use, which turns the fence
off everywhere instead of off once.

## Asking before you fire

A scenario is a value, so what a plan would do is arithmetic:

```kotlin
import io.github.matthewjones372.proofload.Allowance
import io.github.matthewjones372.proofload.Preview
import io.github.matthewjones372.proofload.preview

when (val asked = checkout.at(50.perSecond, over = 1.minutes).preview(Allowance.fromFile())) {
    is Preview.Allowed -> println("${asked.users} users, ${asked.requestsAtLeast}+ requests, ${asked.hosts}")
    is Preview.Refused -> println(asked.reason.described)
}
```

Four of the numbers it returns are worth knowing the shape of:

| | |
|---|---|
| `peakRate` | the tallest stage, never the mean — a fence that averaged a ramp would allow a peak nobody agreed to. Null for a closed run, whose departures are the target's to decide |
| `requestsAtLeast` | the fewest it can send |
| `requestsBounded` | whether that is also the most. False where `during` or `doIf` leaves the count to the run — and a plan like that is **refused** against `maxRequests` rather than allowed on its lower bound, because a fence that cannot count cannot fence |
| `untargeted` | steps whose host could not be read. Not "allowed": unknown. A step body calling a client of your own is invisible from here, and counting it as safe would be the reassurance this page exists to avoid |

## Running inside one

```kotlin
import io.github.matthewjones372.proofload.Ran
import io.github.matthewjones372.proofload.engine.runWithin

when (val ran = Proofload().runWithin(Allowance.fromFile(), plan)) {
    is Ran.Result -> ran.result.writeHtmlReport(Path.of("build/reports/proofload/checkout.html"))
    is Ran.Refused -> System.err.println(ran.reason.described)
}
```

A refused run departs nothing — the check happens before the first departure,
and the engine's own test proves it by counting what the action was asked to do
rather than by believing the runner.

`run` itself consults no allowance. A library call you wrote by hand is your
decision; the fence is for the callers that are programs, and making the method
everything goes through read a file it was never passed would be action at a
distance.

## Not to be confused with `Limits`

`Limits` is the other direction, and older: what the *injector* ran into while
it measured — descriptors, ephemeral ports, its share of the cores. That one is
observed during a run and travels in the result. This one is declared before a
run and decides whether there is one.

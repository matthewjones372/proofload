# 0045 — A clock the recorder owns

## Problem

0025 gives every sample a second to belong to, counted from the run's start.
The engine knows that origin because it started the run. `kestrel-pelican` does
not: it is handed a `RunRecorder` and has no idea when the run began, so it
counts seconds from the moment its transport was constructed.

In the usual case — a transport built for a run, moments before it — the two
agree closely enough that nothing on the page is wrong. That is the problem
rather than the defence. It is an assumption standing where a measurement
should be, in the one part of this repository whose stated rule is that a number
in a report is a measurement or it is a lie. A transport built once and reused
for two runs, or built at class-initialisation time and used a minute later,
silently shifts every second in the timeline of a module nobody would think to
suspect — and the shape of the chart still looks plausible.

## Not doing

- No new clock abstraction, no injectable time source. The engine reads
  `System.nanoTime()` and that is right; this is about who is allowed to ask.
- No change to what a second contains, or to how it is counted. 0025 settled it.
- No change to `kestrel-pelican`'s transport API for its callers.
- No wall-clock time in the recorder. The origin is monotonic, like everything
  else on the timed path.

## Shape

```kotlin
recorder.sinceStart()      // how long this run has been going, from the recorder's own origin
```

- `RunRecorder` already takes the instant the run started; it gains a monotonic
  origin beside it and answers offsets from that.
- Every recording caller — the engine and `kestrel-pelican` alike — asks the
  recorder rather than keeping an origin of its own.
- The engine's existing `at` argument keeps working: a caller that already knows
  the offset passes it, and one that does not asks.

## Why this shape

Two ways. Passing the run's start into the Pelican transport pushes the problem
onto whoever constructs it, which is the caller, who has even less reason to
know. Putting the origin on the recorder puts it on the object that already
represents "this run's measurements" and that every recording path holds
already — so there is one origin per run by construction rather than by
convention, and a second caller cannot invent a second one.

The cost is one field on the recorder and a nanosecond read where a caller has
no offset. Nothing on the timed path allocates, and the engine's hot path is
unchanged because it still passes the offset it already computed.

## Stack

- [x] **`spec-0045-origin`** — the monotonic origin on `RunRecorder`, and
      `kestrel-pelican` asking it instead of timing from construction.
      Done when: a transport constructed well before a run starts records its
      samples in the seconds they actually happened in, and the engine's
      recording path still passes its own offset.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. **Does a recorder built by a test need an origin it can control?** Recommend
    yes — the tests for this cannot use a real clock and stay deterministic.
    Recommend an internal constructor taking the origin, rather than a public
    clock parameter that becomes part of the API forever.
2. **What happens to a sample recorded before the run starts?** With one origin
    per recorder it cannot happen. Recommend not defending against it, and
    letting the negative-duration `require` in `Histogram.record` be the thing
    that names it if it somehow does.
3. **Do the shard recorders each need one?** They are merged into one at the
    end, so they must all share the origin or the merge pools three timelines
    with three zero points. Recommend the origin travelling with the recorder
    that spawns the shards, and a test that merging preserves it.

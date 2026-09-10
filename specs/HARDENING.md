# Hardening for 1.0

Not a spec: an index over the fourteen drafts numbered 0121 to 0134, which came
out of one adversarial review of the tree. It exists so nobody has to open
fourteen files to find out what is being proposed, what is already true, and
what is deliberately being left alone.

Nothing here is built. Nothing here is approved. Every one of these is a draft
under step 1 of `specs/README.md`'s lifecycle, and the point of the exercise was
to be cheap to disagree with.

## What the review was looking for

A way the current architecture could produce a **plausible-looking but incorrect
load-test result**. It found five, and they are the reason the list is this
long rather than a page of tidying:

1. **Carrier starvation is reported as target latency.** A virtual thread
   descheduled inside a step has that wait counted in `serviceTime`. `behind`
   stays flat (the user departed on time), `hiccups` cannot see it (it ticks on
   a platform thread), and Little's law agrees (both sides inflate together).
   Every gate is green and the target looks slower than it is. → **0126**
2. **Nothing makes a caller acknowledge validity.** `metEveryGoal` is true for a
   run with no goals; the precedence order is `private` in an export module; and
   the README's own first example asserts on a p99 with no schedule check.
   → **0121**
3. **A run that sent nothing passes.** `1.perSecond over 500.milliseconds` is
   zero arrivals, and zero of zero failures meets a failure-rate goal.
   → **0121, 0130**
4. **`fellBehind` is a whole-run verdict on a per-step problem.** It weighs
   lateness against the *worst* step's tail, so in a mix of a 2 s step and a
   2 ms step it is silent while the fast step's numbers are 25× corrupted.
   → **0121**
5. **A run cannot be stopped.** No cancellation, no deadline, an unbounded
   `awaitAll`, and a JVM exit loses everything the run measured. → **0128**

Three smaller ones are recorded in the specs that own them: `Arrivals` reports
the *intended* spacing rather than what left (0122); `Departed.left` is a
lost-update race on the closed path (0125); and a sample recorded after freeze
destroys the whole result through a `checkNotNull` (0125).

## Already covered — no new spec needed

Where the review's brief asked for something the tree already has:

| Asked for | Already |
|---|---|
| two clocks, response time from the promised departure | 0003, built |
| lateness recorded, per second, with a materiality threshold | 0076, 0097, built |
| lateness against the interval, apart from against the tail | 0043, built |
| a run that fell behind still reporting what it measured | 0076, built |
| Little's law as a free consistency check | 0068, built |
| what the machine can resolve, and comparisons that consult it | 0039, built |
| injector ceilings — descriptors, ports, CPU | 0065, built |
| the tool's scheduling ceiling, and its footprint | 0011, 0093, built |
| arrivals that are not a metronome | 0034, built |
| think time as a step that records nothing | 0024, 0067, built |
| a step recording more than one sample | 0075, built |
| the pool wait kept beside the query, not inside it | 0083, built |
| what a socket ceiling cannot say | 0118–0120, drafted |

The review's recommendation in each of these cases is **document it, do not
change it** — which is what 0130, 0132 and 0133 are for.

## The new drafts

| Spec | What it settles | P |
|---|---|---|
| [0121](0121-a-number-you-are-not-allowed-to-read-yet.md) | measurement validity as a value, and an API where the short path is the safe one | **P0** |
| [0122](0122-late-missed-or-never-asked-for.md) | the vocabulary — arrival, departure, lateness, missed, dropped, catch-up, saturation — and the counts behind it | **P0** |
| [0123](0123-experiments-designed-to-break-it.md) | nine adversarial coordinated-omission experiments against an oracle | **P0** |
| [0124](0124-a-schedule-tested-without-a-clock.md) | scheduling arithmetic proven deterministically, using seams that already exist | **P0** |
| [0125](0125-correct-at-a-hundred-thousand.md) | correctness of the sharded recorder under load, independent of speed | **P0** |
| [0126](0126-when-the-carrier-is-the-bottleneck.md) | carrier starvation and pinning, and the claims virtual threads do not earn | **P0** |
| [0127](0127-what-the-instruments-cost-the-measurement.md) | instrumented against uninstrumented, per departure, at the tail | **deferred** |
| [0128](0128-a-run-that-can-be-stopped.md) | the run lifecycle, cancellation, deadlines and what happens to in-flight work | **P0** |
| [0129](0129-a-result-that-cannot-lie-about-itself.md) | a `check()` at every freeze, merge and read — 0134 owns the list it checks | P1 |
| [0130](0130-what-a-hundred-a-second-means.md) | the open model's arithmetic as a 1.0 contract | **P0** |
| [0131](0131-the-bar-a-closed-model-would-have-to-clear.md) | the closed-model statement, and the bar if it were ever to change | P1 |
| [0132](0132-where-a-step-starts-and-stops.md) | what a step measures, made explicit and testable | P1 |
| [0133](0133-the-documents-1-0-cannot-ship-without.md) | the documentation audit and the generated ROADMAP; the new pages land with the specs that own them | P1 |
| [0134](0134-the-statements-that-must-never-become-false.md) | the sixteen invariants, and which five do not hold | **P0** |

## What a first read cut

Reviewed against cost rather than merit, because fourteen specs at four stack
entries each is fifty-five pull requests and that number, not the quality of any
one of them, is what decides whether this is a hardening pass or a release
cycle. Nothing is deleted — `specs/README.md`'s rule is that a spec stays as the
record of a decision, and an argued-against one keeps its argument, as `0086`
and `0061-containers` already do.

| Cut | Why |
|---|---|
| **0127** entirely | needs an uninstrumented arm it refuses to ship, and 0011 and 0093 already bound the question from two sides |
| **0129**'s list | 0134 is the anchor and owns the enumeration; two lists means the second one goes stale |
| **0133**'s new pages | all four are already stack entries in 0126, 0130, 0131 and 0132 |
| **`spec-0126-jfr`** | detecting starvation is the invariant; naming the site is a profiler, and JFR is already one |
| **`spec-0122-saturation`** | `heldScheduleFor` answers it off the same series |

Eleven specs, about forty-four entries.

## Build order, which is not priority order

The dependencies are real and nobody wrote them down:

1. **0124 and 0134 first.** 0124 is the cheapest thing here — no new
   abstraction, pure seams that already exist, and it converts flaky wall-clock
   tests into arithmetic, which makes everything after it cheaper to verify.
   0134 gives the table to measure progress against.
2. **Then 0121**, because 0126, 0128 and 0131 each add a case to its `Doubt`
   sealed interface. Building them first means opening that type three times.
3. **Then 0123**, then 0126 and 0128.

## Priority, and what it means

**P0 — 1.0 cannot honestly ship without it.** Each is either an invariant in
0134's table that reads "no", or the evidence for one that reads "yes,
untested". 0134 is the anchor: it names the five gaps and sets the release rule
that no row may be a "no".

**P1 — 1.0 is weaker without it and is not dishonest without it.** 0129, 0131,
0132 and 0133 make claims precise, cheap to defend and hard to drift. They can
follow 1.0 if they have to; the risk of deferring them is a claim nobody notices
going stale, not a wrong number.

**Deferred — 0127 only**, and past 1.0 rather than within it. See the cut table
above and the note at the head of the spec.

## Explicitly out of scope

Named here so the boundary is a decision rather than an omission:

- **A closed workload model.** Not in 1.0. 0131 states the position and the bar,
  and recommends against clearing it: the semantics are a research problem.
- **Throttling, pacing or catch-up smoothing.** A generator that lowers its own
  rate to keep schedule measures a load it then does not report. 0076 refused
  it; 0122 restates the refusal as an invariant.
- **Subtracting the tool's own overhead from a measurement.** 0126 and 0127 both
  recommend reporting beside, never subtracting: an estimate taken off a
  measurement is the interpolation `AGENTS.md` forbids.
- **A virtual clock or injectable time source.** 0124 recommends against it —
  the pure seams already exist and were built for other reasons.
- **A supported "instrumentation off" mode.** 0127 uses one as a benchmark arm
  and recommends against shipping it.
- **Managing the carrier pool.** 0126 detects and reports; it does not size,
  steal or schedule.
- **Cross-machine or cross-tool comparison.** 0030 and 0011 already settled
  both.

## Behaviour that needs a human decision before anything is built

**The one that decides the size of all of it** is 0134's third open question:
does 1.0 ship with a "no" in the invariant table? 0134 recommends not, and says
the honest alternative is to weaken the README's claim to match rather than
ship the claim over the gap. Fixing five invariants is most of the work below;
changing "a p99 you can trust" to something the code earns today is an
afternoon. Both are defensible. Shipping the current claim over the current
gaps is the option that is not.

Then the questions several specs branch on:

1. **Does reading a percentile from an invalid run throw, or merely flag?**
   0121 recommends both — a `validity` value and a `trusted()` gate — and
   recommends against a type that makes the unsafe read impossible.
2. **Is `stoppingAfter` allowed a default?** 0128 recommends no, and expects
   argument: a default silently turns a hung run into a short one.
3. **`Error` out of a step body**: a failed request, or a broken run? 0125
   recommends re-throwing and marking the run invalid.
4. **Does a closed run's `responseTime` become absent?** 0131 recommends yes,
   and it is a published-API break that should land before 1.0 rather than
   after.
5. **Is `MATERIAL` at five percent still right** once it is applied per step
   rather than per run? 0097 expected the figure to move once there were runs to
   move it against; 0121 is the change that produces them.

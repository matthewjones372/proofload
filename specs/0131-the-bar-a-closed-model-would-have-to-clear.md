# 0131 — The bar a closed model would have to clear

## Problem

Proofload already ships `users(n, over)`. 0024 argued for it and the code
implements it honestly: no schedule, no lateness recorded, `behind` empty rather
than full of zeros, `keptSchedule` refused where it is declared, and a comment
saying the collapse of response time onto service time "is the finding, not a
gap". That is more care than most tools take.

It is also not a supported closed model, and the repository does not say which
it is. `ROADMAP.md` lists 0024 as built, under a line that ends "the closed
model, labelled with what it cannot see". A reader deciding whether to use
`users(50, over = 5.minutes)` for a real capacity question gets no statement of
what that run can and cannot conclude, and every schedule-validity gate in the
tool answers *false* for it by construction — which reads as "kept its schedule"
to anyone who does not know why.

The purpose of this spec is to let the project state one of two things
deliberately, rather than leaving it implied:

> **Closed model is not supported in 1.0. `users(n)` is a fixed-population
> runner whose measurements are labelled, and no goal, capacity search or
> comparison may be judged from one.**

or, alternatively, the list of things that would have to be true first.

## Not doing

- **Not implementing a closed model.** Not think-time changes, not a queueing
  model, not `L = λW` inversion, not a population that adapts.
- **Not removing `users(n)`.** 0024's argument stands: refusing to build it does
  not stop anyone needing it, it stops them using a tool that is honest about it.
- No change to `pause`/think time (0024, 0067).

## Why a closed model is a different measurement

Not a variation on the open one — the opposite direction of causality.

- **Open**: arrivals are exogenous. The generator decides when to send, the
  target decides how long it takes, and those two facts are independent. Offered
  load is a constant of the experiment.
- **Closed**: arrivals are endogenous. A user sends again when the previous
  answer arrives, so **response time determines offered load**. As the target
  slows, the generator sends less, so the target recovers, so it sends more.
  The load is a fixed point, not an input.

The consequence is `[OPENCLOSED]`: mean response time under an open model can
exceed a closed one by an order of magnitude at the same nominal load, and
conclusions about scheduling *invert* between the two. A closed harness caps the
queue at its population by construction, so it cannot exhibit the queue growth
that is the whole failure mode a load test is looking for.

## How a naive implementation misleads

Every one of these is a plausible-looking result that is wrong, and the first
three are reachable in Proofload today:

1. **Reporting one clock as two.** `responseTime == serviceTime` for every
   sample, so a page with both columns implies the generator kept up. It did
   not: it has no schedule to keep.
2. **Passing every schedule gate.** `fellBehind`, `lostGround`, `heldScheduleFor`
   and `offered` are all false or null by construction, and the JSON headline
   reads `met`. A reader who knows the tool "proves the generator kept up" reads
   that as proof.
3. **Quoting a throughput.** Requests per second from a closed run is an
   *output*, and quoting it beside an open run's rate compares an input with an
   output.
4. **Comparing two closed runs.** If the target slowed, the second run sent less
   load, so a latency improvement can be an artefact of the reduced load — the
   comparison measures the target's slowness twice, once with its sign flipped.
5. **Searching for capacity.** A ladder over populations finds the population at
   which goals break, which is not a rate and does not transfer.
6. **Think time hiding it.** With enough think time a closed run approximates an
   open one, and there is no threshold at which it starts to. It is a limit, not
   a switch.

## The bar

Eight things, all of which must hold before the phrase "supports a closed
workload model" is used:

1. **The population is stated as the experiment's input and never converted to a
   rate.** `Offered`, `plannedInterval` and every rate on the page are absent,
   not zero. (Today: true.)
2. **Response time is reported as absent, not as equal to service time.** A
   closed run has no promised departure, so the honest reading is one clock and
   an explicit "not measured", not two identical columns. (Today: **false** —
   `responseTime` is populated and equals `serviceTime`.)
3. **Every result of a closed run is `Partial` at best** and carries a
   `ClosedModel` doubt naming what it cannot see (0121).
4. **Achieved throughput is reported with its causal direction stated** —
   labelled an output, never compared against an open run's asked rate.
5. **Little's law is the primary check**, since concurrency is the input and the
   law is the only thing tying it to the other two.
6. **A comparison between two closed runs refuses** unless the achieved
   throughput of both is within the band that makes the comparison meaningful,
   and says so where it refuses (0038's machinery, a new reason).
7. **A capacity search refuses a closed profile** at construction, as
   `expecting(keptSchedule)` already does.
8. **An adversarial test proves the omission exists** and that the tool says so:
   0123's experiment 1, run closed, must produce a *fast* p99 and a result that
   is not `Valid` — the one place in the repository where the coordinated
   omission is demonstrated rather than described.

## Recommendation

**State that the closed model is not supported in 1.0.** Items 1, 7 and most of
3 are already true; 2, 4, 6 and 8 are small and worth doing anyway, because they
make the existing feature honest rather than making a new one. Items 5 and the
full closed semantics — a population that is a fixed point, a warm-up long
enough to reach it, a stopping rule based on it — are a research problem, not a
1.0 problem.

The wording, for the README and `docs/concepts.md`:

> `users(n)` runs a fixed population and labels what that cannot see. Proofload
> does not claim to support closed-model *measurement*: response time under a
> fixed population is offered load in disguise, and no goal, capacity search or
> baseline comparison in this tool is judged from one.

## Stack

- [ ] **`spec-0131-statement`** — the wording above in the README,
      `docs/concepts.md` and `llms.txt`, and `users(n)` documented as a runner
      rather than a model.
      Done when: no page claims closed-model support and every page that
      mentions `users(n)` links this spec.
- [ ] **`spec-0131-absent`** — bar item 2: a closed run's `responseTime` reads
      absent rather than a copy of service time.
      Done when: the report shows one clock for a closed run, the JSON writes
      null, and no goal defaulting to `Clock.ResponseTime` silently reads a
      service time.
- [ ] **`spec-0131-doubt`** — bar items 3, 4 and 7.
      Done when: a closed run is `Partial(ClosedModel)`, a search over a closed
      profile is refused at construction, and the page labels throughput an
      output.
- [ ] **`spec-0131-proof`** — bar item 8.
      Done when: the same stall workload run open and closed produces a p99
      differing by more than an order of magnitude, and the closed one is not
      `Valid`.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
./gradlew :examples:timingTests
```

## Open questions

1. **Does making `responseTime` absent break the API?** Yes — `StepStats.responseTime`
   becomes nullable or gains an `Absent`. Recommend absorbing it before 1.0.
2. **Is refusing a closed capacity search too strong?** Recommend refusing: the
   answer is a population, the caller asked for a rate, and returning the wrong
   unit is worse than refusing.
3. **Should `users(n)` be renamed** to something that does not read as a model —
   `population(n)`? Recommend yes if it can be done before 1.0; `users(50)` is
   what every other tool calls its closed model, and the name does the misleading.

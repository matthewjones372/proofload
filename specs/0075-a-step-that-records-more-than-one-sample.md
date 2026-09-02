# 0075 — A step that records more than one sample

## Problem

A step produces one number. `Action.run(session): StepResult` (`Action.kt:7-9`)
returns one outcome, `runOn` (`VirtualThreads.kt:376-390`) times the body with
one `nanoTime` pair and calls `sink.record` once (`:388`, `RunRecorder.kt:31`).

Right for HTTP, where a step *is* one request. Wrong for a stream: 0059's
`awaiting` (`Messages.kt:79-91`) records the wait for a hundred messages, its
KDoc calling a message's own latency the thing "one sample cannot carry [count]
of" — while `received()` measures it and throws it away (`Messages.kt:206`,
`Completions.kt:93-99`). 0071 inherits the wall; `repeat(n)` (0053) escapes only
by walking the body N times.

## Not doing

- **No change to HTTP.** One request stays one sample.
- **No second recording path.** No parallel sink, no third `RunRecorder` entry.
- **No allocation per sample on the timed path**, and no buffer until the body
  returns: 0003 refused a per-request event log.
- **No making a stream look like N steps.** The row is the stream.
- **No new column or timing on `StepStats`.** `count` counts samples already.

## Shape

```kotlin
/** One answer under this step's name: [took] long, observed [at] into it. */
fun sample(took: Duration, at: Duration = sinceStarted(), reason: Reason? = null)

fun ScenarioBuilder.awaiting(name: StepName, count: Int, within: Duration) =
    exec(name) {
        val open = this[connection] ?: return@exec fail(NotConnected)
        // One sample per answer, timed from the send it answers.
        open.inbound.eachAnswer(count, within, ::sample)?.let(::fail)
    }   // result[tick].count is 100 messages; reached is still 1 user
```

- One verb beside `fail` and `set` on `StepScope` (`Action.kt:30-63`). The
  engine builds the scope and hands it in, so `sample` writes through the shard
  `Recorders` claims per record; `Action` becomes `run(scope: StepScope)`, the
  scope holding session, step start and sink.
- A body reporting nothing is recorded as today, one sample for the whole body;
  one reporting its own suppresses that. `reached` rides the first (`:116`); a
  per-sample `reason` goes through `countFailure`, so `MAX_REASONS_PER_STEP`
  (`:94,155-162`) applies; `fail` still fails *the step*, first reason winning.
- 0071's client stream is `send` per message, a sample each; its server stream a
  sample per message under `awaiting`, naming what each is measured from.

## Why this shape

**The 37th message.** Timing it from the step's intended departure obeys 0003's
words for response time and ruins the column: the number is how long the stream
had been running, beside rows where that cell is latency. So a sample is
measured from the nearest departure the body can name — for `awaiting`, the send
it answers, which `Pending` pairs and times — and the engine adds this user's
`schedulingDelay` unchanged, lateness being the promised departure's, inherited
once. Where none can be named, 0059 rules: count, do not time.

**Record through, never buffer.** The shard claim holds because nothing blocks
while it is held (`Recorders.kt:40-44`), so a body claims once per sample rather
than across a wait; a buffer would also flatten every sample into the second the
body started (`RunRecorder.kt:185-188`). `Histogram.record` increments a
`LongArray` (`Histogram.kt:50-55`), so nothing new allocates. The handle must
arrive at call time, an `Action` being built once and shared by every user
(`Scenario.kt:112-126`): a second parameter, `run(session, into)`, or the scope
— recommend the scope, which a body reports through already. That costs public
API, `HttpAction` (`:71`), the websocket module's nested `Action`/`action` pair
(`Messages.kt:43`) and pelican all changing shape; samples returned on
`StepResult` avoid it and buffer.

**The page.** The note (`HtmlReport.kt:261-267`, 0053's "30 requests, 10
reached") gains a clause: a step whose samples outnumber its reaches is a
stream. The cell (`:314`) and the arm's users off its most-reached step
(`Markdown.kt:238`) are unchanged.

## Stack

- [x] **`spec-0075-seam`** — the engine builds the `StepScope`; `Action` takes
      one; http, websocket and pelican follow. Done when: every existing test
      and golden passes untouched and the API dump moves once.
- [ ] **`spec-0075-sample`** — `sample` through the sink as the body observes
      it, `reached` on the first, the implicit sample only when none was. Done
      when: three samples are one row of count 3, reached 1, each in its second.
- [ ] **`spec-0075-awaiting`** — `awaiting` reporting a sample per message from
      the send it answers, the caveat gone from its KDoc and the cookbook. Done
      when: `awaiting(count = 100)` is 100 samples under one name and a
      disconnect part-way reports what arrived plus one failure.
- [ ] **`spec-0075-page`** — the count/reached note and both goldens. Done when:
      the note names a step whose samples outnumber its reaches and the goldens
      move once, the diff read rather than regenerated.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
# The ceiling must not move: this changes the path every request is timed on.
./gradlew :benchmarks:ceiling
```

## Open questions

1. **What times the failure sample when a stream fails part-way?** Recommend
    from the last sample — the answer that never came — not from the step's
    start, which puts a batch duration in a per-message histogram.
2. **Do a stream's samples count towards `goodput` and the run's rate?** They
    would, being over `count` (`Goodput.kt:38-56`), against 0059's "only sends
    are departures". Recommend counting them, reopening 0059's question.
3. **How does a sample seen on a foreign thread get a run offset?** `Inbound`
    counts from its own construction (`Messages.kt:111`) — 0045's drift, whose
    `sinceStart()` is not in the tree. Recommend converting on the body's own
    thread, both clocks being `System.nanoTime()`.
4. **Can `sample` fail the step too?** Recommend not: 3 bad messages of 100 is
    not a user to abandon, and `fail` is there — apart is what keeps
    first-reason-wins meaning anything.
5. **What is a server stream's message measured from?** Nothing sent it: from
    the call that opened it is time-to-the-k-th, climbing with the index; from
    the one before it is cadence. Recommend cadence, named as cadence — 0071's.
6. **Does the batch reading survive?** `awaiting`'s one sample answered "how
    long to receive a hundred". Recommend not: a step around it measures that.

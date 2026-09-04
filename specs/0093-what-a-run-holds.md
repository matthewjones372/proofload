# 0093 — What a run holds

## Problem

0011 measured whether the generator keeps its schedule and deliberately looked
away from the heap: the ceiling task hands the JVM `-Xmx2g` and its own comment
says the point is "to see where that stops keeping up rather than where the heap
does". Nothing has measured where the heap does.

Three decisions already rest on that unmeasured number. 0028 books departures a
window at a time rather than booking a million tasks. 0073 refused a metric per
request because "at fifty thousand a second, a memory profile". 0003 argued the
same way for histograms over a sample list. Each is probably right, and each is
an argument from reasoning in a comment rather than a figure anyone can check —
which is the shape of claim this repository does not otherwise allow itself.

There is a second question underneath, and it is the one that can corrupt a
measurement rather than merely end a run. Allocation on the timed path is
latency this tool reports as the target's: an injector that allocates per
departure buys itself a collection pause, and `hiccups` records the pause
without saying it was self-inflicted. Nobody knows the per-departure allocation
either.

## Not doing

- **No JMH.** 0011's reason stands: this weighs a whole run, not a method.
- **No tuning, no flags, no object pooling.** This spec finds the numbers.
  Changing them is whatever spec comes after, if any is worth writing.
- **No comparison against another tool.** A memory benchmark that ranks two
  tools measures the person who wrote it.
- **No heap-dump analysis and no profiler dependency.** `MemoryMXBean` and
  `ThreadMXBean` are in the JDK and answer both questions.
- **Not in the test lane and not in coverage**, like every other benchmark.
- **No budget enforced yet.** A gate that fails a build on a footprint
  regression is the last stack entry and is recommended *against* for now; see
  below.

## Shape

```bash
./gradlew :benchmarks:footprint
```

```
users     retained     per user   per sample   allocated/departure   peak heap
1,000        14 MB      14.0 KB       41 B                  312 B       28 MB
10,000       98 MB       9.8 KB       39 B                  308 B      141 MB
50,000      452 MB       9.0 KB       38 B                  311 B      690 MB
```

Two measurements, taken differently because they answer different questions:

- **Retained** — the live set after the run, read off `MemoryMXBean` following a
  full collection, minus the same reading taken before the run started. Divided
  by users and by recorded samples, so the two things that scale are separated.
- **Allocated per departure** — `com.sun.management.ThreadMXBean
  .getThreadAllocatedBytes` summed across the injector's threads, divided by
  departures. This is the number that can be wrong in a way that changes a
  reported latency, and the one worth watching flat as the rate climbs.

The output is a table in `build/reports/kestrel/footprint.md`, beside the
ceiling's, and the run's own `fellBehind` printed next to it — a footprint taken
from a run that lost its schedule is measuring a backlog, not a design.

## Why this shape

Retained-after-GC rather than peak: peak is the collector's scheduling as much
as the program's, and it moves between JDK builds for reasons that are nobody's
design. Peak is printed anyway, because it is what an operator sizing a
container actually has to provision, and a spec that reported only the honest
number would be ignored in favour of one that answered the question asked.

The alternative shape is a heap dump per rung, parsed for the top retainers,
which would say *what* is holding the memory rather than only how much. It is
better information and is not recommended here: it needs a parser or a tool
outside the JDK, and the first question — is the footprint a problem at all — is
answered by two numbers and a division.

## Stack

- [ ] **`spec-0093-retained`** — the harness, the forced-collection reading, and
      the per-user and per-sample columns over the ceiling's own rung ladder.
      Done when: `./gradlew :benchmarks:footprint` writes the table, and a run
      that fell behind is labelled rather than averaged in.
- [ ] **`spec-0093-allocation`** — allocated bytes per departure off
      `ThreadMXBean`, across the injector's threads.
      Done when: the column is flat within a few percent across three rungs an
      order of magnitude apart, or the spec that says why it is not gets
      written.
- [ ] **`spec-0093-claims`** — the three comments that assert a memory cost
      (0003, 0028, 0073) amended to cite the measured figure or to drop the
      claim.
      Done when: no comment in the tree argues from an unmeasured footprint.
- [ ] **`spec-0093-docs`** — `docs/what-it-costs.md` gains the table beside the
      ceiling's, with the machine it was taken on.
      Done when: the page states both numbers and the JDK that produced them.

## Acceptance

```bash
./gradlew :benchmarks:footprint
cat build/reports/kestrel/footprint.md
./gradlew build   # unchanged: the harness is not in the test lane
```

## Open questions

> Answered 2026-09-04, each on the recommendation in its own bullet. The
> reasoning is left standing rather than deleted: a decision is easier to
> reopen when the alternative it beat is still written down.

- **Does the footprint sweep reuse the ceiling's rungs or its own?** Recommend
  its own, shorter ladder. The ceiling climbs until it breaks; a footprint wants
  three rungs that all kept their schedule, because the interesting number is
  what a working run holds.
- **Is there a budget gate?** Recommend not yet. A threshold invented before the
  first measurement is a number somebody will tune the build to rather than the
  code, and 0042's lesson is that a machine-sensitive gate needs the machine
  measured first. Revisit once three runs on CI agree.
- **Does this measure the HTTP module or a socket-free action?** Recommend the
  socket-free action, as 0011 does: a connection pool's buffers are the JDK
  client's footprint, and mixing them in means neither number can be read.

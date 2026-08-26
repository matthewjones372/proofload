# What this tool costs

Kestrel measures a target, so its own overhead is part of whether its numbers
mean anything. This is that overhead, measured rather than claimed.

The harness is `./gradlew :benchmarks:ceiling`. Each row runs a scenario whose
only step returns immediately, so nothing sits between the departure the
profile promised and the sample the recorder took except this tool: its
scheduler, its virtual threads and its histograms.

| Rate | Requests | Behind p50 | Behind p99 | Behind max | p50 within 1ms | fellBehind() |
|---:|---:|---:|---:|---:|:---:|:---:|
| 100 | 500 | 2.080767ms | 2.703359ms | 9.764863ms | no | yes |
| 250 | 1,250 | 843.775us | 2.244607ms | 5.079039ms | yes | yes |
| 500 | 2,500 | 434.175us | 9.043967ms | 46.923775ms | yes | yes |
| 1,000 | 5,000 | 216.063us | 12.255231ms | 62.128127ms | yes | yes |
| 5,000 | 25,000 | 49.919us | 1.843199ms | 53.477375ms | yes | yes |
| 10,000 | 50,000 | 29.439us | 835.583us | 7.602175ms | yes | yes |
| 25,000 | 125,000 | 17.023us | 1.261567ms | 6.127615ms | yes | yes |
| 50,000 | 250,000 | 12.735us | 1.187839ms | 7.733247ms | yes | yes |
| 100,000 | 500,000 | 11.263us | 45.350911ms | 62.652415ms | yes | yes |

Ceiling: **100,000 a second** on this machine.

Measured on Mac OS X aarch64, 8 processors, JDK 21.0.9.

## What the numbers say

**The median is the ceiling.** Once the code is warm, a departure leaves within
a few tens of microseconds of when it was due, and that holds to a hundred
thousand a second on eight cores. There is no rate in this table where the
generator is systematically failing to keep up.

**The p99 column is not the same measurement.** It does not scale with rate — a
run at a hundred a second has the same multi-millisecond worst cases as one at a
hundred thousand — so what it captures is occasional stalls on the machine
rather than a backlog. One run of each rate is not enough to characterise a
tail, and this document does not pretend otherwise.

That reading is now a measurement rather than an inference. Every run carries
`result.hiccups`, the distribution of how much later than it was due each tick
of a one-millisecond schedule arrived on the injector's own JVM, and the reports
print it beside the backlog. Where the two are the same size, the p99 column
above is this machine.

**The first rows are the coldest.** The low rates send the fewest requests, so
they get the least JIT, and their medians are worse than rates ten times higher.
Read the table as a shape rather than a ranking.

## What this found

The first run of this harness reported two to three milliseconds of median
lateness at every rate, including a hundred a second on an idle machine. That
was a real bug and not a measurement artefact: `ScheduledExecutorService`
schedules a delay from *now*, and a departure is an offset from the run's
start, so every user inherited however long the run had spent booking the users
before it. The offsets were computed from an index precisely to avoid drift,
and then handed over in a way that reintroduced it.

Subtracting the elapsed time at booking took the median at a hundred thousand a
second from 45 ms to 10 µs. `ScheduleDriftTest` in `kestrel-engine` is the
regression test.

## Comparing two runs

A baseline taken from a cold JVM will make the next release look like an
improvement. The first run in a process pays for class loading, JIT and opening
connections, and in this repository's own regression test it measured a p99 of
327 ms where every run after it measured 28 ms — a tenfold difference with no
change to the target at all.

Discard a run before keeping one. `kestrel-baseline` does not do this for you,
because a tool that quietly threw away the first run of a two-run session would
be deciding which measurements count.

The runner is the other half of this. A baseline records the machine it was
measured on, and a comparison across two of them carries a caveat saying every
delta may be the runner rather than the service — on shared CI that is most
runs, not an edge case. It is a warning rather than a refusal; a plan that
differs is the refusal.

## What is not measured here

- **No socket.** A real target's latency would dominate, and the question here
  is what the parts a user cannot swap out cost.
- **No comparison** with Gatling, k6 or anything else. A benchmark that ranks
  two tools measures whoever wrote it.
- **One machine, one run each.** The figures above are a shape on the hardware
  named in the table, not a promise about yours. Run it on yours.

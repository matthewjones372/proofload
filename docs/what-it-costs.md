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

The same machinery answers the question this table cannot: `calibrate()` runs a
null step on a fixed schedule several times over and reports how far apart the
repeats landed, as a fraction. That is the smallest difference this machine can
tell apart at all, and it is measured on the machine rather than read off a
table written on somebody else's.

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

## What the recorder keeps

Memory is the other overhead. A `Histogram` is a table of counters — 5,377
longs, 43,016 bytes, and 43,681 bytes retained once the objects around it are
counted — and a run keeps four per step: service time and response time, each
split into the requests that worked and the requests that did not, plus one for
the generator's own lateness. That is fixed per step and does not grow with the
number of requests.

A recorder is kept per shard and the default shard count is
`availableProcessors`, so a ten-step scenario on the eight-core machine above
holds forty histograms eight times over: **14.1 MiB**, measured by holding the
recorders and reading the heap either side. Splitting a step into the two sides
doubled that from roughly 7 MiB, and cost nothing on the timed path — a sample
is still two counter increments, and the histograms describing the whole step
are merged once, at freeze.

`result.timeline` keeps one more per second per step, and that is the part that
would grow: ten minutes of a three-step scenario is 1,800 of them, which at the
full precision above is over seventy megabytes of counters for three line
charts. A second's histogram is therefore coarse — thirty-two sub-buckets
rather than two hundred and fifty-six, 5,384 bytes — with each second's
percentile good to 6.25% instead of 0.78%.

A second splits its two sides the way a step does, and the table for what
failed is allocated the first time something in that second does — so a second
nothing failed in, which is most seconds of most runs, costs exactly what it
cost before the split.

A second carries both clocks, because the steady segment is rebuilt from the
timeline and response time is the clock a percentile goal reads by default: a
segment without it narrows the statistic nobody wrote a goal on. The two tables
for one side are allocated together, since a request with a service time in a
second has a response time in it too.

That doubles the timeline, and the figure is measured rather than reasoned
about — `./gradlew :benchmarks:timelineCost` holds the recorder and reads the
heap either side. On the run that costs the most, an hour of ten steps at
twenty requests a second, which is 36,000 seconds of tables:

| | one clock | both |
|---|---|---|
| recording | 190.4 MiB | 377.8 MiB |
| recorder and frozen seconds | 221.5 MiB | 439.1 MiB |
| per second of step, recording | 5,546 bytes | 11,005 bytes |

Ten minutes of three steps — 1,800 seconds of tables — is 19 MiB of that, which
is the case to hold in mind rather than the soak. If the soak figure ever stops
being affordable, the fallback to argue is a timeline that keeps response time
only while a response-time goal exists to need it, which the plan knows before
the run starts.

Freezing a second keeps the buckets that counted something and drops the rest —
tens of them for a second of load, against the 673 the table has slots for.
They are kept rather than only the percentiles read off them because a stretch
of the run, the steady segment among them, has to be added up from buckets: a
p99 over forty seconds is not something that can be recovered from forty p99s.

The summary is still read from the full histograms, so nothing on the page
above the timeline is coarser than it was. `CoarseHistogramTest` asserts both
sizes; neither is an estimate.

Each frozen second keeps the buckets it counted in and not only its
percentiles, because merging several runs' seconds means adding the buckets and
reading the percentiles off the sum — `Runs.merged` has no other honest way to
answer. Only the non-empty buckets survive the freeze, so a second holds one
per distinct latency it saw rather than the 672 slots the coarse table
reserves, and the counter tables go with the recorder that owned them.

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

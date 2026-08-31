# What this tool costs

Kestrel measures a target, so its own overhead is part of whether its numbers
mean anything. This is that overhead, measured rather than claimed.

`./gradlew :benchmarks:ceiling` runs two sweeps, and this page carries both:

- **Over a socket — at least 2,500 a second.** The shipped `kestrel-http` step
  against a target on loopback. A **lower bound**: the target's own service
  time is inside it.
- **Without a socket — 100,000 a second.** A step that returns immediately, so
  the row is this tool and nothing else. An upper bound, on a path nobody runs.

Both come from one sweep on **Linux amd64, 4 processors, JDK 21.0.10**, under a
one-minute load average of **5.97 to 9.98** across the sweep. That is a shared
machine with other work on it, and it is what these figures describe. A quiet
machine would give a larger number; quoting that one as general is the thing
this page exists not to do.

A rate kept its schedule when the median departure left within a millisecond of
when it was due. The same rule decides both tables, so the two can be read
against each other.

## Over a socket

The step a user writes, sent the way a user sends it: one blocking `send` per
virtual thread through the pooled shared `HttpClient` that `kestrel-http`
builds, at a `com.sun.net.httpserver` target answering from memory in this same
process. Generator and target share the four cores, which is what a laptop run
and a single CI runner both look like.

| Rate | Requests | Failed | Failed as | Behind p50 | Behind p99 | Behind max | Served p50 | Served p99 | p50 within 1ms | fellBehind() |
|---:|---:|---:|:---|---:|---:|---:|---:|---:|:---:|:---:|
| 100 | 500 | 0 | — | 168.959us | 3.948543ms | 24.772607ms | 696.319us | 2.441215ms | yes | yes |
| 250 | 1,250 | 0 | — | 173.055us | 1.761279ms | 36.438015ms | 165.887us | 456.703us | yes | yes |
| 500 | 2,500 | 0 | — | 143.359us | 3.309567ms | 15.073279ms | 128.511us | 909.311us | yes | yes |
| 1,000 | 5,000 | 0 | — | 149.503us | 411.647us | 8.388607ms | 86.527us | 224.255us | yes | yes |
| 2,500 | 12,500 | 0 | — | 115.711us | 1.269759ms | 11.141119ms | 62.975us | 282.623us | yes | yes |
| 5,000 | 25,000 | 1 | IOException 1 | 171.007us | 16.318463ms | 28.049407ms | 28.799us | 700.415us | yes | yes |
| 10,000 | 50,000 | 29,568 | IOException 17,058; timeout 12,510 | 2.703359ms | 75.497471ms | 94.896127ms | 20.479us | 1.011711ms | no | no |

Ceiling: **at least 2,500 a second**, on the machine named above.

### Why "at least"

`com.sun.net.httpserver` is not a fast server, and this sweep did not
characterise it. So a rate the sweep failed to reach may be the target's limit
rather than the client's, and the honest reading is that Kestrel's HTTP step
reaches *at least* this rate — not that it stops here. Making it an equality
would mean measuring the server too, which is a second project about something
nobody ships.

The `Served` columns are the evidence there is, and they are about the handler
only. The target's own service time did not climb with the rate: 696 µs a
request at a hundred a second, falling to 20 µs at ten thousand, with a p99
between 224 µs and 2.4 ms across every row. A handler that stayed flat while
the generator's lateness went from 116 µs to 2.7 ms is a handler that was not
what the sweep ran into.

What a handler time cannot see is the connection path in front of it. That is
where this sweep ended: at ten thousand a second, 29,568 of 50,000 requests
never reached a handler at all — refused, dropped, or unanswered inside the
step's thirty-second timeout. Whether that is the client, the loopback stack or
the server's accept path is not something this sweep separates, which is the
other half of why the number is a bound.

The pick is deliberately conservative. Five thousand a second kept the budget —
a median departure 171 µs late — and is still not the ceiling, because one
request in twenty-five thousand failed. A refused request is not a request this
tool sent at the rate it promised, and a lower bound is the right place to be
strict about that.

### What this sweep excluded

- **No network.** Loopback only. A ceiling measured across a LAN measures the
  LAN.
- **No tuning.** The client exactly as `kestrel-http` ships it — pooled,
  shared, no executor swap, no connection-pool flags. The question is what a
  user gets, not what is achievable.
- **No characterisation of the target.** That is what makes this a bound rather
  than a measurement of the client.
- **No cores of its own.** The generator and the target shared all four.

## Without a socket

A scenario whose only step returns immediately, so nothing sits between the
departure the profile promised and the sample the recorder took except this
tool: its scheduler, its virtual threads and its histograms.

| Rate | Requests | Behind p50 | Behind p99 | Behind max | p50 within 1ms | fellBehind() |
|---:|---:|---:|---:|---:|:---:|:---:|
| 100 | 500 | 292.863us | 1.712127ms | 9.961471ms | yes | yes |
| 250 | 1,250 | 150.527us | 327.679us | 3.997695ms | yes | yes |
| 500 | 2,500 | 143.359us | 399.359us | 5.177343ms | yes | yes |
| 1,000 | 5,000 | 127.999us | 3.047423ms | 10.092543ms | yes | yes |
| 5,000 | 25,000 | 107.007us | 602.111us | 16.646143ms | yes | yes |
| 10,000 | 50,000 | 112.639us | 3.522559ms | 26.607615ms | yes | yes |
| 25,000 | 125,000 | 83.455us | 26.869759ms | 71.827455ms | yes | yes |
| 50,000 | 250,000 | 80.383us | 83.886079ms | 110.624767ms | yes | yes |
| 100,000 | 500,000 | 90.623us | 320.864255ms | 329.252863ms | yes | yes |

Ceiling: **100,000 a second**, on the same machine and the same sweep.

### What this sweep excluded

- **The socket, which is everything a real target adds.** A target's latency
  would dominate, and the question here is what the parts a user cannot swap
  out cost. Nobody runs a step that touches nothing, so this is an upper bound
  and the table above is the one to plan against.

## What the numbers say

**The median is the ceiling.** Once the code is warm, a departure leaves within
a couple of hundred microseconds of when it was due, and without a socket that
holds to a hundred thousand a second on four loaded cores. There is no rate in
that table where the generator is systematically failing to keep up. Over a
socket the median holds to five thousand a second and gives way between there
and ten thousand.

**The first rows are the coldest.** The low rates send the fewest requests, so
they get the least JIT, and their medians are worse than rates a thousand times
higher — 293 µs at a hundred a second against 91 µs at a hundred thousand. Read
each table as a shape rather than a ranking.

**The p99 column is not the same measurement.** Below ten thousand a second it
does not follow the rate at all: 1.7 ms at a hundred, 328 µs at two hundred and
fifty, 3 ms at a thousand, 602 µs at five thousand. Above that it climbs — 27
ms, 84 ms, 321 ms — on a machine carrying a load average near ten while it
measured. One run of each rate cannot say how much of that is backlog and how
much is the machine, and this document does not pretend otherwise.

That reading is a measurement rather than an inference. Every run carries
`result.hiccups`, the distribution of how much later than it was due each tick
of a one-millisecond schedule arrived on the injector's own JVM, and the reports
print it beside the backlog. Where the two are the same size, the p99 column
above is this machine.

The same machinery answers the question these tables cannot: `calibrate()` runs
a null step on a fixed schedule several times over and reports how far apart the
repeats landed, as a fraction. That is the smallest difference this machine can
tell apart at all, and it is measured on the machine rather than read off a
table written on somebody else's.

## What this found

The first run of this harness reported two to three milliseconds of median
lateness at every rate, including a hundred a second on an idle machine. That
was a real bug and not a measurement artefact: `ScheduledExecutorService`
schedules a delay from *now*, and a departure is an offset from the run's
start, so every user inherited however long the run had spent booking the users
before it. The offsets were computed from an index precisely to avoid drift,
and then handed over in a way that reintroduced it.

Subtracting the elapsed time at booking took the median at a hundred thousand a
second from 45 ms to 10 µs. Those two figures are from the machine the bug was
found on, not the one in the tables above. `ScheduleDriftTest` in
`kestrel-engine` is the regression test.

## What the recorder keeps

Memory is the other overhead. The figures in this section were measured on Mac
OS X aarch64, 8 processors, JDK 21.0.9, which is not the machine the tables
above were run on.

A `Histogram` is a table of counters — 5,377 longs, 43,016 bytes, and 43,681
bytes retained once the objects around it are counted — and a run keeps four per
step: service time and response time, each split into the requests that worked
and the requests that did not, plus one for the generator's own lateness. That
is fixed per step and does not grow with the number of requests.

A recorder is kept per shard and the default shard count is
`availableProcessors`, so a ten-step scenario on that eight-core machine holds
forty histograms eight times over: **14.1 MiB**, measured by holding the
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

- **No comparison** with Gatling, k6 or anything else. A benchmark that ranks
  two tools measures whoever wrote it.
- **One machine, one run each, and a busy machine.** The load average beside
  the tables is part of the figures, not an apology for them: this is what the
  tool did on four cores that were already carrying other work. It is a shape
  on that hardware, not a promise about yours.
- **Nothing is in `./gradlew build`.** The sweeps are benchmarks, they want a
  machine to themselves, and `AGENTS.md` keeps a benchmark of the tool out of
  the tests of the tool and out of the coverage denominator. Run them on
  purpose, on your own machine, and read your own numbers.

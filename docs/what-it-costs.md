# What this tool costs

Proofload measures a target, so its own overhead is part of whether its numbers
mean anything. This is that overhead, measured rather than claimed.

`./gradlew :benchmarks:ceiling` runs two sweeps, and this page carries both:

- **Over a socket: at least 2,500 a second.** The shipped `proofload-http` step
  against a target on loopback. A **lower bound**: the target's own service
  time is inside it.
- **Without a socket: 100,000 a second.** A step that returns immediately, so
  the row is this tool and nothing else. An upper bound, on a path nobody runs.

`./gradlew :benchmarks:kafkaCeiling` runs a third, over the Kafka adapter with
the broker taken out; it is [below](#the-kafka-adapter) and its number is not
comparable to either of the two above without reading what is missing from it.

Both come from one sweep on **Linux amd64, 4 processors, JDK 21.0.10**, under a
one-minute load average of **0.41 to 1.29** across the sweep. That is a quiet
machine, and it is what these figures describe. A busier one gives smaller
numbers. An earlier sweep of this page ran at a load average near six and
reached the same ceiling with far worse tails, so quoting either as general is
the thing this page exists not to do.

A rate kept its schedule when the median departure left within a millisecond of
when it was due. The same rule decides both tables, so the two can be read
against each other.

## What has a number, and what has none

Proofload ships steps over more transports than this page has swept. A document
about what the tool costs should say which of them carry a number before it
shows one.

| Path | Measured | Where, and what the number is |
|---|---|:---|
| the engine alone, no socket | yes | `:benchmarks:ceiling`: 100,000/s, an upper bound on a path nobody runs |
| `proofload-http`, a request per step | yes | `:benchmarks:ceiling`: at least 2,500/s, a lower bound: loopback, target in this JVM |
| `proofload-kafka` | partly | `:benchmarks:kafkaCeiling`: the adapter, with the broker, the accumulator and the sender thread taken out |
| what a run retains | yes | `:benchmarks:footprint` and `:benchmarks:timelineCost`, on a different machine, named where those figures are |
| `proofload-http` server-sent events | **no** | nothing sweeps a stream held open |
| `proofload-websocket` | **no** | nothing sweeps it |
| `proofload-grpc`, `proofload-grpc-dynamic` | **no** | nothing sweeps them |
| `proofload-jdbc` | **no** | nothing sweeps it, and a pool wait is the interesting part |

**"No" means nobody measured it, not that it is slow.** An unswept adapter still
reports `behind`, `hiccups` and `fellBehind()` on every run it is used in, so a
user is not flying blind. What is missing is the sweep that says at which rate
that verdict starts turning over, which is the thing only a benchmark can say.

**Every rate on this page was measured against a target that answers
immediately.** By Little's law that puts fewer than one user in flight, so all of
them are rates at near-zero concurrency, and none says what this tool costs while
it holds thousands of users open. That is a gap, not a subtlety.

## Over a socket

The step a user writes, sent the way a user sends it: one blocking `send` per
virtual thread through the pooled shared `HttpClient` that `proofload-http`
builds, at a `com.sun.net.httpserver` target answering from memory in this same
process. Generator and target share the four cores, which is what a laptop run
and a single CI runner both look like.

| Rate | Requests | Failed | Failed as | Behind p50 | Behind p99 | Behind max | Served p50 | Served p99 | Files | Ports | p50 within 1ms | fellBehind() |
|---:|---:|---:|:---|---:|---:|---:|---:|---:|---:|---:|:---:|:---:|
| 100 | 500 | 0 | — | 216.063us | 1.916927ms | 38.273023ms | 190.463us | 376.831us | 54 / 20,000 | 46 / 28,232 | yes | yes |
| 250 | 1,250 | 0 | — | 179.199us | 25.034751ms | 47.972351ms | 124.927us | 250.879us | 41 / 20,000 | 66 / 28,232 | yes | yes |
| 500 | 2,500 | 0 | — | 154.623us | 460.799us | 7.077887ms | 88.575us | 175.103us | 73 / 20,000 | 110 / 28,232 | yes | yes |
| 1,000 | 5,000 | 0 | — | 134.143us | 835.583us | 9.961471ms | 62.463us | 151.551us | 129 / 20,000 | 191 / 28,232 | yes | yes |
| 2,500 | 12,500 | 0 | — | 91.135us | 366.591us | 3.719167ms | 36.863us | 115.199us | 263 / 20,000 | 365 / 28,232 | yes | no |
| 5,000 | 25,000 | 3 | IOException 3 | 100.863us | 5.603327ms | 11.468799ms | 32.255us | 266.239us | 417 / 20,000 | 4,734 / 28,232 | yes | yes |
| 10,000 | 50,000 | 51 | IOException 51 | 126.975us | 6.750207ms | 11.206655ms | 17.535us | 370.687us | 991 / 20,000 | 21,317 / 28,232 | yes | yes |

Ceiling: **at least 2,500 a second**, on the machine named above.

**Files** and **Ports** are this process's own ceilings, sampled once a second
while each row ran: open descriptors against this JVM's limit, and sockets in
TIME_WAIT against the ephemeral port range. They are what turns a failure count
from a mystery into an attribution; see below. The port reading is machine-wide,
because `tw` counts every socket on the host, so it is read against a range that
is machine-wide too.

### What the last two columns mean

They disagree at every rate, and the disagreement is the point rather than a
mistake in one of them.

**`p50 within 1ms`** is the rule this page picks a ceiling by: the median
departure left within a millisecond of when it was due. It asks whether the
generator was, in the ordinary case, on time.

**`fellBehind()`** is what the library reports on a run, and it asks a different
question: whether the injector's *p99* lateness is more than `MATERIAL`, a
twentieth, of the **worst** step's response-time p99. Against a target
answering in hundreds of microseconds that threshold is itself microseconds, so
a run with any lateness at all trips it. Reading a `yes` there as "the tool
cannot manage a hundred a second" is exactly backwards: it says the generator's
own lateness is large enough to be visible beside the number being reported,
which at these latencies it nearly always is.

It is a whole-run verdict read off one step, so in a mix of a slow step and a
fast one it answers for the slow one. `RunResult.kt` states the threshold in one
place and 0097 argues for the number.

Both are in the table because a reader deserves to see the strict test rather
than have this page quietly pick the flattering one. It says `no` at exactly one
rate here, 2,500 a second, the ceiling, and `yes` everywhere else, which is
what a quiet machine looks like at this precision.

### Why "at least"

`com.sun.net.httpserver` is not a fast server, and this sweep did not
characterise it. So a rate the sweep failed to reach may be the target's limit
rather than the client's, and the honest reading is that Proofload's HTTP step
reaches *at least* this rate, not that it stops here. Making it an equality
would mean measuring the server too, which is a second project about something
nobody ships.

The `Served` columns are the evidence there is, and they are about the handler
only. The target's own service time did not climb with the rate: 696 µs a
request at a hundred a second, falling to 17 µs at ten thousand, with a p99
between 115 µs and 377 µs across every row. A handler that got *faster* as the
rate climbed is a handler that was not what the sweep ran into.

What a handler time cannot see is the connection path in front of it, and that
is where this sweep ended: 3 requests failed at five thousand a second and 51 at
ten thousand, refused or dropped before a handler ran. The Files and Ports
columns say which end ran out. At ten thousand a second this process held 991
descriptors of the 20,000 it was allowed, five per cent, nowhere near, while
sockets in TIME_WAIT reached 21,317 of a 28,232-port range, three quarters of
it, up from 4,734 at half the rate. The failures at this end are ephemeral ports
recycling too slowly, not descriptors, and not the target refusing work.

That is a better answer than this page could give before, and it is not a
complete one: the port figure is machine-wide, so it cannot prove every one of
those sockets was Proofload's, and it does not rule out the server's accept path
failing at the same time. It narrows the question from "the client, the loopback
stack or the server" to "port exhaustion at this end, or the accept path at the
other".

### Where it breaks, and what that does not tell you

The sweep stops at rates it can send, so it does not show where the shipped
path breaks. Pushed past its published rates on this machine it breaks between
ten and twenty-five thousand a second, and *which resource runs out depends on
how the machine is configured*:

| Rate | Requests | Failed | Served p99 | Files | Ports |
|---:|---:|---:|---:|---:|---:|
| 10,000 | 50,000 | 27 | 397.311us | 486 / 20,000 | 20,630 / 28,232 |
| 25,000 | 125,000 | 111,989 | 303.103us | 94 / 20,000 | 34,666 / 28,232 |
| 50,000 | 250,000 | 229,000 | 313.343us | 160 / 20,000 | 52,288 / 28,232 |

As shipped it is ephemeral ports: sockets in TIME_WAIT go past the whole range
while descriptors stay under one per cent of their limit. Told to hold its
connections open instead, with a wider idle-connection cap on the target, the same
rates exhaust *descriptors* rather than ports, at 14,589 of 20,000, and fail
every request. Pinned to HTTP/1.1 rather than negotiating, they exhaust
descriptors too, at 19,999 of 20,000.

Three configurations, three different resources, the same wall between ten and
twenty-five thousand. That is what a saturated arrangement looks like, not a
single bottleneck with a name.

**What this cannot say is whether the client or the target ran out first**, and
it is worth being plain about why, because the served columns look like they
answer it. They do not. `Served p99` is the time inside the target's handler:
it stays near three hundred microseconds at every rate above, which says
handler execution is not the limit, and says nothing about the target's accept
path, its connection handling, or the cores it is taking from the generator to
do any of it. `com.sun.net.httpserver` runs in this same JVM on these same four
cores and is not a server anyone tunes.

Separating the two needs a target that is not competing with the generator for
the machine: a real server, on other hardware, over a network this sweep
deliberately excludes. Until then the number stays a lower bound for the reason
it always was, and the honest reading of the rows above is that this
arrangement saturates, not that the JDK client does.

The consequence for anyone thinking of a faster client: `proofload-http`'s
transport seam makes one easy to write, and this measurement is not a reason to.
It is not evidence the client is slow. Getting evidence either way means
measuring against a target that is not in the way.

The pick is deliberately conservative. Five thousand a second kept the budget,
a median departure 101 µs late, and is still not the ceiling, because three
requests in twenty-five thousand failed. A refused request is not a request this
tool sent at the rate it promised, and a lower bound is the right place to be
strict about that.

### What this sweep excluded

- **No network.** Loopback only. A ceiling measured across a LAN measures the
  LAN.
- **No tuning.** The client exactly as `proofload-http` ships it: pooled,
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
| 100 | 500 | 325.631us | 8.159231ms | 28.180479ms | yes | yes |
| 250 | 1,250 | 251.903us | 2.162687ms | 32.243711ms | yes | yes |
| 500 | 2,500 | 189.439us | 12.255231ms | 45.350911ms | yes | yes |
| 1,000 | 5,000 | 182.271us | 5.505023ms | 26.869759ms | yes | yes |
| 5,000 | 25,000 | 83.455us | 203.775us | 12.189695ms | yes | yes |
| 10,000 | 50,000 | 77.823us | 212.991us | 14.417919ms | yes | yes |
| 25,000 | 125,000 | 71.679us | 164.863us | 20.971519ms | yes | yes |
| 50,000 | 250,000 | 63.487us | 30.932991ms | 71.303167ms | yes | yes |
| 100,000 | 500,000 | 61.439us | 90.177535ms | 119.537663ms | yes | yes |

Ceiling: **100,000 a second**, on the same machine and the same sweep.

### What this sweep excluded

- **The socket, which is everything a real target adds.** A target's latency
  would dominate, and the question here is what the parts a user cannot swap
  out cost. Nobody runs a step that touches nothing, so this is an upper bound
  and the table above is the one to plan against.

## What the numbers say

**The median is the ceiling.** Once the code is warm, a departure leaves within
a couple of hundred microseconds of when it was due, and without a socket that
holds to a hundred thousand a second on four cores. There is no rate in
that table where the generator is systematically failing to keep up. Over a
socket the median holds to five thousand a second and gives way between there
and ten thousand.

**The first rows are the coldest.** The low rates send the fewest requests, so
they get the least JIT, and their medians are worse than rates a thousand times
higher: 326 µs at a hundred a second against 61 µs at a hundred thousand. Read
each table as a shape rather than a ranking.

**The p99 column is not the same measurement.** Below twenty-five thousand a
second it does not follow the rate at all: 8.2 ms at a hundred, 2.2 ms at two
hundred and fifty, 12 ms at five hundred, and then 204 µs at five thousand.
Above that it climbs: 31 ms at fifty thousand, 90 ms at a hundred thousand.
One run of each rate cannot say how much of that is backlog and how much is the
machine, and this document does not pretend otherwise.

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
`proofload-engine` is the regression test.

## What the recorder keeps

Memory is the other overhead. The figures in this section were measured on Mac
OS X aarch64, 8 processors, JDK 21.0.9, which is not the machine the tables
above were run on.

A `Histogram` is a table of counters (5,377 longs, 43,016 bytes, and 43,681
bytes retained once the objects around it are counted) and a run keeps four per
step: service time and response time, each split into the requests that worked
and the requests that did not, plus one for the generator's own lateness. That
is fixed per step and does not grow with the number of requests.

A recorder is kept per shard and the default shard count is
`availableProcessors`, so a ten-step scenario on that eight-core machine holds
forty histograms eight times over: **14.1 MiB**, measured by holding the
recorders and reading the heap either side. Splitting a step into the two sides
doubled that from roughly 7 MiB, and cost nothing on the timed path. A sample
is still two counter increments, and the histograms describing the whole step
are merged once, at freeze.

`result.timeline` keeps one more per second per step, and that is the part that
would grow: ten minutes of a three-step scenario is 1,800 of them, which at the
full precision above is over seventy megabytes of counters for three line
charts. A second's histogram is therefore coarse, thirty-two sub-buckets
rather than two hundred and fifty-six at 5,384 bytes, with each second's
percentile good to 6.25% instead of 0.78%.

A second splits its two sides the way a step does, and the table for what
failed is allocated the first time something in that second does, so a second
nothing failed in, which is most seconds of most runs, costs exactly what it
cost before the split.

A second carries both clocks, because the steady segment is rebuilt from the
timeline and response time is the clock a percentile goal reads by default: a
segment without it narrows the statistic nobody wrote a goal on. The two tables
for one side are allocated together, since a request with a service time in a
second has a response time in it too.

That doubles the timeline, and the figure is measured rather than reasoned
about. `./gradlew :benchmarks:timelineCost` holds the recorder and reads the
heap either side. On the run that costs the most, an hour of ten steps at
twenty requests a second, which is 36,000 seconds of tables:

| | one clock | both |
|---|---|---|
| recording | 190.4 MiB | 377.8 MiB |
| recorder and frozen seconds | 221.5 MiB | 439.1 MiB |
| per second of step, recording | 5,546 bytes | 11,005 bytes |

Ten minutes of three steps, 1,800 seconds of tables, is 19 MiB of that, which
is the case to hold in mind rather than the soak. If the soak figure ever stops
being affordable, the fallback to argue is a timeline that keeps response time
only while a response-time goal exists to need it, which the plan knows before
the run starts.

Freezing a second keeps the buckets that counted something and drops the rest,
tens of them for a second of load, against the 673 the table has slots for.
They are kept rather than only the percentiles read off them because a stretch
of the run, the steady segment among them, has to be added up from buckets: a
p99 over forty seconds is not something that can be recovered from forty p99s.

The summary is still read from the full histograms, so nothing on the page
above the timeline is coarser than it was. `CoarseHistogramTest` asserts both
sizes; neither is an estimate.

Each frozen second keeps the buckets it counted in and not only its
percentiles, because merging several runs' seconds means adding the buckets and
reading the percentiles off the sum. `Runs.merged` has no other honest way to
answer. Only the non-empty buckets survive the freeze, so a second holds one
per distinct latency it saw rather than the 672 slots the coarse table
reserves, and the counter tables go with the recorder that owned them.

## What a run holds

```bash
./gradlew :benchmarks:footprint
```

The other half of what this tool costs. The ceiling sweeps ask where the
schedule breaks and hand the JVM `-Xmx2g` so they never have to ask where the
heap does; this asks the second question and takes the JVM's default.

| users | retained | per user | per sample | allocated/departure | peak heap | kept schedule |
|---|---|---|---|---|---|---|
| 1,000 | 108.0 KB | 110 B | 110 B | 8.2 KB | 5.3 MB | **no** |
| 10,000 | 82.5 KB | 8 B | 8 B | 1.1 KB | 5.5 MB | yes |
| 50,000 | 137.6 KB | 2 B | 2 B | 508 B | 46.9 MB | yes |

8 cores, JDK 21.0.9+10-LTS, macOS aarch64, a step that touches no socket.

**Retained does not grow with the run.** Fifty times the users left the same
hundred kilobytes or so behind, and the per-user column falling from 110 B to
2 B is that number being fixed rather than per-user. It is close enough to the
noise that the honest reading is "too small to measure this way", which is
itself the answer: nothing in a result grows per sample, because a histogram is
a counter table and not a list. That was [0003](../specs/0003-what-a-run-measured.md)'s
argument, and it had never been checked.

**Allocation per departure falls as the rate climbs**, from 8.2 KB to 508 B.
[0093](../specs/0093-what-a-run-holds.md) expected it flat within a few percent
and it is not: what these rows mostly measure is the fixed allocation of
starting a run, spread over more departures each time. The marginal cost of one
departure is somewhere below the smallest figure here, and pinning it needs a
longer window rather than a wider one.

**The thousand-user row did not keep its schedule** and its numbers are
therefore about a backlog. Two seconds of warm-up at a hundred a second is two
hundred departures, which is not enough to have finished compiling; the row is
left in rather than tuned away, because a table that quietly dropped the
inconvenient rung would be worth less than one that shows it.

**Peak heap is what an operator provisions**: 47 MB at fifty thousand users,
against the 2 GB the ceiling harness gives itself.

## Comparing two runs

A baseline taken from a cold JVM will make the next release look like an
improvement. The first run in a process pays for class loading, JIT and opening
connections, and in this repository's own regression test it measured a p99 of
327 ms where every run after it measured 28 ms, a tenfold difference with no
change to the target at all.

Discard a run before keeping one. `proofload-baseline` does not do this for you,
because a tool that quietly threw away the first run of a two-run session would
be deciding which measurements count.

The runner is the other half of this. A baseline records the machine it was
measured on, and a comparison across two of them carries a caveat saying every
delta may be the runner rather than the service. On shared CI that is most
runs, not an edge case. It is a warning rather than a refusal; a plan that
differs is the refusal.

## The Kafka adapter

`./gradlew :benchmarks:kafkaCeiling`. An `emit` producing through a producer
that answers immediately and keeps nothing, so what is left between the
departure the profile promised and the sample the recorder took is what
`proofload-kafka` adds: the serializer lambda, the record, the correlation
header, and waiting on the send's future.

| Rate | Records | Failed | Behind p50 | Behind p99 | Behind max | p50 within 1ms |
|---:|---:|---:|---:|---:|---:|:---:|
| 1,000 | 5,000 | 0 | 270.335us | 4.784127ms | 27.525119ms | yes |
| 5,000 | 25,000 | 0 | 91.135us | 32.767999ms | 73.400319ms | yes |
| 10,000 | 50,000 | 0 | 81.919us | 22.020095ms | 47.972351ms | yes |
| 25,000 | 125,000 | 0 | 81.407us | 29.097983ms | 47.972351ms | yes |
| 50,000 | 250,000 | 0 | 78.847us | 57.671679ms | 90.177535ms | yes |
| 100,000 | 500,000 | 0 | 86.527us | 179.306495ms | 205.520895ms | yes |
| 250,000 | 1,250,000 | 0 | 348.127231ms | 754.974719ms | 759.169023ms | no |
| 500,000 | 2,500,000 | 0 | 3.070230527s | 5.133828095s | 5.200936959s | no |

By the median rule this page uses throughout, the adapter's ceiling is
**100,000 a second** on this machine. Two things have to be said next to that
number or it is worse than useless.

**The accumulator is not in it.** A real `KafkaProducer` batches into an
accumulator, hands batches to a sender thread, and blocks up to `max.block.ms`
when that fills. None of that is here. This bounds the adapter, the part this
repository wrote, and says nothing whatever about what producing to a broker
costs. What a real producer costs is not measured anywhere yet.

**Read the p99 column before the verdict.** The median rule is what names the
ceiling, and it is the only reason this table and the two above are comparable
at all. But a median can sit inside a millisecond while the tail is hundreds of
them, and it does: at 100,000 a second the median departure is 87 µs late and
the 99th is 179 ms late. That is not a rate anyone should drive. The p99 column
is where the adapter stopped keeping up for the users who would notice, and it
starts climbing around 50,000.

`fellBehind()` is not reported for this sweep, for the reason the null step does
not report it: it asks whether the backlog is large against the response time it
inflates, and a producer that answers immediately has no response time to speak
of, so it says yes at every rate. A constant column tells a reader nothing and
reads like it does.

## What is not measured here

- **No comparison** with Gatling, k6 or anything else. A benchmark that ranks
  two tools measures whoever wrote it.
- **One machine, one run each.** The load average beside the tables is part of
  the figures, not an apology for them: this sweep had four cores mostly to
  itself, and an earlier one on the same code at a load average near six
  reached the same ceiling with tails several times worse. It is a shape on
  this hardware, not a promise about yours.
- **The port reading is machine-wide.** `tw` counts every socket in TIME_WAIT
  on the host, not just this process's, so a busy neighbour inflates it. It is
  read against the ephemeral range, which is machine-wide too, so the reading
  and its ceiling describe the same thing, but it cannot prove a particular
  socket was Proofload's.
- **What a real Kafka producer costs.** The adapter sweep above removes the
  broker with a producer that answers immediately, which also removes the
  accumulator, the batching and the sender thread, the parts most likely to
  decide what a Kafka run can drive. Measuring those needs a broker on a
  socket, and there is not one in this build.
- **A footprint measured while something else held the machine.** The live-set
  reading is a difference between two heap readings around a forced collection,
  so a neighbour allocating during the window lands in the figure. The same rule
  as the ceiling sweeps: run it on a quiet machine or do not quote it.
- **Nothing is in `./gradlew build`.** The sweeps are benchmarks, they want a
  machine to themselves, and `AGENTS.md` keeps a benchmark of the tool out of
  the tests of the tool and out of the coverage denominator. Run them on
  purpose, on your own machine, and read your own numbers.

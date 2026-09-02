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
one-minute load average of **0.41 to 1.29** across the sweep. That is a quiet
machine, and it is what these figures describe. A busier one gives smaller
numbers — an earlier sweep of this page ran at a load average near six and
reached the same ceiling with far worse tails — so quoting either as general is
the thing this page exists not to do.

A rate kept its schedule when the median departure left within a millisecond of
when it was due. The same rule decides both tables, so the two can be read
against each other.

## Over a socket

The step a user writes, sent the way a user sends it: one blocking `send` per
virtual thread through the pooled shared `HttpClient` that `kestrel-http`
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
from a mystery into an attribution — see below. The port reading is machine-wide,
because `tw` counts every socket on the host, so it is read against a range that
is machine-wide too.

### What the last two columns mean

They disagree at every rate, and the disagreement is the point rather than a
mistake in one of them.

**`p50 within 1ms`** is the rule this page picks a ceiling by: the median
departure left within a millisecond of when it was due. It asks whether the
generator was, in the ordinary case, on time.

**`fellBehind()`** is what the library reports on a run, and it asks something
much stricter: whether the injector's *p99* lateness is larger than the
precision the report quotes the target's p99 to — 0.78% of it. At a hundred a
second that threshold is a few microseconds, so any lateness at all trips it.
Reading a `yes` there as "the tool cannot manage a hundred a second" is exactly
backwards: it says the generator's own lateness is big enough to be visible
beside the number being reported, which at these latencies it nearly always
is.

Both are in the table because a reader deserves to see the strict test rather
than have this page quietly pick the flattering one. It says `no` at exactly one
rate here — 2,500 a second, the ceiling — and `yes` everywhere else, which is
what a quiet machine looks like at this precision.

### Why "at least"

`com.sun.net.httpserver` is not a fast server, and this sweep did not
characterise it. So a rate the sweep failed to reach may be the target's limit
rather than the client's, and the honest reading is that Kestrel's HTTP step
reaches *at least* this rate — not that it stops here. Making it an equality
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
descriptors of the 20,000 it was allowed — five per cent, nowhere near — while
sockets in TIME_WAIT reached 21,317 of a 28,232-port range, three quarters of
it, up from 4,734 at half the rate. The failures at this end are ephemeral ports
recycling too slowly, not descriptors, and not the target refusing work.

That is a better answer than this page could give before, and it is not a
complete one: the port figure is machine-wide, so it cannot prove every one of
those sockets was Kestrel's, and it does not rule out the server's accept path
failing at the same time. It narrows the question from "the client, the loopback
stack or the server" to "port exhaustion at this end, or the accept path at the
other".

### What the wall actually is

The sweep stops at rates it can send, so it does not show where the shipped
path breaks. Pushed past its published rates on this machine, it breaks in one
particular way:

| Rate | Requests | Failed | Behind p50 | Served p99 | Files | Ports |
|---:|---:|---:|---:|---:|---:|---:|
| 10,000 | 50,000 | 27 | 140.287us | 397.311us | 486 / 20,000 | 20,630 / 28,232 |
| 25,000 | 125,000 | 111,989 | 9.371647ms | 303.103us | 94 / 20,000 | 34,666 / 28,232 |
| 50,000 | 250,000 | 229,000 | 570.425343ms | 313.343us | 160 / 20,000 | 52,288 / 28,232 |

Nine tenths of the requests failed at twenty-five thousand a second, and the two
columns that explain it are the last two. Descriptors never went above one per
cent of what this JVM was allowed. Sockets in TIME_WAIT went past the whole
ephemeral port range. And the target answered every request that reached it in
about three hundred microseconds at p99, at every rate, unchanged.

So the wall here is **ephemeral ports**, not the client's throughput and not the
target's speed. Connections are being recycled faster than the kernel will give
their ports back. A faster HTTP client would hit the same wall at the same
place, which is worth knowing before anyone writes one: the seam in
`kestrel-http` makes a different client easy to try, and this measurement says
trying one is not what raises this number.

Two things would, and they are different projects. Holding connections open
rather than churning them — which is partly the target's policy here, and
`com.sun.net.httpserver` is not a server anyone tunes — or sending from more
than one host, where each has an ephemeral range of its own.

One caveat on the Ports column, visible in the rows above: it can read past its
own limit. `tw` is machine-wide and counts both ends of a loopback connection,
while the range it is read against describes only the end that dials out. Over
loopback both ends are this machine, so the count roughly doubles. It is right
about *what* ran out and approximate about by how much.

The pick is deliberately conservative. Five thousand a second kept the budget —
a median departure 101 µs late — and is still not the ceiling, because three
requests in twenty-five thousand failed. A refused request is not a request this
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
higher — 326 µs at a hundred a second against 61 µs at a hundred thousand. Read
each table as a shape rather than a ranking.

**The p99 column is not the same measurement.** Below twenty-five thousand a
second it does not follow the rate at all: 8.2 ms at a hundred, 2.2 ms at two
hundred and fifty, 12 ms at five hundred, and then 204 µs at five thousand.
Above that it climbs — 31 ms at fifty thousand, 90 ms at a hundred thousand.
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
- **One machine, one run each.** The load average beside the tables is part of
  the figures, not an apology for them: this sweep had four cores mostly to
  itself, and an earlier one on the same code at a load average near six
  reached the same ceiling with tails several times worse. It is a shape on
  this hardware, not a promise about yours.
- **The port reading is machine-wide.** `tw` counts every socket in TIME_WAIT
  on the host, not just this process's, so a busy neighbour inflates it. It is
  read against the ephemeral range, which is machine-wide too, so the reading
  and its ceiling describe the same thing — but it cannot prove a particular
  socket was Kestrel's.
- **Nothing is in `./gradlew build`.** The sweeps are benchmarks, they want a
  machine to themselves, and `AGENTS.md` keeps a benchmark of the tool out of
  the tests of the tool and out of the coverage denominator. Run them on
  purpose, on your own machine, and read your own numbers.

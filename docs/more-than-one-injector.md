# More than one injector

One JVM sends what one JVM can send. `docs/what-it-costs.md` measures the
shipped path saturating somewhere between ten and twenty-five thousand requests
a second on four shared cores, and whichever resource runs out first, the
answer is the same: a second host brings its own ports, descriptors and cores.

Four JVMs by hand is four plans nobody proved alike, four sets of percentiles
that cannot honestly be pooled, and four timelines whose second thirty is not
the same second thirty. This is how to run four and still have one experiment.

## The three scalars

Every injector runs the same jar and is given which one it is, how many there
are, and the instant they all start on:

```kotlin
proofload.run(soak.sharded(index = 2, of = 4, startingAt = at))
```

Injector *k* of *N* sends the users whose number is `k` modulo `N`, filtered per
arm, so every injector sends the same mix and the union of all of them is every
user exactly once. Four injectors offer exactly the departures one JVM would.

Nothing is coordinated during the run. Feeders and seeded arrivals are functions
of the user number, so they follow the partition without being told about it,
and every injector carries the **whole** plan unmodified, which is why the
files merge at the end instead of refusing each other as four different
experiments.

## The instant

`startingAt` is a wall-clock instant each injector waits for on its own clock,
after it has warmed up and taken its host's lock. Everything else is that
injector's own `System.nanoTime()`, which is honest with no shared clock at all.

What alignment buys is a timeline whose second thirty is the same second thirty
everywhere, because a merge superimposes them. The timeline's resolution is one
second, so a hundred milliseconds of skew is a tenth of a bucket and NTP beats
that comfortably: sub-second alignment buys the picture, not the honesty.

Give them enough time to reach it. A host still loading classes when its
neighbours depart is a host that smears the second they share. An injector
handed an instant that has already passed refuses and writes nothing, rather
than starting late and merging a different window into everyone else's.

## The launcher

A shell loop and ssh. There is no agent, daemon, registry or control plane, and
nothing to install on the injectors but the jar you already have:

```bash
AT=$(( ($(date +%s) + 30) * 1000 ))      # thirty seconds from now, in millis
for i in 0 1 2 3; do
  ssh injector-$i "java -cp app.jar com.example.OneInjector \
      /var/proofload http://target:8080 60000 $i 4 $AT" &
done
wait
for i in 0 1 2 3; do scp injector-$i:/var/proofload/*.proofload ./run/; done
```

Each writes one file, named for when it started, which injector it was, and its
pid, because four injectors given one instant start in the same millisecond by
design, and across four hosts they may hold the same pid.

`examples/src/main/kotlin/.../OneInjector.kt` is the whole of the program those
four calls run.

## Reading them back

```kotlin
import io.github.matthewjones372.proofload.Shards
import io.github.matthewjones372.proofload.baseline.readAll

val shards = Shards.readAll(Path.of("run"))

shards.merged[pay].responseTime.p99   // the run, as one JVM would have measured it
shards.of                             // 4
shards.worst.behind.p99               // the injector that struggled
shards.lostGround()                   // whether any of them did
shards.startedApart                   // how far apart they actually started
```

`merged` is a `RunResult` like any other: hand it to a report, compare it
against a baseline, read a goal off it. Counts are summed and percentiles read
off the added buckets, never averaged.

Two things are read from the worst injector rather than pooled. Shard *k*'s
departures are *N* planned intervals apart, so pooling lateness against the
whole run's spacing would call a run void when every injector kept perfect
time; `lostGround()` asks each injector about the schedule it was actually
keeping, and one host falling behind means the load the profile named is not
the load that left.

## What it refuses

- **An incomplete set.** Exactly injectors `0` until `N`, of one `N`, given one
  instant. Three quarters of a run is a smaller experiment than the one you
  asked for, and nothing in the numbers says so. The refusal names which
  injector wrote nothing, so you know which host to go and look at.
- **A clock that disagrees.** Every injector waits until *its own* clock reads
  the instant, so a host running three seconds fast starts three seconds early
  and writes the same instant as everybody else. The file says nothing is
  wrong. What it cannot write the same is the hold it computed against that
  instant, which is recorded on its shard and refused where two of them differ
  by more than `Shards.TOLERABLE_SKEW` (a hundred milliseconds, which is a
  tenth of a timeline bucket). The refusal says by how much and which injector
  held what. Pass `Shards(each, tolerating = ...)` where a set of hosts nobody
  synchronises is still worth an answer from, and a bound you state is a decision
  you made. A set with a version 7 baseline in it has no hold to compare and is
  not refused for want of a number.
- **Unlike machines**, as `Runs` refuses them: a merged result has one machine,
  one probe and one resolution.
- **`Runs.readAll` on a directory of injectors.** Handed four of them it would
  offer an interval across four quarters of one run and call it the variance
  between processes. *N* shards make a `RunResult`; *M* of those make a `Runs`.

## One host, four injectors

Only for a test of the mechanism. The one-run-at-a-time lock is per host and is
taken *before* the wait, since queueing for a busy machine inside the alignment
window would de-align the timeline, so four injectors on one host serialise
unless you pass `-Dproofload.exclusive=false`. Four processes contending for four
cores is also the arrangement all of this exists to escape.

`examples/src/test/kotlin/.../FourInjectorsTest.kt` does exactly this, and is
what proves the partition adds up.

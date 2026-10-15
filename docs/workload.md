# Shaping the workload

A simulation is a scenario plus a load profile. This page covers how to state
the profile: the arrival rate, how it changes over the run, how long it lasts,
how many users there are, and how they pause and loop.

## How an open workload is scheduled

Most profiles here are **open**: they say when each user arrives, and a user
arrives on time whether or not the users before it have finished. This is what
keeps a slow target from slowing the load down, and it is the model the
[schedule checks](measurement.md#the-schedule-checks) are about.

The arithmetic is exact and computed before anything is sent:

- `at(50.perSecond, over = 1.minutes)` is 3,000 users. The count is the rate
  times the window, rounded **down**, so `1.perSecond` over `500.milliseconds`
  is zero users. Departures fall in the half-open window `[0, over)`.
- User *i* departs at `i / rate`, computed from its index rather than by adding
  intervals, so the schedule does not drift.
- A ramp solves for each user's departure along the rising rate, and stages
  run one after another, each offset by the windows before it.
- The schedule depends only on the profile and the user's index. No response
  or measurement changes when a later user departs.

```kotlin
checkout.at(50.perSecond, over = 1.minutes).userCount()   // 3000, before anything is sent
```

## Flat, ramped, and staged

`at` is the flat case and covers most tests. For anything else, name the shape:

```kotlin
import io.github.matthewjones372.proofload.constantRate
import io.github.matthewjones372.proofload.hold
import io.github.matthewjones372.proofload.injecting
import io.github.matthewjones372.proofload.rampRate
import io.github.matthewjones372.proofload.then
import io.github.matthewjones372.proofload.thenRampTo

checkout.at(50.perSecond, over = 10.minutes)                     // flat

checkout.injecting(rampRate(from = 0.perSecond, to = 200.perSecond, over = 5.minutes))

checkout.injecting(
    hold(10.perSecond, over = 1.minutes)                         // warm the caches
        .thenRampTo(200.perSecond, over = 5.minutes)             // climb from 10
        .then(hold(200.perSecond, over = 10.minutes)),           // and sit there
)
```

`thenRampTo` reads the rate the shape was already running at, so the ramp above
starts at 10 without you writing 10 twice. The whole thing is still a value:

```kotlin
val profile = hold(10.perSecond, over = 1.minutes).thenRampTo(200.perSecond, over = 5.minutes)

profile.over          // 6m
profile.userCount()   // 32100
profile.endRate       // 200.0/s
```

## Fifty users, looping

A **closed** workload is a fixed population of users, each starting again when
it finishes. It is how many people describe load, and it is the one shape here
that measures a queue of its own making:

```kotlin
import io.github.matthewjones372.proofload.users

val soak = looping.at(users(50, over = 10.minutes))
```

Fifty users start at once and each restarts the scenario when it finishes.

**Read this before the numbers.** When the target slows down, a closed run
sends *less*, so its report shows a service that stayed fast while doing less
work. That is coordinated omission, and it is why the open model is the default
here. The HTML report says so at the top of a closed run; the JSON document,
markdown summary, CLI and MCP output do not label it.

What a closed run does not report, because it never promised a departure:

- **no lateness.** `behind` and the per-second lateness are empty rather than
  zero, because a generator is not late for a departure nobody promised, and zero
  would read as perfect punctuality;
- **one clock, not two.** Response time is service time plus departure
  lateness, and a closed run has no lateness, so the two are the same number;
- **no `offered`, no `heldScheduleFor`**, and a `keptSchedule` goal is refused
  where it is written rather than judged against a schedule that never existed;
- **no arrival spacing.** That figure needs one thread seeing departures in
  order, and a population has none.

What it does report is the rate it achieved, which is the only rate it has, and
[Little's law](measurement.md#littles-law), which needs no promised
departure and is the one check a fixed population makes better than an open
run, because the concurrency it predicts is a number you chose.

`users(...)` cannot go inside `then`, `randomized` or a replay: those shape
departures, and this has none to shape.

## Stop sending on a metronome

Evenly spaced departures understate queueing at the rate they claim to be
testing: real arrivals are close to Poisson, and queueing delay scales with the
variability of arrivals rather than only with their mean.

```kotlin
import io.github.matthewjones372.proofload.randomized

checkout.injecting(constantRate(50.perSecond, over = 10.minutes).randomized(seed = 7))
```

Each one-second window gets the number of users the rate owes it, at random
positions inside the window, so the count per second matches the rate while the
spacing varies. There is no default seed: an unseeded run cannot be reproduced.
The report prints the seed.

## Think time

A real user reads the page before clicking. `pause` is that gap, and it happens
when the run does rather than when the file is loaded:

```kotlin
val checkout = scenario("checkout") {
    exec(browse, api.get("/products"))
    pause(2.seconds)
    exec(placeOrder, api.post("/orders").body("""{"cart":"1 anvil"}"""))
}
```

A pause has no name and no row in the report. It is not a step that took two
seconds; it is the absence of one.

A constant pause has a consequence worth knowing: two hundred users that reach
it together leave it together, to the scheduler's resolution, and click again in
the same instant. That is the metronome [Poisson arrivals](#stop-sending-on-a-metronome)
took out of the arrivals, put back inside the journey. Draw the wait instead:

```kotlin
val checkout = scenario("checkout") {
    exec(browse, api.get("/products"))
    pause(exponential(mean = 2.seconds))
    exec(placeOrder, api.post("/orders").body("""{"cart":"1 anvil"}"""))
}

val soak = checkout.at(50.perSecond, over = 10.minutes).thinkingFrom(seed = 20260826)
```

`constant`, `exponential`, `lognormal(median, sigma)` and `uniform(from, until)`
are the shapes. A scenario that draws is refused without a seed, because an unseeded
random run is not one anybody can reproduce, and a scenario of constants still
needs none. Each user's waits come from that seed and its own user number, so
user 4,001 parks the same tomorrow whatever the target did today, and the report
names the distribution and the seed beside the arrivals line.

## Replay the arrivals you actually had

Poisson is closer to production than a metronome and still not production: real
traffic bunches, and the bunching is what fills a queue. If you have a log of
when requests arrived, send that:

```kotlin
val friday = arrivalsFrom(csv(Path.of("friday-peak.csv")), column = "at")
friday.count      // 1,204,663, known before anything is sent
friday.span       // 1h
friday.cov        // 2.71, how bursty that hour really was

val peak = checkout.injecting(friday.replaying(from = 40.minutes, window = 10.minutes, scaled = 2.0))
```

`from` and `window` cut the capture in its own time, before the scaling, so ten
captured minutes at twice the rate is five minutes of run.

Scaling multiplies every gap by the same number, which leaves the coefficient of
variation exactly where it was, and that is what lets the report print the
capture's burstiness beside the run's and mean something. Proofload will not thin
the arrivals to scale them: thinning drives a point process towards Poisson,
which is the shape you replayed a capture to avoid.

A replay cannot be `randomized`, and says so: a capture is already an arrival
process, and drawing from it would put a model back where the measurement was.

## Loops and conditions

A scenario is a tree, not a list, so a loop is a step holding steps rather than
a body copied out:

```kotlin
val browsing = scenario("browsing") {
    exec(signIn, api.post("/session").body(credentials))

    repeat(10) {
        exec(browse, api.get("/products"))
        pause(2.seconds)
    }

    during(5.minutes) {
        exec(poll, api.get("/notifications"))
        pause(30.seconds)
    }

    doIf({ it[tier] == "gold" }) {
        exec(concierge, api.get("/concierge"))
    }
}
```

`repeat` deliberately shadows `kotlin.repeat` inside a scenario. The stdlib one
would build the body ten times over, and the report would carry ten rows for one
step; this builds one step that runs ten times, so `/products` is one row with
ten times the samples.

`during` reads its own clock between iterations. A user still looping when the
profile's window closes finishes its iteration, which extends the run: the
alternative is cutting a user off mid-journey and counting half of it.

`doIf` asks the session and nothing else. A condition over what the target
answered would be a step nobody named, and a run that took one could not say
beforehand what it was going to send.

Because a step can run many times per user, a result reports both:

```kotlin
result[browse].count      // 300 requests
result[browse].reached    // from 30 users
```

## Two journeys in one run

Most real load is a mix. Name each journey as an arm with its own rate and its
own data:

```kotlin
import io.github.matthewjones372.proofload.Arm
import io.github.matthewjones372.proofload.Simulation

val simulation = Simulation(
    arms = listOf(
        Arm(browsing, constantRate(500.perSecond, over = 10.minutes)),
        Arm(checkout, constantRate(20.perSecond, over = 10.minutes)),
    ),
)

proofload.run(simulation)
```

The arms are merged into one departure schedule. Each arm is fed from its own feeder and numbers its users from zero,
so `feed(customer) { "customer-$it" }` on two arms is two independent sequences.

The run lasts as long as its longest arm. Two arms may not share a step name: a
step name is one row of the report. The constructor names the clash. A two-arm run also refuses to compare against a
one-arm baseline, and names the arm that is missing.

## More than one injector

When one JVM cannot send the load, split the run across hosts.
[what-it-costs.md](what-it-costs.md) shows where one JVM's HTTP path saturated on
the machines it was measured on. Each is given which one it is, how many there are,
and the instant they all start on:

```kotlin
proofload.run(soak.sharded(index = 2, of = 4, startingAt = at))
```

Injector *k* of *N* sends the users whose number is `k` modulo `N`, so the four
of them offer exactly the departures one JVM would. Each writes one file, and
the directory reads back as the run they were pieces of:

```kotlin
Shards.readAll(Path.of("run")).merged[pay].responseTime.p99
```

The launcher is a shell loop and ssh; the refusals, the worst injector's
lateness and what one host can and cannot stand in for are in
[more-than-one-injector.md](more-than-one-injector.md).

## Bound what a run on this machine may do

A rate arrives from somewhere: a person, a script, or a plan file a program
wrote. `proofload.toml` beside the build says what this machine permits, and
`preview` says what a plan would do before it does any of it:

```kotlin
import io.github.matthewjones372.proofload.Allowance
import io.github.matthewjones372.proofload.Preview
import io.github.matthewjones372.proofload.preview

when (val asked = checkout.at(50.perSecond, over = 1.minutes).preview(Allowance.fromFile())) {
    is Preview.Allowed -> println("${asked.users} users to ${asked.hosts}")
    is Preview.Refused -> println(asked.reason.described)
}
```

`Proofload().runWithin(allowance, plan)` returns `Ran.Refused` instead of
departing. It is a fence rather than a sandbox, and
[allowance.md](allowance.md) says where that stops.

# The ideas behind the numbers

Every figure Proofload prints answers a question, and most of them answer a
different question from the one people assume. This page is the vocabulary:
what a percentile is, why lateness is a verdict rather than a footnote, and what
Little's law catches that nothing else does.

The numbers here are from a real run against a real target, not invented for the
page.

## Two clocks, and why one of them is the honest one

Every step is timed twice.

**Service time** starts when the request goes out and stops when the answer
comes back. It is what the target took.

**Response time** starts when the request was *meant* to go out, the moment the
schedule said, and stops at the same place. It is what a user would have felt.

They are the same number only while the generator keeps up. The moment it falls
behind, service time keeps looking healthy and response time does not:

```
                request due        request sent        answer
                     |------ late ------|--- service ---|
                     |------------ response ------------|
```

This is **coordinated omission**, and it is the bug this tool exists to close. A
generator that waits for a response before sending the next request measures a
queue it created. Worse, it *stops sending* while the target is slow, so
the slow period is under-sampled by exactly the thing that made it slow. The
tail looks fine because the requests that would have been in it were never sent.

Proofload sends on a schedule regardless of what came back, and reports both
clocks. Goals judge response time by default: a goal written against service
time can be met by a generator that never sent the load.

## Why being late matters enough to be a verdict

If the generator is late, the lateness is *inside* the response times it
reports. A tool that is 200 ms behind reports a target 200 ms slower than it is,
and nothing in the numbers says so.

So every run states whether it kept its own schedule, and that verdict outranks
every goal. A run that fell behind and missed its target has not measured a slow
service; it has measured itself.

Two different questions get asked about it:

- **Lost ground.** Is the lateness large against the gap between departures? At
  150/s the gap is 6.67 ms, so lateness of 40 ms means the load that left is not
  the load that was asked for. The plan itself did not happen.
- **Fell behind.** Is the lateness large against the tail it would inflate? A
  5 ms delay hides inside a 356 ms response and is nothing; the same 5 ms
  against an 8 ms response is most of the number.

The second is a judgement, and it is written down as one: `MATERIAL` is five
percent of the tail. It was once the width of a histogram bucket, a figure that
says how small a difference this tool can *see*, doing the job of one that says
whether a difference *matters*. It called a run behind on 1.4% of its tail while
that run's own timeline was flat end to end. A verdict that fires on noise is one
people learn to ignore.

## What a percentile is, and why not an average

**p99 is the value 99% of requests came in under.** One request in a hundred was
slower. It is not "the worst case" and it is not "the average plus a bit".

From a real run of `/search` at 200/s:

| | |
|---|---|
| p50 | 188 ms |
| p99 | 352 ms |

The average of those 3,000 requests is near the p50. Quote it and you describe a
request that mostly does not happen: half the traffic is slower than the average
and the shape of *how much* slower is the entire question. A page that takes six
calls has a roughly one-in-six chance of containing a p99 request.

Proofload's percentiles are read off counted buckets. A bar is what was counted; a
percentile is the top of the bucket the sample landed in. Nothing is
interpolated, so a number on a page came from something measured. The cost is
that a percentile is accurate to a bucket width, which travels with the number
as `precision` rather than being assumed.

**p999** is the same idea further out, and a short run does not have it: at
fewer than a thousand samples the top bucket holds less than one request. It
reports absent with that reason rather than a confident number nothing measured.

## Little's law, and what it catches

For any stable system:

```
L = λ × W

  L  requests in flight
  λ  arrival rate
  W  time each one takes
```

It is arithmetic, not a model, and true of any queue that is not growing. Which
makes it a free consistency check on a load test, and one nothing else provides.

Proofload measures all three independently: it counts users in flight each second,
knows the rate it asked for, and has the latency from the histograms. If the
count it *observed* disagrees with what the other two *predict*, one of the three
is wrong.

From the same run at 200/s:

| | |
|---|---|
| observed in flight | 38.9 |
| predicted from service time | 38.5 |
| ratio | 1.009 |

Within one percent, so the numbers are consistent with each other. When the
ratio drifts, something is queueing where nobody thinks it is, usually in the
generator, occasionally in a connection pool. The gap between the two
predictions, one from service time and one from response time, is the backlog,
counted in requests.

## Steady state

The opening seconds of a run are not the run. Caches are cold, connections are
being made, the JIT has not finished. A percentile over the whole window mixes
that with the part anybody cares about.

Proofload looks for where the run settled and can judge goals over that segment
alone. A run that never settles says so rather than reporting a number that
describes two different systems averaged together.

## What the machine can resolve

Ask the same unchanging thing twice and the answers differ. That spread is the
floor: the smallest difference this machine can distinguish from noise.

It matters when comparing runs. A 3% regression on a machine whose floor is 6%
is not a regression, and a comparison that consults the floor can say **cannot
tell**, which is a third answer most tools do not have, and the honest one more
often than people expect.

## Declared failures

A `404` from an endpoint documented to return one is the service working as
written. A `500` nobody wrote down is a defect. Every other load tool has to be
told which statuses are acceptable, by hand, per step, and mostly is not, so
both land in one failure count.

Where a contract exists, Proofload reads it: a status the OpenAPI document or the
Pelican endpoint declares fails under its own reason, and the run separates
*the service behaving* from *something wrong*.

## Goodput

Throughput counts requests. **Goodput counts the ones that were both fast enough
and successful**: the share that actually did the job.

A service answering 500/s where a fifth of them are errors and another fifth
miss the latency target is doing 300/s of useful work, and reporting 500 is
reporting the wrong number. Goodput reads like an SLI because it is one.

## Where these show up

| Idea | Where you meet it |
|---|---|
| both clocks | `responseTime` and `serviceTime` on every step |
| kept its schedule | `schedule.kept` and the `behind` verdict |
| percentiles | `p50`, `p99`, `p999`, with `precision` beside them |
| Little's law | `concurrency`, and `agrees` |
| steady state | `steadyState`, and goals judged over it |
| the floor | `calibrate()`, and comparisons that consult it |
| declared failures | `declared:` in a plan, `DeclaredStatus` in a result |
| goodput | `goodput(step, under = …) atLeast …` |

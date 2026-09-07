# 0105 — A run that waits its turn

## Problem

`kestrel-mcp` is written for one person at a terminal, and every part of it says
so. The registry is a map lost with the process. Run ids come from an
`AtomicInteger`, so two replicas both hand out `r-1`. A second `run` while one
is sending is **refused** — `r-1 is still sending; one run at a time` — and the
caller is told nothing about when it might not be. The allowance is a file
describing *this machine*, with no notion of who asked.

Deployed as a service with hundreds of callers, each of those is a fault. A
model told "still sending" has no way to know whether to wait two seconds or
twenty minutes, so it retries; a caller polling after a restart is told its run
never existed; and one operator's `kestrel.toml` fences every tenant alike.

What must **not** change is the constraint underneath: one run at a time on one
host. Two runs on a box contend for its cores, descriptors and ports and each
measures the other. That is not a limit to lift — it is what makes the numbers
true. So a shared service queues; it never parallelises on a host.

## Not doing

- **No scheduler of our own.** Priorities, quotas and fair share are a control
  plane, and [0102](0102-an-injector-that-is-a-pod.md) already argues a `Job` is
  the coordinator. A queue this writes would be a worse Kubernetes.
- **No database.** A run's result is already a file. Durability is where it is
  written, not a schema.
- **No authentication.** Who a caller is arrives from whatever fronts this. A
  load generator that grew a user table would be a second product.
- **No change to one-run-at-a-time.** The lock stays exactly as 0050 wrote it.

## Shape

`run` accepts instead of refusing, and says where it stands:

```json
{"runId":"r-7f3a1c2e","state":"queued","ahead":3,"startingIn":"~6m"}
```

Ids that survive being one of many:

```kotlin
// r-1 collides across replicas; this does not
val id = "r-" + UUID.randomUUID().toString().take(8)
```

Where the run actually happens is a seam, not a scheduler:

```kotlin
/** Where a submitted run goes. In one process, or somewhere with more hosts. */
fun interface Executor {
    fun submit(run: Submitted): Queued
}
```

`InProcess` is today's behaviour with a queue instead of a refusal, and is the
default. A caller with a cluster supplies one that creates a `Job` per run —
whereupon Kubernetes does the queueing, the priority classes and the per-tenant
`ResourceQuota`, and none of that is written here.

## Why this shape

The seam is the whole design. Everything hard about hundreds of callers —
fairness, priority, preemption, quota — is a solved problem in whatever already
runs the fleet, and the honest move is to hand the run over rather than to
reimplement a batch scheduler inside a load-testing tool.

A queue position and an estimate rather than a bare refusal, because the
estimate is the actionable half: every plan states its own `over`, so what is
ahead of you is arithmetic over plans rather than a guess. It is a bound, not a
forecast, and says so — a run can end early, and none ends late.

The alternative is leaving `run` refusing and telling operators to put a queue
in front. Recommended against: the refusal carries no id, so a caller has
nothing to poll and retries, which is the load pattern this tool exists to
teach people not to write.

The tenant question is the sharp one and is deliberately narrow here: an
`Allowance` per caller rather than per machine. `kestrel.toml` becomes the
default a caller inherits, not the only one there is — otherwise a shared
deployment fences every tenant with one operator's numbers, and 0088's ceiling
stops being anybody's commitment in particular.

## Stack

- [ ] **`spec-0105-an-id-worth-having`** — ids unique across processes, and a
      registry that survives a restart by reading the results it wrote.
      Done when: two servers started together hand out different ids, and a run
      finished before a restart is still readable by `status` after one.
- [ ] **`spec-0105-queued`** — `run` accepting and returning a position, and
      `status` answering `queued` with what is ahead.
      Done when: a second `run` gets an id it can poll rather than a refusal,
      and the estimate is arithmetic over the plans ahead of it.
- [ ] **`spec-0105-an-executor`** — the seam, and `InProcess` as the default.
      Done when: the whole of today's behaviour is one `Executor`, and a test
      one dispatches without sending anything.
- [ ] **`spec-0105-a-fence-per-caller`** — an `Allowance` resolved per caller,
      with the machine's file as the default.
      Done when: two callers with different allowances are fenced differently
      by the same server.
      **Held.** It waits on the first open question below, which nobody has
      answered: this is the entry that makes the tool multi-tenant, and the
      other three do not.

## Acceptance

```bash
./gradlew build
```

## Open questions

> Answered 2026-09-07, each on the recommendation in its own bullet, except the
> first — which is not answered and gates the last entry. The reasoning is left
> standing rather than deleted.

- **Is this a different product? Not answered, and it gates
  `spec-0105-a-fence-per-caller`.** A single-tenant tool a person runs and a
  queued multi-tenant service are not the same thing, and four stack entries
  will not make them one. The first three are improvements even for one caller
  and are built. The fourth turns `kestrel.toml` from "what this machine may
  do" into a per-tenant policy, which is the point the answer starts to matter,
  so it waits for one.
- **Answered: caller identity is an opaque string the front door sets.** A
  header or an environment variable, read and never parsed. Anything else is
  authentication, which is refused above. Only the held entry needs it.
- **Answered: a queued run does not keep its place across a restart**, and the
  server says so. A queue that survives is a database. A restart drops what has
  not started and keeps what has finished, which is the honest split for a
  process holding its state in memory.
- **Answered: `benchmark` says how long a run would wait** before it smokes, so
  a caller can decide against it while that is still cheap.

# 0086 — More than one injector, started together

> **Recommendation: do not build this yet.** Written because the gap is real
> and somebody will ask; the argument below is that the cost lands in the wrong
> place. `0061-containers` has the same shape and the same status.

## Problem

0070 split a run across injectors and merged them back: a user-number
partition, one instant everybody aligns on, one file each, `Shards.readAll` to
put them together. What it did not build is anything that *starts* them. The
spec is honest about it — "the coordinator, and the whole of it: a directory
somebody's shell copied" (`ManyRuns.kt:48`).

So a four-machine run is: pick an instant far enough ahead that every machine
can reach it, start four processes by hand with matching `--index`/`--of`, wait,
copy four files to one directory, read them. Every one of those steps is a way
to get a wrong number.

One of them is already caught. A shard nobody copied is refused by name —
`Shards.of` reads `of` out of the files themselves and fails the merge with
"injector 2 of 4 wrote nothing" (`Shards.kt:38-49`). That was written with 0070
and it is the strongest of the checks this spec was going to argue for.

What is left silent is the clock. Every shard records the instant it was *told*
to start on and the merge refuses where those disagree (`Shards.kt:32-37`) —
but that is the nominal instant out of the plan, not the wall clock each
machine read it against. A host running three seconds fast starts three seconds
early, writes the same instant as everybody else, and merges without complaint
into a run whose zero points are three seconds apart. Nothing sees it.

Gatling Enterprise and k6 Cloud both do this for you, and both charge for it,
which is a fair signal about where the difficulty is.

## Not doing

- **No agents, no daemons, no control plane.** A long-lived process on every
  load machine is a thing to install, secure, version and operate, and it is
  the part of a load tool that becomes a product.
- **No cloud provider.** Nothing here knows what an instance is.
- **No clock synchronisation.** NTP exists. What this can do is *detect* a
  machine whose clock disagrees and refuse, which is the failure that is
  currently silent.
- **No change to the measurement.** 0070 decided how shards merge and this
  changes none of it.

## Shape

```bash
kestrel run --scenario com.acme.Checkout --on host1,host2,host3,host4 --starting-in 30s
```

- SSH as the transport, because every load machine already has it and it needs
  nothing installed: the coordinator copies the jar, starts one process per
  host with its own `--index`, and collects the files when they exit.
- The instant is computed once and sent to all of them, so nobody types it.
- **A shard that did not report is a failed run**, not a merge of the ones that
  did — the failure the current arrangement cannot see.
- **A host whose clock differs from the coordinator's by more than the
  alignment window refuses before the run starts**, naming the offset. A
  measurement whose zero points disagree is not a measurement.

## Why this shape

**SSH, because the alternative is a product.** An agent is the honest way to do
this well and it is also a daemon on four machines, a protocol between
versions, and a security surface. SSH is already there, already authenticated,
and already how the team reached those machines to install the JDK.

**The refusal is the actual value.** Copying files by hand is tedious but
visible; a clock that was wrong is not. If this is ever built, that check is
the reason — and it is also the one thing that can be built *without* a
coordinator, which is the recommendation.

## Why not to build it

**The cost lands outside what can be tested here.** Every interesting failure
is multi-machine: a host that refuses SSH, a jar that failed to copy, a process
that died at minute three, a clock that drifted mid-run. None of them can be
verified in `./gradlew build`, and this repository has already declined a
feature for exactly that reason — `0061-containers`, where "the half of this
entry worth having cannot be verified where it was written".

**It is a different product.** Everything else here is a library you call from a
test. This is an orchestrator that copies jars and manages processes, and once
it exists it grows: retries on a failed host, logs from four machines, a run
that half-started. That is the shape of a tool, and the argument for it is not
a measurement argument.

**The refusal does not need it.** Half the safety is already here: a missing
shard is refused by name today. The other half — a clock that disagrees — is
each injector recording the offset it saw between its own wall clock and the
instant it was told, and the merge refusing when those disagree. That is
testable in one process, in one file, against two shards written by two
offsets. All of the remaining safety for none of the machinery, and it is the
recommendation.

## Stack

- [x] **`spec-0086-expected`** — a merge missing a shard refused by name.
      **Already built, with 0070.** `Shards.of` reads `of` out of the files and
      fails with "injector 2 of 4 wrote nothing" (`Shards.kt:38-49`), so this
      entry was written for a gap that was not there. Left ticked and recorded
      rather than deleted: the spec is worth less if it does not say what was
      checked.
- [ ] **`spec-0086-clocks`** — each shard records the offset between its own
      clock and the instant it was told to start on; the merge refuses where
      they disagree by more than the run can tolerate.
      Done when: two shards written by clocks a second apart refuse to merge
      and say by how much.
- [ ] **`spec-0086-coordinator`** — the SSH runner above.
      **Not recommended.** Build the clock entry above first, then decide
      whether what is left is worth an orchestrator. With a missing shard
      already refused and a wrong clock refused too, what remains is the
      tedium — and tedium is a script, not a subsystem.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

For the clock entry. The coordinator cannot be accepted by a command on one
machine, which is the argument.

## Open questions

1. **How much clock skew can a run tolerate?** The alignment is to an instant
    and the timeline is whole seconds, so a second is already visible.
    Recommend refusing above a configurable bound defaulting to something well
    under a second, and recording the observed offset either way.
2. **Where does the observed offset go?** The baseline's `shard` line already
    carries `index`, `of` and the instant (0070's format version 6), so a
    fourth field is the smallest change — but it is a format version, and
    version 8 for one number wants saying out loud.
3. **Is there a smaller coordinator that is worth it?** A script in `docs/`
    that copies and starts, shipped as an example rather than as code with a
    test. Recommend that as the answer if somebody asks for this: it is
    documentation of a thing they will edit anyway.

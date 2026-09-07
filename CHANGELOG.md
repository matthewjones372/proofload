# Changelog

Notable changes, newest first, in the format of
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

Until 1.0, breaking changes come without a major bump and are recorded here.
From 1.0, the public API of the shipped modules is stable and a break waits for
a major release. What changed is recorded here rather than checked by a tool,
so a break that is not written down is a break nobody was told about.

A version is cut by tagging — `git tag v0.1.0 && git push --tags`. The build
reads the nearest `v` tag, so an untagged commit is a `-SNAPSHOT` of the next
one.

## [0.1.0] — unreleased

The first release. Seventeen modules, published together and versioned together.

Read the limitations before the features: what this does not do yet is short
enough to list, and long enough to matter.

### Added
- **`benchmark`, the tool a request actually arrives as.** The rest of the MCP
  table is a verb per step of Kestrel's own model — the shape a library has, not
  the shape a question has. Nobody asks to validate a plan; they ask whether
  their service holds up, and answering that took seven calls in an order the
  caller had to infer. `benchmark` takes an OpenAPI document, a plan, or just a
  base URL, and answers with the plan, what running it would send, and what one
  request per step already found. It sends the smoke and no load: `run` still
  does that, which makes the allowance's gate the only way through rather than a
  step a caller remembers. A refused plan is still handed back, because a caller
  told only that a host is barred cannot see what it was about to send there.
  It also states what it is guessing and asks about it: a generated plan sends
  one request a second and asserts a goal of one second, and handing that over as
  a benchmark is a number nobody chose reported as though somebody had. The
  questions are derived from the plan and the smoke rather than read off a list —
  a credential is asked about because the target answered 401, paths because a
  bare URL was all it was given — and a plan somebody wrote themselves is asked
  nothing.
- **Kestrel over MCP.** `kestrel-mcp` is a stdio server whose every tool is a
  call `kestrel-cli` already makes, so what a program can do is what a person at
  a terminal can do and there is no second behaviour to keep in step. The
  framing is line-delimited JSON-RPC written here rather than taken from a
  library — a notification is not answered, a method nobody serves is a JSON-RPC
  error rather than a tool result, and an unreadable line does not stop the
  server. `plan_schema` is the tool that makes the rest work: a caller who can
  ask for the format writes a valid plan first time instead of a plausible one,
  and a test parses both of the worked plans it hands back, because a schema
  whose own examples do not parse is worse than no schema. Every tool's
  description states what it sends before a caller has to find out.
- **The MCP tools that send nothing.** `validate` parses a plan and resolves its
  steps and goals; `preview` says what it would send and sends none of it,
  refusing what the machine's `Allowance` refuses; `from_openapi` reads a
  document and hands back a plan. Each refuses with the sentence the library
  already writes — the line, and the keys that were allowed — because that
  sentence is what a caller correcting itself acts on, and a stack trace buries
  it. A test drives all three against a counting `HttpServer` and asserts it saw
  nothing: a tool a caller is told is free has to be free.
- **The MCP debug loop.** `smoke` sends one request per step and `trace` walks a
  single user, both bounded by the plan's shape rather than its rate — a plan
  asking for five thousand a second still sends one of each. A plan answering
  400s produces a run full of them and the run says only that they were 400s;
  these are how a caller finds out why without sending load to do it. The
  machine's `Allowance` still refuses a host it does not permit, because that is
  true whether a plan would send one request or a million.

  The server also takes stdout for the protocol and points everything else at
  stderr before a single tool runs. `trace` narrates to stdout and `Kestrel`'s
  default progress prints to it too; either landing mid-message would end the
  session. Cheaper than auditing every call for prints, and it stays true for
  calls nobody has written yet.
- **Running one over MCP.** `run` starts a run and returns a `runId` with what
  it is about to send; `status` answers with how much of the window is left, or
  with 0087's summary once it is done. Split because a ten-minute run inside one
  tool call is a dead connection, a retry, and a second ten-minute run against
  the same target. One at a time: a second `run` while one is sending is refused
  by name rather than handed an id for a run that never started, which is
  something a caller can poll forever. The `Allowance` refuses before the run
  begins, so a refused run departs nothing. Runs are held in memory and lost
  with the process — the honest scope for a server a client starts and stops.
- **A plan from a contract.** `kestrel-contract`'s `planFrom(endpoints, baseUrl)`
  reads Pelican endpoint values into a `plan/1` document: a step per endpoint,
  named by the operation the contract named and keyed on the path template, so
  `/orders/{id}` is one step rather than one per id. Read methods only unless
  another is asked for — a generated `DELETE` loop against staging is somebody's
  evening — and asking for a method the contract does not serve says which ones
  it does. The load it generates is one a second for ten seconds, because a
  generated artefact should never be the thing that hurt something; the caller
  raises it on purpose, under an `Allowance`. Every step carries a goal against
  a placeholder limit, so a generated plan cannot validate green while asserting
  nothing. Its own module rather than a generator inside `kestrel-pelican`,
  whose dependency test promises a consumer nothing but core and `pelican-core`.
- **Values a contract already calls legal.** A generated step's path is filled
  from the constraints on its own inputs — `between(1, 100)` is both the rule
  that refuses a request and the schema's `minimum`/`maximum`, so a value drawn
  inside it exercises the endpoint rather than its validation. Seeded, so the
  same contract yields the same plan twice and a generated file is reviewable.
  This is not a nicety: `{id}` in a path is read from the session key of that
  name and fails the step when nothing is there, and a plan has no feeder to put
  one there, so a generated step that kept its braces would fail every request
  it made. One value, the same for every user — a generated plan is a smoke at
  one a second, and per-user variety is what `kestrel emit` and a feeder are
  for.
- **A declared failure is not a defect.** `HttpAction.declaring(404, 409)` names
  the statuses an endpoint is documented to answer with. They still fail the
  step — a declared `404` did not do what was asked, and counting it a success
  would inflate the goodput of a run against a service returning nothing but
  declared errors — but they fail as `DeclaredStatus` rather than `HttpStatus`,
  so a report separates a service working as written from one doing something
  nobody wrote down. `plan/1` carries a `declared:` list per step, `kestrel
  emit` prints it, and `planFrom` fills it from the endpoint's own `orFail`
  declarations. Every other load tool has to be told this by hand, per step, and
  mostly is not.
- **A plan from an OpenAPI document.** `kestrel from-openapi orders.yaml` reads
  a document and writes the plan it describes: a step per read operation, the
  path filled from the schema its own parameters declare, and every other
  documented status carried as `declared:`. `$ref` into `components` is
  followed, and YAML 1.2 being a superset of JSON means a `.json` document reads
  through the same parser. `Declaration.asYaml()` writes a plan back out, and
  `readPlan(plan.asYaml())` is the same declaration — a generator whose output
  nobody can read back is a generator nobody can use.

  In `kestrel-openapi`, apart from `kestrel-contract`, so that reading a
  document costs nothing from Pelican: most people with a document do not have a
  Pelican service, and the command line would otherwise install `pelican-core`
  for a feature that never touches it. The two share the half that turns a
  schema's facets into a value the service will accept, because a `minimum` in a
  document and a `between(1, 100)` on an input are the same constraint.
- **Goals from Java.** `Goals.p99Under`, `p95Under`, `p50Under`, `p999Under`,
  `failureRateUnder` and `goodputAtLeast` build core's own `Goal` values, and
  `Simulations.at(scenario, rate, over, goals...)` attaches them.
  `Results.verdicts(result)` reads back what each one measured and the margin it
  missed by. Kotlin writes these infix — `p99(placeOrder) under
  200.milliseconds` — which has no Java spelling, and every call that builds one
  takes a `StepName` or a `Share`, whose names mangle; so these are Java sources
  over an `internal` Kotlin layer, the same split the rest of the facade uses.
  There is deliberately no Java `assertNotWorseThan`: `Difference` is a
  baselines type, and wrapping it would put JUnit on the classpath of every
  project that takes the facade.

- **Scenarios as values.** `Scenario`, `Step`, `Action`, `Session` and
  `StepResult` in `kestrel-core`, with typed `SessionKey<T>` and a step body
  that names neither the session nor its result. A scenario is a tree rather
  than a list: `Step.Repeat` and `Step.When` hold steps instead of naming one,
  so `name` sits on the leaves and `Scenario.stepNames` reads off every name a
  run can record.
- **Open-model injection.** `ConstantRate` and `RampRate` state departure times
  up front; each offset is computed from its index so a long run cannot drift.
  `then` puts them in `Stages` for a run that ramps, holds and ramps again, and
  `randomized(seed)` jitters the offsets of any of them from a seed the report
  records, so a rerun departs the same way.
- **Measurement.** A log-linear `Histogram` written here rather than taken as a
  dependency, `RunResult`/`StepStats`/`Timing`, and two latencies per step so a
  generator's own backlog is never reported as the target's speed —
  `Clock.ServiceTime` is what the target took, `Clock.ResponseTime` is measured
  from the departure the profile promised, and every goal names which it reads.
  `RunResult.timeline` is the same pair a second at a time, and the HTML report
  draws it.
- **Goals, judged rather than asserted.** `p50`/`p95`/`p99`/`p999`,
  `failureRate` and `goodput(step, under)` are values; a run answers each with a
  `Verdict` naming the goal, what was measured and what it was over by. A goal
  reads `ResponseTime` unless it is handed a `Clock`.
- **Steps that finish somewhere else.** `emit` records a step whose answer
  arrives out of band, keyed by a `Correlation`, and the run drains its
  `Completions` for a bounded window after the last departure. What is still
  outstanding is reported rather than dropped.
- **Comparing runs.** `RunResult.against(baseline)` answers a `Comparison`, and
  refuses to compare two runs whose shape differs rather than returning a number
  about nothing. `Runs.against` puts many runs on each side, so a `Difference`
  carries a `Spread` and can say it cannot tell instead of calling noise a
  regression.
- **`kestrel-baseline`** — a run written to a file and read back, and a
  directory of them read as `Runs`, so the comparison above has something to
  compare against between builds.
- **Progress says how long.** The line a run prints names the window the profile
  scheduled, and a capacity search names the bound it already computed. Neither
  is a forecast: both are read off values that exist before a request leaves.
- **`kestrel-engine`** — one virtual thread per user, departures started by a
  scheduler, recorders sharded rather than one per user, and a failed step
  abandoning that user rather than counting its later steps as successes.
- **The rate a scenario sustains.** `Scenario.sustainable(upTo, holding,
  expecting)` is a `Search`, and a value: it answers `rungs` and `worstCase`
  before a request leaves. Running one climbs a coarse ladder and bisects at
  the knee, and returns a `Capacity` — the highest rate every goal held at, the
  goal that stopped it, and the curve every rung is on. A rung the injector
  could not offer is void rather than failed, and ends the search.
  `Capacity.toHtmlReport()` draws the curve, with the operating point marked.
- **A credential that stays fresh.** `refreshing(every) { fetchToken() }` in
  `kestrel-core` fetches once before the run and again on a daemon scheduler,
  so a step reads `current` — a volatile read — rather than timing an identity
  provider. `Refreshing.fixed(value)` is the same value with no scheduler, and
  `stop()` ends the schedule.
- **`kestrel-http`** — steps on `java.net.http`, keyed on the path template.
  `withCookies()` carries cookies between a user's steps, in that user's own
  session rather than on the run's shared client, and `traced()` puts a W3C
  `traceparent` and a synthetic-traffic `baggage` entry on every request.
  Redirects are not followed.
- **One run at a time, across processes.** The in-JVM lock is now backed by a
  `FileLock` under `java.io.tmpdir`, so two JVMs on one host run one after the
  other rather than measuring each other, and a killed holder frees the machine
  with nothing to reap. It degrades rather than fails: a lock that cannot be
  taken says so once and the run goes ahead with the in-JVM guarantee.
  `kestrel.exclusive=false` opts out, `kestrel.exclusive.file` moves the lock,
  and `kestrel.exclusive.timeout` puts a ceiling on the wait. A run that queued
  says how long; one that did not says nothing.
- **A test can name its engine.** `Engine` is a `fun interface` in core; a
  JUnit class names one by implementing `RunsOn`, and a Kotest spec by calling
  `kestrel(engine)`. A class that names none runs on virtual threads, so bare
  `@LoadTest` is unchanged. A named engine is still held exclusively, so two
  tests never measure each other whichever engine sends them.
- **Loops and conditionals in the DSL.** `repeat(n) { }`, `during(window) { }`
  and `doIf(predicate) { }` in `ScenarioBuilder`, building the tree the engine
  already walked. `repeat` deliberately shadows `kotlin.repeat` inside a
  scenario — the stdlib one unrolls the body and gives the report a row per
  copy. A `during` loop reads its own clock between iterations, so no carrier is
  parked and a user still looping when the profile's window closes extends the
  run rather than being cut off.
- **`reached` on a step.** How many users got to a step, beside how many
  requests it made — so a loop reads as 30 requests from 10 users rather than as
  30 of something. A result with no user information behind it prints `—`
  rather than a zero nobody measured.
- **More than one journey in a run.** `Simulation(arms = listOf(…))` sends every
  arm, merged into one schedule by a lazy k-way merge of their own departure
  sequences rather than booked one after another — which would have handed the
  arrival recorder a gap running backwards. Each arm is fed from its own feeder
  at its own user number from zero, and a two-arm mix refuses a one-arm baseline
  naming the arm that is missing.
- **A ceiling measured over a socket.** `:benchmarks:ceiling` now sweeps the
  shipped HTTP step against a loopback target as well as a null step, and
  `docs/what-it-costs.md` leads with the figure and calls it a lower bound.
- **`kestrel-websocket`** — `open`, `send`, `awaiting` and `close` as timed
  steps on `java.net.http.WebSocket`, one connection per user, held in the
  session. `open` times the upgrade to the 101 alone and `close` the Close frame
  out and back; `send` is timed for the write and waits for nothing, `awaiting`
  for the wait alone. Answers pair with sends in departure order, and one that
  matches no send is counted as `unsolicited` rather than timed.
- **Redirects, followed in the step.** `following(max)` on an HTTP action walks
  the chain explicitly, one request per hop, with the shared client still on
  `Redirect.NEVER` — so a redirect nobody asked to follow stays a finding. 301,
  302 and 303 go on as a bodyless GET; 307 and 308 keep method and body; cookies
  carry across hops; `expecting()` judges where the chain lands; and a chain past
  `max` fails under `TooManyRedirects`.
- **A feeder straight from a CSV.** `csvFile.feeding(key, ...)` fills each key
  from the column of the same name, with a conversion overload for a key that is
  not a `String`. A key naming a column the file lacks fails when the feeder is
  built, not on user one.
- **The test frameworks are quiet.** `@LoadTest` and Kotest's `kestrel()` hand
  the runner `Progress.silent`, and a calibration no longer announces its
  twenty-four internal runs. A `main` still prints.
  `open` times the upgrade to the server's 101 and `close` times the Close
  frame out to the far end's Close back; neither is a message, and no message
  is timed yet.
- **`kestrel-junit5` and `kestrel-kotest`** — a load test in whichever
  framework is already there, with the runner handed over as a parameter.
- **`kestrel-report-html` and `kestrel-report-github`** — one self-contained
  interactive page, a markdown summary, `$GITHUB_STEP_SUMMARY`, and a Pages
  index.
- **`Scenario.trace(feeder)`** — one user walked through the ordinary step
  machinery and printed, a step to a line. It schedules nothing and returns
  nothing, so a diagnostic pass cannot be read as a measurement.
- **`kestrel-pelican`** — Pelican's `ClientTransport` over the JDK client, so a
  generated typed client runs inside a load test with no Pekko.

- **A warm-up the runner does.** `warmingUp(over)` on a run and on a search
  sends load before the measurement and records none of it: no histogram,
  second or lateness sample from a warm-up reaches the result, because it runs
  before the recorder exists. Each arm holds at the rate its own shape opens
  at, and a search warms every rung at that rung's rate — one warm-up at the
  start would leave the first rung, whose verdict decides whether the ladder
  climbs, measuring a cold JVM. The plan carries it, so the page says what was
  warmed and a comparison refuses to pool a warmed run with a cold one. There
  is no default: a tool that warms unless told otherwise changes what every
  run already written measures.
- **What a run that fell behind still measured.** `RunResult.offered` names the
  load asked for, the load that left and the window it took;
  `latePerSecond` and `heldScheduleFor` say *when* the schedule went rather
  than only that it did. The page, the job summary and a void rung now say the
  service times are the target at the load that left — service time is
  measured from the departure that happened, so a run the generator could not
  drive is a smaller experiment rather than a wasted one. Kestrel still will
  not throttle itself mid-run: a run that lowers its own rate measures a load
  it then does not report.
- **An exemplar beside a percentile.** A traced run keeps one trace id per
  histogram bucket — tens per step, not one per request — and
  `Timing.exemplar(percentile)` returns an id belonging to a request that
  landed in that bucket, never a neighbour's. The page prints it beside p99.9
  and carries it on the p99 cell. The id table is allocated on the first traced
  sample, so an untraced run pays nothing.
- **Attempts beside requests.** `StepStats.attempts` counts the round trips
  behind the requests, so a redirect followed is one request and two attempts
  rather than one request that quietly took two. A retry folded into one
  measurement would report the target as slower than it is and hide that it
  answered wrongly first.
- **A run you can watch counts what it recorded.** The progress line reports
  requests and failures as they land, from a volatile count per shard read
  approximately — it went in only because `:benchmarks:ceiling` reported the
  same ceiling with it as without.
- **One clock per run, owned by the recorder.** `RunRecorder` holds the run's
  monotonic origin and answers `sinceStart()`; `shard()` spawns recorders that
  share it. Every recording path asks the recorder rather than keeping an
  origin of its own, so a transport built before the run can no longer count
  seconds from its own construction.
- **`kestrel-arbs`** — data a run makes up rather than reads from a file, so a
  thousand-row CSV cycled for a million users stops deciding the target's hit
  rate. An `Arb<T>` answers `at(userNumber)` and nothing else: a pure function
  over a mixing hash, so a run replays, user 8,412 is re-derivable, and fifty
  thousand virtual threads share no source to contend on and allocate nothing
  per draw. `oneOf` picks from a list, `map` puts the draw in the caller's own
  keyspace, and `Shape` says what was drawn and from what seed. Core and the
  JDK only — kotest's `Arb` leans towards edge cases because it is hunting
  bugs, which is the wrong bias for traffic.
- **Cardinality and skew, said out loud.** `zipf(keys, skew)` is the generator
  the module was written for: the exponent is the parameter that moves a p99,
  and until now nothing in the tool named it. It answers a rank rather than a
  key, so nothing has guessed the target's id scheme, and it is sampled by
  rejection-inversion rather than off a cumulative table — a million keys cost
  no table at all. `uniform` is the flat keyspace beside it, `digits` and
  `uuids` are ids of a fixed shape, and `weighted` is a traffic mix stated as
  proportions.
- **A run says what its data was made up from.** `Simulation.drawing(shapes)`
  puts a generator's `Shape` on every arm, beside `fedBy`, and `plan()` carries
  it into `PlannedArm.drawn` and out with the result. A `Feeder` is a function
  of the user's number and nothing else, so nothing downstream could work out
  the cardinality and skew a run was measured under by looking at it — the
  caller says it here or the page has nothing to state. `Shape` lives in
  `kestrel-core` rather than in `kestrel-arbs` for that: a plan is a core value,
  and a leaf module cannot put a type of its own into one. A baseline keeps it
  at format version 9; a version 8 file reads as it always did and claims
  nothing, since every stored baseline predates the field. Two runs that both
  named a shape and named different ones are refused rather than compared, and
  the HTML page and the job summary state it beside the arrival process — a run
  that named none says nothing and reads exactly as before.

### Changed

- **A gRPC call from a name and a JSON body.** `grpc.call(schema,
  "shop.Orders/PlaceOrder", body)` is a step reported under the name the wire
  uses, built on 0071's `Grpc` so the channel, the deadline and the
  `traceparent` are the ones that module already fits — there is one gRPC seam
  here and this is not a second. `expecting` takes a status by name because
  that is what the generated code and every log line call them, and `declaring`
  puts a documented `NOT_FOUND` under `DeclaredGrpcStatus` rather than
  `GrpcStatus`, the split `DeclaredStatus` already makes for HTTP.
  `DEADLINE_EXCEEDED` is core's own `TimedOut`, so "how many timed out" has one
  answer across every protocol. The body is checked against the schema when the
  scenario is built, not at the first departure, and nothing is sent to
  discover that it is wrong. `Grpc.target` is public: a narration prints it.

- **JSON to a message, refused by name where it does not fit.** A plan's body
  becomes the message the method declares, merged a field at a time so a
  refusal says which key caused it — protobuf's own "Not an int32 value" is
  unanswerable in a document with thirty keys in it. A field nobody declared is
  refused rather than dropped, which is the opposite of protobuf's usual
  generosity and is deliberate: a plan buys convenience with the guarantee the
  typed path was written for, so a renamed field is as loud as a file can make
  it. An answer comes back as one line of JSON, which is what a `trace` prints.

- **`kestrel-grpc-dynamic`, and a descriptor set read into methods.** A method
  is found by the name gRPC puts on the wire — `shop.Orders/PlaceOrder`, what a
  plan writes and what `grpcurl` takes — and a name nobody declared is answered
  with the ones there are rather than with a null. A set whose files import one
  another is linked across them, and one missing an import it declares is
  refused naming both files, because the fix is a `protoc` flag and not a
  change to the plan.

  Beside `kestrel-grpc` rather than inside it: the typed path carries `grpc-api`
  and a caller's own stubs, and protobuf's runtime is a stack nobody wanting
  that path should inherit. No transport here either, for the reason
  `kestrel-grpc` names none.

- **A broker is a host a fence can see.** `Targeted` gained a `hosts` beside its
  `host`, defaulting to the one, because a bootstrap list is several and a fence
  shown the first of them is a fence with a hole in the shape of the rest. The
  Kafka send is a class rather than a lambda so it can answer: `preview` names
  every entry of the list, an allowance that does not permit the cluster refuses
  the plan the way it refuses a URL, and a produce step no longer counts as a
  step that named no host.

  `benchmark` reads a plan with brokers in it, and asks what such a plan is
  guessing: whether that cluster may be written to and what downstream acts on
  the records, which topic carries the answer where nothing does, and whether
  one key for every record is the partition distribution the service really
  sees. `plan_schema` describes all three step kinds and carries a third worked
  plan. `kestrel-cli` and `kestrel-mcp` carry `kestrel-plan-kafka` and say so in
  their dependency tests; `kestrel-plan` on its own still carries no broker.

- **`produce`, `completes` and their keys in `readPlan`.** A step names which
  protocol it is by which key it carries — a verb, or `produce`, or `completes` —
  and a plan may mix them, so a team whose journey is a request and then a
  record writes one file. `baseUrl` and `brokers` are read as optional for the
  same reason. A key nobody declared is still named as the key it is, and
  `readPlan(plan.asYaml())` round-trips a topic plan, which is what makes the
  writer usable. `kestrel emit` on such a plan is checked in under `examples`
  and compiled by the build, beside the HTTP one: a `Topic` value per produce
  step carrying what that step sends, one call to a line.

- **An answer on another topic, declared beside the step it answers.** A plan
  step carrying `completes`, `on`, `by` and `within` is the other half of the
  produce step it names: what it records is the round trip, as its own row
  rather than folded into the publish. `within` has no default, because a run
  that waits forever for an answer that never comes reports no failure and no
  number. A plan declaring two answers is refused naming both — a run is
  drained into one sink — and a `completes` naming a step nobody produces is
  refused before anything is built.

  The correlation is the user's number, which is unique per departure in an open
  model and is the only value a plan has without a lambda; the lowering supplies
  the feeder that puts it there. `Lowering` accordingly returns a `Lowered`
  rather than a list of steps: a step answered somewhere else needs a sink and a
  value per departure, and both belong to the run rather than to a position in
  the scenario. `emit --kotlin` prints the `emit`/`completing` pair, with the
  correlation header on both topics.

- **`kestrel-plan-kafka`, the module that lowers a produce step.** `KafkaSteps`
  is the `Lowering` `asSimulation` wants for a plan with topics in it, and it
  builds the step through `kestrel-kafka`'s own DSL rather than assembling one —
  so a plan cannot describe a step the language could not have. It takes a
  `Kafka` to lower onto, defaulting to the one the plan's brokers name, so a
  caller with a producer of their own passes `kafka.over(it)`. Kafka's own
  producer settings pass through by their own names, `acks` included.

  `kestrel-kafka`'s fake broker moved to test fixtures, since proving a lowered
  plan produces what the equivalent Kotlin produces wants the same socket a real
  `KafkaProducer` connects to. The fixture variants are kept out of the
  published component, so nothing new goes to Maven Central beside the library.

- **A declared step is a sealed type, and a plan can name a topic.**
  `DeclaredStep` was one data class describing a request; it is now a sealed
  interface with `DeclaredStep.Request` — the same fields under a new name — and
  `DeclaredStep.Produce`, a record on a topic. `Declaration.baseUrl` is
  nullable beside a new `brokers`, because which host a plan needs is decided by
  the steps it declares rather than by a key that is always there: a plan of
  nothing but topics has no base URL to state, and one of nothing but requests
  has no cluster. Both are refused where the steps are lowered, naming the step
  that wanted the missing one. `Declaration.requests()` is the way back to the
  request steps for a caller that had them typed before.

  `kestrel-plan` still carries `kestrel-http` and one parser and nothing else.
  A produce step is lowered by a `Lowering` a caller passes to `asSimulation`,
  supplied by the module that carries the Kafka client, so a plan of nothing but
  requests does not inherit a broker's stack; a plan with a produce step and no
  such lowering says which module supplies one. `ScenarioBuilder.produce(name,
  topic)` in `kestrel-kafka` is the publish measured on its own, where `emit` is
  the publish plus the answer on a second topic.

- **A step body can record more than one sample.** `Action.run` takes the
  `StepScope` the engine builds rather than a `Session`, and `StepScope.sample`
  reports an answer as the body observes it. A WebSocket `awaiting(count)` is
  now one sample per message, each measured from the send it answers, rather
  than one sample for the batch; a body that reports none is recorded exactly
  as before. A wait that runs out part-way reports the answers that did arrive
  and a sample of its own carrying the failure, timed from the last answer:
  the engine records nothing for a body that reported its own samples, so
  without that sample ninety good answers and a timeout would read as ninety
  successes. `StepStats.visits` counts how many times a body ran, beside the
  answers it reported and the users that reached it: a stream visits once and
  samples many times, a loop the other way round, and both make `count`
  outnumber `reached` identically without it. Both reports name the steps whose
  answers outnumbered their runs, and say nothing on a run with none — a
  baseline written before this existed counts no visits, which reads as
  unmeasured rather than as no stream. Anything implementing `Action` by hand changes shape, and
  `run(session)` stays for callers that have a session and want the outcome.
- **A body the user brings.** `body("...")` fills `{name}` from the session,
  the rule `/orders/{id}` already uses, so ten thousand users no longer send
  one identical order and the page no longer calls whatever the target does
  with a duplicate the latency of placing one. A missing key fails the step
  with `UnfilledPath` naming it rather than sending the braces on. Only an
  identifier is a placeholder — a body is full of braces that are not — so a
  JSON document arrives exactly as written unless it carries `{aName}`. **A
  break for a body that meant one literally:** it now fails as a key the
  session has nothing under.
- **A body too large to hold.** `bodyFrom(bytes) { stream }` sends without
  materialising, so a test that uploads a gigabyte can run and one that uploads
  more than the heap can be written. A supplier rather than a stream: a stream
  is read once and this module retries and follows redirects by sending again,
  so each attempt opens its own. A length given is sent as `content-length` and
  none is chunked, a length nobody knows not being one to guess. **A break for
  a custom `Transport`:** `Request.body` is a sealed `Body` — `Text` or
  `Streamed` — rather than a `String?`, because a nullable string cannot say
  "a stream this long".
- **Server-sent events.** `sse.baseUrl(...).at(path)` opens a feed in
  `kestrel-http` — no new module, no new dependency — and it is read with the
  split 0071 settled for gRPC: `open` ends when the target agrees to stream,
  `firstEvent` is the round trip to the first event, and `cadence` is one
  sample per event after it, each measured from the event before. A `cadence`
  before any `firstEvent` is refused rather than reporting the round trip as a
  gap. A comment line is counted as a heartbeat and is not an event, so a feed
  that only heartbeats times out instead of reading as a busy one. Nothing
  reconnects: `retry:` and `Last-Event-ID` are ignored and a far end that lets
  go fails the waiting step, a generator that reconnected being one that hides
  the disconnection it exists to report.
- **What each stage measured.** `RunResult.stages` splits a staged run at the
  boundaries its own profile named, so a ramp and the hold after it are two
  answers rather than one number over both. The aggregate on a staged run is a
  mixture weighted by how long each stage lasted — lengthen the ramp and it
  improves without the target changing — and both reports now carry a row per
  stage beside it. Read off the timeline rather than recorded, so the figures
  are the timeline's coarse width and say so; a boundary falling inside a
  second is named rather than interpolated, the second counted whole in the
  stage it begins in. Empty for a run nobody staged.
- **The closed model.** `users(50, over = 10.minutes)` holds a fixed population,
  each user restarting the scenario when it finishes — "fifty users, looping",
  which is how most people describe load. Supported and labelled: a closed run
  sends less when the target slows down, so its report shows a service that
  stayed fast while doing less work, and the page says that before it says
  anything else. It reports no lateness (absent, not zero — nothing promised a
  departure), one clock rather than two, no `offered` or `heldScheduleFor`, and
  no arrival spacing; a `keptSchedule` goal on one is refused where it is
  written, as is putting a population inside `then`, `randomized` or a replay.
  Little's law is the check it makes better than an open run. The baseline
  format goes to version 7 to carry the population and window; version 6 files
  still read.
- **What the Kafka path costs.** `:benchmarks:kafkaCeiling` sweeps the produce
  path with the broker removed, and `docs/what-it-costs.md` carries the figure
  with what is missing from it: no accumulator, no sender thread, no
  `max.block.ms`, so it bounds the adapter and says nothing about producing to
  a broker. It also says that the median rule naming the ceiling calls 100,000 a
  second "kept its schedule" while the 99th percentile departure is 179 ms late.
- **`kestrel-kafka`** — Kafka produce steps, and the answer read off another
  topic. `kafka.brokers(...).topic(name).keyed { }.value { }` produces through
  an `emit`, and `topic.correlatedBy(Header(...)).completions()` is the sink a
  `completing` drains — so the latency that matters is a consumer having done
  the work, measured from the departure the profile promised, rather than a
  broker's ack. The correlation is stated once and rides a header, so the
  completion side needs no deserializer. The serializer stays the caller's, a
  `(Session) -> ByteArray?` this module never looks inside: Confluent's is not
  on Maven Central, and depending on it would force a `packages.confluent.io`
  declaration on every consumer. `linger.ms` defaults to 0, since a producer
  that lingers makes the arrivals figure describe the injector rather than the
  broker. No broker in the build, embedded or containerised.
- **`kestrel-grpc`** — gRPC steps over a caller's own stubs. `grpc.target(...)`
  gives a channel to build a stub on, `call(descriptor) { }` times one round
  trip and names the row `orders.v1.Orders/PlaceOrder` off the descriptor, and
  a status is a value: `failedWith(GrpcStatus(UNAVAILABLE))` reads a run back,
  with `DEADLINE_EXCEEDED` recorded as core's own `TimedOut`. `traced()` puts a
  `traceparent` and the synthetic `baggage` marker on every call's metadata,
  and `deadline(...)` gives a call a budget where it set none of its own —
  a caller's `withDeadlineAfter` still wins. `open`/`send`/`awaiting`/`done`
  measure a bidirectional or client stream as one sample per answer, timed from
  the message it answers. A server stream is `serverStream`/`firstAnswer`/
  `cadence`: its messages answer no send of their own, so the round trip to the
  first one and the gaps between the rest are two numbers under two names
  rather than one histogram holding both — and a `cadence` before any
  `firstAnswer` is refused rather than reporting the round trip as a gap.
  `grpc-api` and `grpc-stub` only: no transport, no protobuf runtime, no
  coroutines, so the thread model stays the caller's.
- **A run nothing fires by accident.** `Allowance` is what a machine permits a
  run to do, read from a `kestrel.toml` a human commits: `hosts`, `maxRate`,
  `maxDuration` and `maxRequests`, any of them absent meaning unbounded. A
  hand-read `key = value` subset rather than a TOML dependency in core — four
  keys do not earn one, and the thing this has to get right is the message,
  which names the line. An absent file is `Allowance.none` and bounds nothing,
  because a tool that fails closed on an unconfigured machine is one people
  delete the configuration to use. `Refusal` is a sealed type carrying what was
  asked beside what is allowed, so a refusal names the number to come down to.
  It is a fence and not a sandbox, and the documentation says so. Not to be
  confused with `Limits`, which is the other direction: what the injector ran
  into while measuring.
- **Answers before you fire.** `simulation.preview(allowance)` says what a plan
  would do without doing any of it: users, window, the tallest rate it reaches,
  the fewest requests it can send, and every host it would touch — or a
  `Refusal` naming what was asked beside what is allowed. The peak is the
  tallest stage rather than the mean, because a fence that averaged a ramp
  would allow a peak nobody agreed to, and a closed run states no rate at all,
  because its departures are the target's to decide. `requestsBounded` says
  whether the count has an upper bound; a scenario looping on `during` or
  `doIf` has none, and is refused against a request cap rather than allowed on
  its lower bound — a fence that cannot count cannot fence. Hosts come through
  a new `Targeted` interface core declares and `kestrel-http` answers, read off
  the base URL rather than resolved: a DNS lookup would be the first thing this
  tool did to a host nobody agreed it may touch. A step whose target cannot be
  read is counted as `untargeted` rather than as safe.
- **`runWithin`.** `kestrel.runWithin(allowance, simulation)` returns `Ran.Result`
  or `Ran.Refused`, taking the same `preview` a caller can take itself, so what
  a run is refused for is what it was shown. It sits beside `run` rather than
  replacing it: a library call somebody wrote by hand is that person's decision,
  and widening `run` to a sum type would delete a line from a published `.api`
  file for every caller that never asked for a fence. A refused run departs
  nothing, which the engine's own test proves by counting what the action was
  asked to do rather than by believing the runner.
- **A plan as a value.** `kestrel-plan` holds `Declaration` — a plan somebody
  wrote down — and `asSimulation()`, which lowers it into the values the Kotlin
  DSL already builds, so the two cannot describe different runs. Deliberately a
  strict subset: everything needing a lambda, a capture or a condition is absent
  rather than spelled with a string key, because a file that grew those would be
  a worse language for the same job. Everything a plan can get wrong is refused
  before anything departs — an unknown version, no steps, a verb nobody serves,
  a goal naming a step that was never declared. The model and the lowering live
  here rather than in core because lowering a path into a step needs an HTTP
  client, and core declares an `Action` without a protocol type.
- **A plan read from a file.** `readPlan(text)` and `readPlan(path)` read a
  `plan/1` document, on snakeyaml-engine and nothing more: YAML 1.2 is a
  superset of JSON, so one parser reads a plan a person hand-edited with
  comments in it and a plan a program generated, and there is no second reader
  to disagree with the first. It composes to nodes rather than loading to maps
  so that every failure names the line it came from — an undeclared key is
  refused with the line and the list of keys that were allowed, which is what a
  caller working from the schema needs in order to fix it. `Rate.parse` moves
  into core so that `"500/s"` in a plan and `"500/s"` in a `kestrel.toml` cannot
  come to mean different things.
- **A command line.** `kestrel validate <plan>`, `kestrel preview <plan>` and
  `kestrel run <plan> [--json]`, in `kestrel-cli`. The exit code is the verdict
  — 0 met, 1 missed a goal, 2 the generator fell behind, 3 refused by the
  allowance, 4 the plan does not read — so a shell branches on it without a JSON
  reader in sight, and `behind` outranks a missed goal there for the reason it
  does everywhere else. Arguments parse into a `Command` value and every command
  returns a `Finished` value, so what a shell would see is asserted without
  spawning one. `--json` prints 0087's summary; without it the same verdict is
  printed in words, off the same goals and the same remedy, so a caller cannot
  be told two things. A plan that does not read exits with the parser's own
  sentence — the line and what was allowed — rather than a stack trace.
- **The way out of the file.** `kestrel emit <plan>` prints the plan as the
  Kotlin it was equivalent to — step handles, the scenario, the profile and the
  goals — so the moment a plan needs a capture, a condition or a body per user
  the caller carries on in the language rather than asking for another key in
  the file. That is what stops `plan/1` growing into a worse DSL. The emitted
  source is checked in under `examples`, so the build compiles it and a test
  keeps it identical to what the emitter writes: a golden can show the text is
  unchanged and only a compiler can show it is Kotlin. `kestrel-record` emits
  Kotlin too and is deliberately not reused — its output is a scenario where
  this is a whole load test, and its header describes a browser recording with
  the credentials stripped out, which would be a false account of where a plan
  came from.
- **`Traceparent` in core** — the W3C id generator moved out of `kestrel-http`,
  which is where it was first needed, so every protocol that can carry a trace
  uses the same one rather than a copy per module.
- **`kestrel-export` and `kestrel-otel`** — a run's measurements in formats
  other tools already read. `writeHistogramLog(path)` writes HdrHistogram's log
  format, one tagged line per step per side per clock plus the run's lateness
  and the injector's stalls, with nothing re-bucketed: this counter table *is*
  HdrHistogram's, and its own reader is the test oracle. `openMetrics()` and
  `writeOpenMetrics(path)` write a Prometheus/OpenMetrics exposition, cumulative
  buckets on the histogram's own boundaries, no `_sum` because nothing here adds
  latencies up. `sendOtlp(endpoint)` pushes the same measurements as one delta
  export and answers `Accepted` or `Refused` rather than throwing. These three
  metrics formats carry measurements only — the plan, the goals, the verdicts,
  the intervals and every "cannot tell" stay in the report, and in the run
  document below, which is read whole rather than scraped. `kestrel-export` is core and the JDK
  only; `kestrel-otel` carries the SDK, over `java.net.http` rather than the
  OkHttp the exporter ships with. See
  [docs/exporting.md](docs/exporting.md).
- **A result a machine can read.** `result.json(Density.Summary)` is the run's
  answer in under two kilobytes — a one-word `verdict`, the plan, whether the
  schedule held, every goal with the margin it missed by, the steady segment,
  Little's law, the counts and the failures folded together by reason — and
  `Density.Full` adds the per-step timings, the timeline and the run's own
  lateness. `writeJson(path)` puts either on disk. Every document names its
  schema, `kestrel/run/1`, in its first field, and a reader must ignore keys it
  does not know, so a new optional field is not a break. The verdict is ordered
  rather than scored: `behind` outranks a missed goal, because a run whose
  generator lost its schedule did not measure the target, and a run carrying no
  goals reads `nothingAsked` rather than `met`. Beside the verdict is a
  `remedy`: `Verdict.remedy` and `RunResult.scheduleRemedy` in core say what to
  do about a run in a sentence, taking `Tell.CannotTell.wouldChangeIt` where a
  verdict was refused rather than writing a second answer beside it. No remedy
  names a rate nothing measured — "try 38 a second" would be an estimate
  printed as advice. The summary carries the sentence once; `Density.Full`
  repeats it per goal. The shape is written down in `docs/schemas/run-1.json`
  and both densities are validated against it on every build, with undeclared
  properties refused: emitting a field without declaring it fails the build,
  while a reader is still told to ignore keys it does not know. Durations are
  the
  nanoseconds the histogram reported: the document holds the measurement and
  the reader does the formatting. In `kestrel-export`, which stays core and the
  JDK only; the JSON the HTML report inlines is a separate document with a
  separate job and is unchanged.
- **A precision that travels with the number.** `Timing.precision` carries the
  width of the bucket its percentiles were read off, set at the freeze from the
  histogram behind it, and `List<Timing>.merged()` refuses across unlike widths
  the way `Histogram.merge` already did — grouping on `upperBound` and summing
  would otherwise produce a distribution half of one bucket scheme and half of
  another, silently. Both reports now read the figure off the value they are
  printing rather than off a constant: three of them had been claiming a 6.25%
  timeline precision for runs whose timeline was empty. Null where nothing was
  counted, and no default on the field, so nothing can quietly claim a
  precision it never had.
- **Trends across baselines.** `readTrend(history, statistic)` reads a
  directory of directories — one subdirectory per point, holding that point's
  runs, the subdirectory name being the only label a baseline file can carry —
  and `Trend` holds the comparisons themselves rather than a line through them:
  `ends` is oldest against newest, `steps` the adjacent pairs whose own runs
  support a move, `comparisons` how many were made. `Runs.band(statistic)` is a
  point's own reading resampled, in that statistic's units. `Trend.writeHtmlReport`
  draws the series, with the note saying how many comparisons were made at 95%
  and how many a series that never moved would name anyway. Nothing is fitted,
  nothing is smoothed, and a change of machine is a break rather than a segment.
- **More than one injector.** `simulation.sharded(index, of, startingAt)`
  splits a run across JVMs: injector *k* of *N* sends the users whose number is
  `k` modulo `N`, so the set of them offers exactly the departures one JVM
  would. Every injector carries the whole plan unmodified and derives its own
  share, waits for one instant on its own wall clock after warming and after
  taking its host's lock, and writes one file. `Shards.readAll(directory)`
  merges those back into the run they were pieces of — counts summed,
  percentiles off the added buckets, lateness and stalls from the worst
  injector rather than the pool, and an incomplete or unlike set refused by
  name. `Runs` refuses injectors outright. See
  [docs/more-than-one-injector.md](docs/more-than-one-injector.md).
- **The baseline format is version 8.** It carries the warm-up a run declared,
  because a comparison refuses to pool a warmed run with a cold one and a file
  that did not carry it would refuse every warmed run against every baseline
  ever written; since version 6, the run's lateness and the injector's own
  stalls as buckets plus the shard that wrote it, without which a merged
  distributed run could not say which injector lost ground; and since version 8
  the hold that shard computed against the instant it was given, which is the
  only place a clock that disagreed can be seen. Versions 7, 6, 5, 4 and 3 still
  read, each claiming nothing about the lines it did not have.
- **A failed credential refresh no longer stops refreshing.** A fetch that
  threw out of `refreshing`'s scheduled task cancelled its own schedule, so one
  failure left every later refresh unscheduled and a soak reading a credential
  that expired an hour ago — arriving as the target answering 401. The failure
  is now caught, counted on `Refreshing.failures` and named in `lastFailure`,
  and the last good value stays in force.
- **`StepResult` carries what a step did as well as what it was.** `Ok` and
  `Failed` gained `attempts` and `trace`, both defaulted, and `StepScope`
  gained `attempted()` and `traced(id)` for a step body to report them.

- **A failure reason is a value, not a string.** `StepScope.fail` takes a
  `Reason` — `Said`, `Threw`, `TimedOut`, `Other`, or one a caller writes — and
  `Outcome.reasons` is keyed on it. Reported failures were strings until this
  week, so a step body that called `fail("...")` still compiles through `Said`,
  and anything that read a reason back as a `String` does not. A `Reason`
  implementation must be a data class or an object: one with identity equality
  gets a row per request instead of a count.

- **A run says what it is doing while it is doing it.** `and` composes two
  `Progress` reporters and `throttled(every)` slows one that costs something,
  both in core; `otlpEvery(interval, to)` in `kestrel-otel` pushes the live
  `Snapshot` at a collector, under the names the finished export uses. A
  collector that refuses is one line on stderr rather than a two-hour run that
  died at minute one.

- **A download nobody reads is measured without being held.**
  `discardingBody()` drains the response through a counting handler:
  `Response.bytes` is what came back and `Response.body` is empty, so a 200 MB
  export is measured in a heap that could not hold one of them. A check or a
  capture on such a step is refused where it is written rather than passing
  against an empty string. `Response.bytes` is on every response.
- **A goal can be judged per stage.** `goal.inEveryStage` answers one `Verdict`
  per stage of a staged run, each carrying the `Stage` it is about, and the run
  meets it only where every stage does — so a run can no longer meet a goal by
  lengthening its ramp. `Verdict` gained `stage` and `refused`: a stage verdict
  missed by less than the width of the timeline's own buckets reports as
  cannot-tell rather than as a red tick nobody can chase. Both reports put the
  verdict on the stage row it belongs to.

- **Database steps, with the pool wait counted apart.** `kestrel-jdbc` sends
  `query` and `update` over a `DataSource` the caller hands in — no driver, no
  pool, no ORM, and `java.sql` is the whole of what it adds to a classpath. The
  statement is timed from the statement: the connection checkout is recorded
  beside it as `waitedForPool`, because a user queueing for the generator's own
  resource is not the target being slow. Rows are counted rather than read, and
  a failure is a `SqlState` carrying the code the database is required to give.
  `StepStats` gained `queued` and `produced` for it, and `StepScope` the two
  methods that report them; `StepSink` gained an `aside` with a default, so a
  sink written as a lambda stays one.

- **A clock that disagrees is refused rather than merged.** Every injector
  waits until its own wall clock reads the instant it was given, so a host
  running fast starts early and writes the same instant as everybody else: the
  hold each one computed is the only place the disagreement shows. It is
  recorded on the shard, and `Shards` refuses a set whose holds differ by more
  than a bound the caller can state, naming which injector held what. **The
  baseline format is version 8**, which is version 7 plus that one field;
  version 7 files still read, and claim no hold.

- **A scenario from traffic you already have.** `kestrel-record` reads a HAR —
  the file every browser and proxy exports — and writes Kotlin source you edit
  and commit: one `exec` per request, a `capture` and a `{name}` where one
  answer's value turns up in a later request, one step where forty differ only
  by a segment, and static assets left out. Every credential is dropped and
  named where it was, with no flag to keep one. Source rather than a runtime
  `Scenario`, because a recording is a first draft and a file re-read on every
  run is one nobody edits. It carries a JSON parser, which is why it is a module
  of its own and on nobody else's classpath.

- **`kestrel-java`** — the same values, built from Java. `Rates.perSecond(50)`,
  `Steps.named("pay")` and `Shares.percent(1)` hand back core's own `Rate`,
  `StepName` and `Share` rather than a copy of them, and `SessionKeys.of(
  String.class, "orderId")` reaches the key that `sessionKey<T>` cannot give a
  caller with no reified `T`. The factories are Java sources over a Kotlin
  bridge: a Kotlin function returning a `@JvmInline` value compiles to a mangled
  name returning the `String` or `double` underneath it, so no Kotlin signature
  can hand one to Java at all. `sessionKey(name, type)` is the new function in
  core those keys come from, and
  [docs/from-java.md](docs/from-java.md) is the page — every Java line on it is
  a line of the source set the build compiles, which `FromJavaDocTest` fails on.

- **A Java scenario is a Kotlin scenario.** `Scenarios.named("checkout")
  .exec(placeOrder, action).pause(Duration.ofSeconds(1)).build()` accumulates
  core's own `Step`s and freezes them into core's own `Scenario`, so the value a
  Java caller hands the engine compares equal to the one `scenario { }` builds
  for the same steps — which is a test rather than a claim. `Actions.of` takes
  the step body as a `Consumer<StepScope>`, since a lambda with a receiver is
  the one shape Java has nothing for.

- **A Java caller runs the thing and reads what it measured.**
  `Kestrel.create().run(Simulations.at(checkout, Rates.perSecond(50),
  Duration.ofMinutes(1)))` hands back core's own `RunResult`, and `Results.p99(
  result, placeOrder)` reads a percentile off it as a `java.time.Duration` —
  there is no parallel result tree, only accessors that convert. `Https.baseUrl`
  reaches the HTTP steps, with `capturing` and `checking` for the two calls
  Kotlin states as a lambda.

- **The Java surface is gated by a Java compiler.** `examples-java` is a module
  whose whole content is one load test written in Java, compiled by
  `./gradlew build`. `apiCheck` records the Kotlin surface and cannot see
  whether it is *callable* from Java; deleting a facade method fails here
  instead of in a consumer's project. `kestrel-java` is in `smoke/` beside the
  other published modules.

- **The public API is recorded, and a break is a diff.**
  `binary-compatibility-validator` is applied to every published module and
  wired into `check`, so `./gradlew build` fails on an unrecorded break exactly
  as it fails on a detekt finding. `./gradlew apiDump` rewrites the dumps after
  a deliberate change; `config/api/proves-the-gate.sh` makes a break, watches
  the gate catch it and puts it back, and `config/api/README.md` carries the
  transcript. A test cannot make that claim, because breaking a module's API
  from inside that module breaks the module the test lives in.

### Limitations

What this does not do yet. Each of these is checked against the tree at the
commit this section was written on, not planned or assumed.

- **A mix's departed share is a floor, not a count.** The report names the arm
  on every step row and prints the asked-for share beside the departed one, but
  nothing records a departure per arm: the departed share is derived from
  `reached`, the users counted at each step. That is exact for a scenario every
  user walks and a floor for one that opens with a condition, and the page says
  so. The progress line still names the first arm of a mix.
- **A closed run answers nothing about a schedule.** `users(50, over = ...)`
  holds a population rather than stating departure times, so there is no
  schedule to fall behind: the run records no arrivals, no `behind` and no
  `lateness`, its offered rate is null, and `Goal.KeptSchedule` is refused
  rather than answered off numbers that were never taken. The rate such a run
  reaches is the target's speed, not a rate anybody asked for, and the report
  says so.
- **No queue beyond Kafka.** HTTP, server-sent events, WebSocket handshakes,
  gRPC, JDBC, Kafka and Pelican endpoints are the protocols. `emit` is the seam
  for anything else, and the caller writes the client. `kestrel-jdbc` holds no
  transaction across steps and sends no batches: a connection held across a
  think time is a pool exhausted by a scenario rather than by load, and a batch
  wants a spec saying whether it is one sample or many.
- **A response body is held whole unless a step says otherwise.**
  `BodyHandlers.ofString` reads the answer into memory to be checked and
  captured, which is what a check and a capture need. `discardingBody()` counts
  the bytes and lets them go for the step that reads neither, and refuses a
  check or a capture where one is written.
- **Nothing but counts leaves during a run.** `otlpEvery` pushes what the
  scheduler keeps — departed, in flight, requests, failures and the newest
  lateness — to a collector while a run is going, and `and` puts it beside the
  progress line. No percentile is live: reading a percentile means reading the
  histograms the recorders are still writing to, which is a lock on the path
  being timed. Percentiles arrive when the run does, through `sendOtlp`.
- **Read `behind` before any percentile.** Coordinated omission is handled
  rather than avoided: `ResponseTime` measures from the departure the profile
  promised, so a generator that fell behind reports it. But if `behind` is
  large, the injector did not offer the rate the run claims and every percentile
  in that report is about a smaller experiment than the one asked for.
- **The API is not frozen, but it is written down.** Every published module
  carries an `api/<module>.api` dump, and `apiCheck` fails the build where the
  surface moved and the dump did not. Until 1.0 a break still comes without a
  major bump — what changed is that it is now a line removed from a file a
  reviewer is already looking at, rather than something somebody remembered to
  write here.
- **CI is not running, and the Actions tab says otherwise.** GitHub Actions is
  blocked at the account level: a push still *creates* a run, and the run fails
  in a few seconds with no runner assigned — `runner_id: 0`, no runner name, no
  steps, and no logs to download. So nothing here is backed by a green tick,
  and the repository's Actions tab is several hundred red runs that look like a
  broken build and are a build that never started. `./gradlew build` on a
  developer machine is what these modules have been checked with. The workflows
  themselves are fine — the action versions they pin all exist, and runs
  succeeded before the block.

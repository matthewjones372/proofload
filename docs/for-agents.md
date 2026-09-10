# Proofload for agents

The long form of [`llms.txt`](../llms.txt), for a model that has room for more
than a screenful. It carries two things the other documents do not: the public
surface of every published module, and the mistakes a model makes here.

It does not repeat the recipes. [The cookbook](cookbook.md) is where the code
is: profiles, feeders, captures, goals, gRPC, Kafka, JDBC, WebSockets,
baselines and reports, each a few lines with the reason it is those lines.
[Modules](modules.md) says which coordinates carry what, and the
[README](../README.md) says what the library is for.

## Written wrong, written right

The failure here is not missing knowledge, it is confident knowledge carried in
from a more popular tool: a model asked for a Proofload scenario writes Gatling or
k6 with Kotlin syntax, and the caller finds out through a compile error it cannot
map back to the right shape. Each row is a shape reached for and the shape that
compiles. Every call in the right-hand column is checked against `examples` by
`WrittenRightTest`, so none of it is a shape only this document believes in.

| Written wrong | Why it is not that | Written right |
|---|---|---|
| `class CheckoutSimulation : Simulation()` | Gatling's `Simulation` is a base class to extend. Proofload's `Simulation` is the value `at` returns, and nothing extends anything. | An ordinary class, `class CheckoutLoadTest`, with `@LoadTest` on a method that is handed the runner: ``fun `checkout holds up at fifty a second`(proofload: Proofload)``. |
| `setUp(scn.inject(constantUsersPerSec(50).during(60)))` | There is no `setUp`, no `inject`, no registry and nothing to run at startup. A profile is applied to a scenario, and the result is a value. | `checkout.at(50.perSecond, over = 1.minutes)`, which answers before it runs: `userCount()` is `3000` with nothing sent. |
| `exec(http("place order").post("/orders"))`, and then `result["place order"]` elsewhere | The name is written twice, so a rename compiles and leaves an assertion about a step nobody ran. k6's `group("place order")` is the same shape. | `val placeOrder = step("place order")` once, then `exec(browse, api.get("/products"))` beside it and `result[placeOrder].failed.count` after. A `String` overload is there for a step nothing later asks about. |
| `.check(status is 200)`, or k6's `check(res, { 'is 200': r => r.status === 200 })` | There is no check DSL and no `status` receiver to compare against. The status a request expects is declared on the request; anything else is a named predicate over the `Response`. | `.expecting(201)` for the status, and `.checking("has an id") { it.body.contains("\"id\"") }` for the body. A failed check is a failed step carrying the check's name as its reason. |

## The surface

Rendered from the `.api` dumps that `apiCheck` gates, by `./gradlew apiDocDump`,
so a method that leaves the library leaves this section in the commit that
removed it. Read it for *what exists*; read the cookbook for how it is called.

A dump records the JVM signature, which is less than the source says. Parameter
names are not in it. An extension function appears as a top-level function whose
first parameter is its receiver, and an extension property as `getX(Receiver)`.
Value classes are erased, so a `Rate` reads as `Double`, a `Duration` as `Long`
and a `StepName` as `String`. A name the compiler mangled for one of those is
shown without the mangling.

<!-- Rendered from the .api dumps by ./gradlew apiDocDump. Do not edit below. -->

### `io.github.matthewjones372:proofload-arbs`

```text
interface Arb
    fun at(Long): Object
    val shape: Shape
top-level in ArbKt
    fun map(Arb, Function1): Arb
    fun oneOf(List, Long): Arb
    fun oneOf(Array<Object>, Long): Arb
top-level in ShapesKt
    fun digits(Int, Long): Arb
    fun uniform(Long, Long): Arb
    fun uuids(Long): Arb
    fun weighted(List, Long): Arb
    fun weighted(Array<Pair>, Long): Arb
    fun zipf(Long, Double, Long): Arb
```

### `io.github.matthewjones372:proofload-baseline`

```text
top-level in BaselineKt
    fun readBaseline(Path): RunResult
    fun writeBaseline(RunResult, Path): Path
top-level in HistoryKt
    fun readTrend(Path, Statistic, Double): Trend
top-level in ManyRunsKt
    fun readAll(Runs.Companion, Path): Runs
    fun readAll(Shards.Companion, Path): Shards
    fun readAll(Shards.Companion, Path, Long): Shards
    fun writeInto(RunResult, Path): Path
```

### `io.github.matthewjones372:proofload-cli`

```text
top-level in CliKt
    fun main(Array<String>)
    fun obey(Command, Allowance, Function1): Finished
class Code : Enum
    val Behind: Code
    val Met: Code
    val Missed: Code
    val Refused: Code
    val Unusable: Code
    val entries: EnumEntries
    val number: Int
    fun valueOf(String): Code
    fun values(): Array<Code>
interface Command
    val plan: Path
class Command.Emit : Command
    constructor(Path, String)
    val packageName: String
    val plan: Path
class Command.FromOpenApi : Command
    constructor(Path, String)
    val baseUrl: String
    val plan: Path
class Command.Preview : Command
    constructor(Path)
    val plan: Path
class Command.Run : Command
    constructor(Path, Boolean)
    val json: Boolean
    val plan: Path
class Command.Validate : Command
    constructor(Path)
    val plan: Path
top-level in CommandKt
    val usage: String
    fun parse(List): Command
class Finished
    constructor(String, String, Code)
    val code: Code
    val error: String
    val out: String
```

### `io.github.matthewjones372:proofload-contract`

```text
top-level in EndpointsKt
    fun planFrom(List, String, String, Set, Long): Declaration
```

### `io.github.matthewjones372:proofload-core`

```text
interface Action
    fun run(Session): StepResult
    fun run(StepScope)
top-level in ActionKt
    fun action(Function1): Action
class Allowance
    fun allows(String): Boolean
    val hosts: List
    val maxDuration: Duration
    val maxRate: Rate
    val maxRequests: Long
class Allowance.Companion
    fun fromFile(Path): Allowance
    val none: Allowance
    fun read(String): Allowance
class Arm
    constructor(Scenario, InjectionProfile, Feeder, Long, List)
    val drawn: List
    val feeder: Feeder
    val profile: InjectionProfile
    val scenario: Scenario
    val thinkSeed: Long
class ArrivalRecorder
    constructor()
    fun freeze(): Arrivals
    fun record(Long)
class ArrivalSeries
    val count: Int
    val cov: Double
    val digest: Int
    val identity: String
    val source: String
    val span: Long
    fun isRecalled(): Boolean
class ArrivalSeries.Companion
    fun recalled(String, Int, Long, Int): ArrivalSeries
top-level in ArrivalSeriesKt
    fun arrivalsFrom(CsvFile, String, String): ArrivalSeries
    fun arrivalsFrom(List, String): ArrivalSeries
class Arrivals
    val count: Long
    val cov: Double
    val mean: Long
class Arrivals.Companion
    val none: Arrivals
class Band
    constructor(Double, Double)
    fun contains(Double): Boolean
    val high: Double
    val low: Double
top-level in BandKt
    fun band(Runs, Statistic): Band
class Bucket
    val count: Long
    val trace: String
    val upperBound: Long
class Capacity
    constructor(List)
    val curve: List
    val limitedBy: Goal
    val rate: Rate
    val voided: Boolean
interface Change
    val step: String
class Change.Added : Change
    constructor(String)
    val step: String
class Change.Better : Change
    val before: Long
    val interval: Interval
    val now: Long
    val step: String
class Change.Gone : Change
    constructor(String)
    val step: String
class Change.Indistinguishable : Change
    val before: Long
    val now: Long
    val step: String
class Change.Worse : Change
    val before: Long
    val interval: Interval
    val now: Long
    val step: String
class Clock : Enum
    val ResponseTime: Clock
    val ServiceTime: Clock
    val entries: EnumEntries
    fun valueOf(String): Clock
    fun values(): Array<Clock>
interface Comparison
class Comparison.Compared : Comparison
    constructor(List, Machine, Machine, Probe, Probe)
    val before: Machine
    val beforeProbe: Probe
    val caveat: String
    val changes: List
    val now: Machine
    val nowProbe: Probe
    val slowdown: Double
class Comparison.NotComparable : Comparison
    constructor(String)
    val why: String
top-level in ComparisonKt
    fun against(RunResult, RunResult, Double): Comparison
class Completing
    val drainingFor: Long
    val from: Completions
    val step: String
interface Completions
    fun poll(Long): List
interface Concurrency
class Concurrency.Absent : Concurrency
    constructor(String)
    val because: String
class Concurrency.Measured : Concurrency
    constructor(Double, Double, Double, Int)
    val agrees: Boolean
    val backlog: Double
    val fromResponseTime: Double
    val fromServiceTime: Double
    val observed: Double
    val ratio: Double
    val samples: Int
top-level in ConcurrencyKt
    val LAW_TOLERANCE: Double
    fun getConcurrency(RunResult): Concurrency
interface Correlation
    fun of(Session): Long
top-level in CsvFeedingKt
    fun feeding(CsvFile, SessionKey, Function1): Feeder
    fun feeding(CsvFile, Array<SessionKey>): Feeder
class CsvFile
    fun column(String): List
    val columns: List
    val rows: Int
top-level in CsvKt
    fun csv(Path): CsvFile
class Difference
    val acceptable: Double
    val baselineMachine: Machine
    val baselineRuns: Int
    val before: Double
    val caveat: String
    val interval: Spread
    val machine: Machine
    val now: Double
    val ratio: Double
    val refused: Tell.CannotTell
    val runs: Int
    val statistic: Statistic
    val verdict: Tell
    fun judgedAt(Double): Tell
top-level in DifferenceKt
    fun against(Runs, Runs, Statistic, Double, Floor): Difference
    fun explained(Difference, Double): String
    fun notWorseThan(Difference, Double, Boolean): Boolean
interface Engine
    fun run(Simulation): RunResult
interface Exclusivity
class Exclusivity.Calibrating : Exclusivity
class Exclusivity.Draining : Exclusivity
class Exclusivity.Idle : Exclusivity
class Exclusivity.Running : Exclusivity
class Exclusivity.Waiting : Exclusivity
    constructor(Instant)
    val since: Instant
top-level in ExclusivityKt
    fun leadsTo(Exclusivity, Exclusivity): Boolean
    fun then(Exclusivity, Exclusivity): Exclusivity
class FailuresOf
    fun under(Double): Goal
interface Feeder
    fun forUser(Long): Session
class Feeder.Companion
    val empty: Feeder
top-level in FeederKt
    fun feed(SessionKey, Function1): Feeder
    fun feedFrom(SessionKey, List): Feeder
    fun plus(Feeder, Feeder): Feeder
class Floor
    val UNUSABLE: Double
    constructor(Double, Timing, Probe)
    val absolute: Long
    val hiccups: Timing
    val probe: Probe
    val resolution: Double
    val supportsAClaim: Boolean
    fun movementAt(Long): Long
    fun resolves(Double): Boolean
    fun resolves(Double, Long): Boolean
    fun separates(Long, Long): Boolean
    fun supports(Long): Boolean
class Floor.Companion
top-level in FloorKt
    fun resolutionOf(List): Double
interface Goal
    val asked: Goal
    val described: String
    val overSteadySegment: Boolean
    fun judge(RunResult): Verdict
    fun judgeAll(RunResult): List
class Goal.FailureRateUnder : Goal
    val described: String
    val overSteadySegment: Boolean
    val share: Double
    val step: String
    fun judge(RunResult): Verdict
class Goal.GoodputAtLeast : Goal
    val clock: Clock
    val described: String
    val overSteadySegment: Boolean
    val share: Double
    val step: String
    val under: Long
    fun judge(RunResult): Verdict
class Goal.InEveryStage : Goal
    constructor(Goal)
    val asked: Goal
    val described: String
    val of: Goal
    val overSteadySegment: Boolean
    fun judge(RunResult): Verdict
    fun judgeAll(RunResult): List
class Goal.KeptSchedule : Goal
    val described: String
    val overSteadySegment: Boolean
    fun judge(RunResult): Verdict
class Goal.PercentileUnder : Goal
    val clock: Clock
    val described: String
    val limit: Long
    val overSteadySegment: Boolean
    val percentile: String
    val step: String
    fun judge(RunResult): Verdict
top-level in GoalKt
    fun failureRate(String): FailuresOf
    val failureRate: FailuresOf
    fun getInEveryStage(Goal): Goal
    val keptSchedule: Goal
    fun getPercent(Number): Double
    fun goodput(String, Long, Clock): GoodputOf
    fun p50(String, Clock): PercentileOf
    fun p95(String, Clock): PercentileOf
    fun p99(String, Clock): PercentileOf
    fun p999(String, Clock): PercentileOf
top-level in GoodputKt
    fun goodput(RunResult, Long, Clock): Rate
    fun goodput(StepStats, Long, Long, Clock): Double
    fun met(StepStats, Long, Clock): Met
class GoodputOf : Statistic
    fun atLeast(Double): Goal
    val described: String
    val higherIsWorse: Boolean
    val lessIs: String
    val moreIs: String
    fun magnitudeOf(Double): Duration
    fun noiseIn(Floor): Double
    fun read(Samples): Double
    fun samplesIn(RunResult): Samples
interface Headroom
class Headroom.Absent : Headroom
    constructor(String)
    val because: String
class Headroom.Measured : Headroom
    constructor(Long, Long)
    val limit: Long
    val peak: Long
    val used: Double
class Histogram
    val COARSE_PRECISION: Double
    val PRECISION: Double
    constructor()
    fun distribution(): List
    val count: Long
    val max: Long
    val overflowed: Long
    val precision: Double
    val slots: Int
    val subBuckets: Int
    fun merge(Histogram)
    fun percentile(Double): Long
    fun record(Long)
    fun record(Long, String)
    fun slotOf(Long): Int
class Histogram.Companion
    fun coarse(): Histogram
    val ceiling: Long
    fun of(Double): Histogram
class InMemoryCompletions : Completions
    constructor()
    fun observe(Long)
    fun poll(Long): List
interface InjectionProfile
    val over: Long
class InjectionProfile.ClosedUsers : InjectionProfile
    val count: Int
    val over: Long
class InjectionProfile.ConstantRate : InjectionProfile
    val over: Long
    val perSecond: Double
class InjectionProfile.RampRate : InjectionProfile
    val from: Double
    val over: Long
    val to: Double
class InjectionProfile.Randomized : InjectionProfile
    constructor(InjectionProfile, Long)
    val of: InjectionProfile
    val over: Long
    val seed: Long
class InjectionProfile.Replay : InjectionProfile
    val from: Long
    val over: Long
    val scaled: Double
    val series: ArrivalSeries
    val window: Duration
class InjectionProfile.Stages : InjectionProfile
    constructor(List)
    val over: Long
    val stages: List
top-level in InjectionProfileKt
    fun constantRate(Double, Long): InjectionProfile.ConstantRate
    fun departures(InjectionProfile): Sequence
    fun getEndRate(InjectionProfile): Double
    fun getSeeds(InjectionProfile): List
    fun getStartRate(InjectionProfile): Double
    fun hold(Double, Long): InjectionProfile.ConstantRate
    fun rampRate(Double, Double, Long): InjectionProfile.RampRate
    fun randomized(InjectionProfile, Long): InjectionProfile.Randomized
    fun replaying(ArrivalSeries, Long, Duration, Double): InjectionProfile.Replay
    fun then(InjectionProfile, InjectionProfile): InjectionProfile
    fun thenRampTo(InjectionProfile, Double, Long): InjectionProfile
    fun userCount(InjectionProfile): Long
    fun users(Int, Long): InjectionProfile.ClosedUsers
class Interval
    val high: Long
    val low: Long
    fun overlaps(Interval): Boolean
top-level in IntervalKt
    fun interval(Timing, Double): Interval
class Limits
    constructor()
    constructor(Headroom, Headroom, Headroom)
    val all: List
    val cpu: Headroom
    val openFiles: Headroom
    val ports: Headroom
class Limits.Companion
    val none: Limits
top-level in LimitsKt
    val TIGHT: Double
    fun ranOutOfRoom(RunResult): Boolean
class Machine
    constructor(Int, String, String, String)
    val arch: String
    val cores: Int
    val jdk: String
    val os: String
class Machine.Companion
    fun here(): Machine
interface Measurement
class Measurement.Absent : Measurement
    constructor(String)
    val because: String
class Measurement.Share : Measurement
    constructor(Double)
    val percent: Double
class Measurement.Took : Measurement
    val duration: Long
interface Met
class Met.Absent : Met
    constructor(String)
    val because: String
class Met.Measured : Met
    constructor(Double)
    val fraction: Double
class Offered
    val asked: Double
    val left: Double
    val over: Long
    val share: Double
top-level in OfferedKt
    fun getOffered(RunResult): Offered
class Other : Reason
    val described: String
class Outcome
    constructor(Timing, Timing, Map)
    val count: Long
    val reasons: Map
    val responseTime: Timing
    val serviceTime: Timing
class Outcome.Companion
    val none: Outcome
class Outstanding
    constructor(Long, Long)
    val inFlight: Long
    val unmatched: Long
class Outstanding.Companion
    val none: Outstanding
class Pending
    constructor()
    fun close(Long, Long): Outstanding
    fun departed(Long, Long, Long)
    fun matched(): List
    fun observed(Long, Long): Duration
class PercentileOf : Statistic
    val described: String
    val higherIsWorse: Boolean
    val lessIs: String
    val moreIs: String
    fun magnitudeOf(Double): Long
    fun noiseIn(Floor): Double
    fun read(Samples): Double
    fun samplesIn(RunResult): Samples
    fun under(Long): Goal
class Plan
    constructor(String, List, InjectionProfile, List)
    constructor(List, List, WarmUp)
    val arms: List
    val closed: Boolean
    val drawn: List
    val goals: List
    val pauses: Boolean
    val plannedInterval: Long
    val plannedRequests: Long
    val plannedUsers: Long
    val plannedWindow: Long
    val profile: InjectionProfile
    val scenario: String
    val steps: List
    val warmUp: WarmUp
class Plan.Companion
    val none: Plan
class PlannedArm
    constructor(String, List, InjectionProfile, Boolean, List, Long, List)
    val drawn: List
    val pauses: Boolean
    val plannedUsers: Long
    val profile: InjectionProfile
    val scenario: String
    val steps: List
    val thinkSeed: Long
    val thinkTimes: List
interface Preview
class Preview.Allowed : Preview
    val hosts: List
    val over: Long
    val peakRate: Rate
    val requestsAtLeast: Long
    val requestsBounded: Boolean
    val untargeted: Int
    val users: Long
class Preview.Refused : Preview
    constructor(Refusal)
    val reason: Refusal
top-level in PreviewKt
    fun preview(Simulation, Allowance): Preview
class Probe
    val MATERIAL: Double
    val took: Long
    fun materiallySlowerThan(Probe): Boolean
    fun timesSlowerThan(Probe): Double
class Probe.Companion
top-level in ProbeKt
    fun calibratedBy(RunResult, Floor): RunResult
interface Progress
    fun aligning(Shard, Long)
    fun climbed(Rung, Int, Long)
    fun searching(Search)
    fun starting(Plan)
    fun tick(Long, Snapshot)
    fun waited(Long)
class Progress.Companion
    val silent: Progress
    fun lines(Long): Progress
top-level in ProgressKt
    fun and(Progress, Progress): Progress
    fun throttled(Progress, Long): Progress
interface Ran
class Ran.Refused : Ran
    constructor(Refusal)
    val reason: Refusal
class Ran.Result : Ran
    constructor(RunResult)
    val result: RunResult
class Rate
    val perSecond: Double
class Rate.Companion
    fun parse(String): Rate
interface Reason
    val described: String
class Refreshing
    val current: Object
    val failures: Long
    val lastFailure: Reason
    fun stop()
class Refreshing.Companion
    fun fixed(Object): Refreshing
top-level in RefreshingKt
    fun refreshing(Long, Function0): Refreshing
interface Refusal
    val described: String
class Refusal.HostNotAllowed : Refusal
    constructor(String, List)
    val allowed: List
    val described: String
    val host: String
class Refusal.OverDuration : Refusal
    val allowed: Long
    val asked: Long
    val described: String
class Refusal.OverRate : Refusal
    val allowed: Double
    val asked: Double
    val described: String
class Refusal.OverRequests : Refusal
    constructor(Long, Long)
    val allowed: Long
    val asked: Long
    val described: String
top-level in RemedyKt
    fun getRemedy(Verdict): String
    fun getScheduleRemedy(RunResult): String
class RunRecorder
    val MAX_REASONS_PER_STEP: Int
    constructor(Instant, Long, Boolean)
    constructor(Instant, Boolean)
    fun arrived(String, Long, Long)
    fun aside(String, Long, Long)
    fun freeze(): RunResult
    fun merge(RunRecorder)
    fun outstanding(String, Outstanding)
    fun record(String, Reason, Long, Long, Long, Boolean, Boolean, Int, String)
    fun shard(): RunRecorder
    fun sinceStart(): Long
class RunRecorder.Companion
class RunResult
    constructor(Instant, Map, Timing, Plan, Arrivals, Machine, Timing, List, List, List, Limits, Probe, Shard, Int)
    fun get(String): StepStats
    val arrivals: Arrivals
    val behind: Timing
    val count: Long
    val failed: Long
    val hiccups: Timing
    val injectors: Int
    val latePerSecond: List
    val limits: Limits
    val machine: Machine
    val metEveryGoal: Boolean
    val ok: Long
    val plan: Plan
    val probe: Probe
    val shard: Shard
    val startedAt: Instant
    val steps: Map
    val timeline: List
    val usersInFlight: List
    val verdicts: List
    fun ran(String): Boolean
top-level in RunResultKt
    val MATERIAL: Double
    fun fellBehind(RunResult): Boolean
    fun getHeldScheduleFor(RunResult): Duration
    fun getInFlight(RunResult): Long
    fun getOwnInterval(RunResult): Long
    fun getPrecision(RunResult): Double
    fun getTimelinePrecision(RunResult): Double
    fun getUnanswered(RunResult): List
    fun getUnmatched(RunResult): Long
    fun lostGround(RunResult): Boolean
    fun timing(Histogram): Timing
    fun timing(List, Double): Timing
class Rung
    val offered: Double
    val outcome: Rung.Outcome
    val rate: Double
    val result: RunResult
    val verdicts: List
class Rung.Outcome : Enum
    val Failed: Rung.Outcome
    val Passed: Rung.Outcome
    val Void: Rung.Outcome
    val entries: EnumEntries
    fun valueOf(String): Rung.Outcome
    fun values(): Array<Rung.Outcome>
class Runs
    constructor(List)
    val afterFirst: List
    val each: List
    val first: RunResult
    val merged: RunResult
    val size: Int
class Runs.Companion
    fun of(Array<RunResult>): Runs
class Said : Reason
    constructor(String)
    val described: String
    val text: String
interface SampleSink
    fun sample(Long, Duration, Reason)
class Samples
    constructor(Timing, Long, Long)
    val count: Long
    val failed: Long
    val timing: Timing
class Scenario
    constructor(String, List)
    val name: String
    val steps: List
class ScenarioBuilder
    fun doIf(Function1, Function1)
    fun during(Long, Function1)
    fun emit(String, Action, Correlation)
    fun exec(String, Action)
    fun exec(String, Function1)
    fun exec(String, Action)
    fun exec(String, Function1)
    fun pause(ThinkTime)
    fun pause(Long)
    fun repeat(Int, Function1)
top-level in ScenarioKt
    fun getDrawsThinkTime(Scenario): Boolean
    fun getPauses(Scenario): Boolean
    fun getStepNames(Scenario): List
    fun getThinkTimes(Scenario): List
    fun scenario(String, Function1): Scenario
    fun step(String): String
class Search
    fun atMostAfter(Int): Long
    val feeder: Feeder
    val goals: List
    val holding: Long
    val rungs: List
    val scenario: Scenario
    val upTo: Double
    val warmUp: WarmUp
    val worstCase: Long
top-level in SearchKt
    fun at(Search, Double): Simulation
    fun fedBy(Search, Feeder): Search
    fun judgedBy(Search, Function1, Function1): Capacity
    fun sustainable(Scenario, Double, Long, List): Search
    fun warmingUp(Search, Long): Search
class Second
    constructor(Timing, Timing, Timing, Timing)
    val count: Long
    val failed: Long
    val failedResponseTime: Timing
    val failedServiceTime: Timing
    val ok: Long
    val okResponseTime: Timing
    val okServiceTime: Timing
    val p50: Long
    val p99: Long
    val responseTime: Timing
    val serviceTime: Timing
class Session
    fun get(SessionKey): Object
    fun set(SessionKey, Object): Session
class Session.Companion
    val empty: Session
class SessionKey
    constructor(String, KClass)
    val name: String
    val type: KClass
top-level in SessionKt
    fun sessionKey(String, KClass): SessionKey
class Shape
    constructor(String, Long)
    val description: String
    val seed: Long
class Shard
    val heldFor: Duration
    val index: Int
    val of: Int
    val startingAt: Instant
    fun sends(Long): Boolean
top-level in ShardKt
    fun sharded(Simulation, Int, Int, Instant): Simulation
class Shards
    val each: List
    val merged: RunResult
    val of: Int
    val startedApart: Long
    val tolerating: Long
    val worst: RunResult
    fun lostGround(): Boolean
class Shards.Companion
    val tOLERABLE_SKEW: Long
class Share
    fun getDescribed(Double): String
    val percent: Double
class Simulation
    constructor(Scenario, InjectionProfile, Feeder, List, Completing, WarmUp)
    constructor(List, List, Completing, WarmUp, Shard)
    val arms: List
    val completing: Completing
    val feeder: Feeder
    val goals: List
    val over: Long
    val profile: InjectionProfile
    val shard: Shard
    val warmUp: WarmUp
top-level in SimulationKt
    fun at(Scenario, InjectionProfile.ClosedUsers): Simulation
    fun at(Scenario, Double, Long): Simulation
    fun completing(Simulation, String, Completions, Long): Simulation
    fun drawing(Simulation, Array<Shape>): Simulation
    fun expecting(Simulation, Array<Goal>): Simulation
    fun fedBy(Simulation, Feeder): Simulation
    fun getClosed(Simulation): Boolean
    fun getPerMinute(Number): Double
    fun getPerSecond(Number): Double
    fun injecting(Scenario, InjectionProfile): Simulation
    fun plan(Simulation): Plan
    fun plus(Simulation, Simulation): Simulation
    fun requireSeededThinking(Simulation)
    fun thinkingFrom(Simulation, Long): Simulation
    fun userCount(Simulation): Long
    fun warmingUp(Simulation, Long): Simulation
class Snapshot
    val behind: Long
    val departed: Long
    val ended: Boolean
    val failed: Long
    val inFlight: Long
    val requests: Long
    val scheduled: Long
class Spread
    constructor(Double, Double)
    fun contains(Double): Boolean
    val high: Double
    val low: Double
class Stage
    val alignedToSeconds: Boolean
    val count: Long
    val failed: Long
    val from: Long
    val index: Int
    val of: Int
    val ok: Long
    val planned: Long
    val profile: InjectionProfile
    val responseTime: Timing
    val serviceTime: Timing
    val until: Long
top-level in StageKt
    fun getStages(RunResult): List
interface Statistic
    val described: String
    val higherIsWorse: Boolean
    val lessIs: String
    val moreIs: String
    fun magnitudeOf(Double): Duration
    fun noiseIn(Floor): Double
    fun read(Samples): Double
    fun samplesIn(RunResult): Samples
interface SteadyState
    val LEAST_INTERVALS: Int
    val TOLERANCE: Double
class SteadyState.Companion
    val LEAST_INTERVALS: Int
    val TOLERANCE: Double
class SteadyState.From : SteadyState
    val offset: Long
class SteadyState.NeverSettled : SteadyState
    constructor(String)
    val why: String
top-level in SteadyStateKt
    fun getSteady(RunResult): RunResult
    fun getSteadyState(RunResult): SteadyState
    fun steadyState(List): SteadyState
interface Step
class Step.During : Step
    val duration: Long
    val steps: List
class Step.Emit : Step
    constructor(String, Action, Correlation)
    val action: Action
    val correlation: Correlation
    val name: String
class Step.Exec : Step
    constructor(String, Action)
    val action: Action
    val name: String
class Step.Pause : Step
    constructor(ThinkTime)
    val duration: Long
    val think: ThinkTime
class Step.Repeat : Step
    constructor(Int, List)
    val steps: List
    val times: Int
class Step.When : Step
    constructor(Function1, List)
    val predicate: Function1
    val steps: List
class StepName
    val name: String
interface StepResult
    val attempts: Int
    val produced: Long
    val queued: Long
    val sampled: Boolean
    val session: Session
    val trace: String
class StepResult.Failed : StepResult
    val attempts: Int
    val produced: Long
    val queued: Long
    val reason: Reason
    val sampled: Boolean
    val session: Session
    val trace: String
class StepResult.Ok : StepResult
    val attempts: Int
    val produced: Long
    val queued: Long
    val sampled: Boolean
    val session: Session
    val trace: String
class StepScope
    constructor(Session, SampleSink, List)
    fun attempted()
    fun fail(Reason)
    fun fail(String)
    fun get(SessionKey): Object
    val narrating: Boolean
    val session: Session
    fun note(String)
    fun produced(Long)
    fun queued(Long)
    fun result(): StepResult
    fun sample(Long, Duration, Reason)
    fun set(SessionKey, Object)
    fun traced(String)
class StepStats
    constructor(String, Outcome, Outcome, Timing, Timing, Long, Long, Long, Timing, Long, Long, Long, List)
    fun failedWith(Reason): Long
    val attempts: Long
    val count: Long
    val failed: Outcome
    val inFlight: Long
    val name: String
    val ok: Outcome
    val produced: Long
    val queued: Timing
    val reached: Long
    val responseTime: Timing
    val serviceTime: Timing
    val streamed: Boolean
    val timeline: List
    val unmatched: Long
    val visits: Long
interface Tail
class Tail.Absent : Tail
    constructor(String)
    val because: String
class Tail.Measured : Tail
    val duration: Long
interface Targeted
    val host: String
    val hosts: List
interface Tell
class Tell.Better : Tell
class Tell.CannotTell : Tell
    constructor(String, String)
    val why: String
    val wouldChangeIt: String
class Tell.Worse : Tell
interface ThinkTime
    fun drawnFrom(Random): Long
    val mean: Long
class ThinkTime.Constant : ThinkTime
    fun drawnFrom(Random): Long
    val duration: Long
    val mean: Long
class ThinkTime.Exponential : ThinkTime
    fun drawnFrom(Random): Long
    val mean: Long
class ThinkTime.Lognormal : ThinkTime
    fun drawnFrom(Random): Long
    val mean: Long
    val median: Long
    val sigma: Double
class ThinkTime.Uniform : ThinkTime
    fun drawnFrom(Random): Long
    val from: Long
    val mean: Long
    val until: Long
top-level in ThinkTimeKt
    fun constant(Long): ThinkTime
    fun exponential(Long): ThinkTime
    fun lognormal(Long, Double): ThinkTime
    fun uniform(Long, Long): ThinkTime
class Threw : Reason
    constructor(String)
    val described: String
    val type: String
class TimedOut : Reason
    val described: String
class Timing
    val SAMPLES_FOR_P999: Long
    fun exemplar(Double): String
    val count: Long
    val distribution: List
    val max: Long
    val mean: Long
    val p50: Long
    val p95: Long
    val p99: Long
    val p999: Tail
    val precision: Double
    fun percentile(Double): Long
    fun share(Long): Met
class Timing.Companion
    val none: Timing
class Traceparent
    fun idIn(String): String
    fun next(): String
top-level in TraceparentKt
    val SYNTHETIC: String
class Trend
    val acceptable: Double
    val comparisons: Int
    val ends: Difference
    val pairs: List
    val points: List
    val segments: List
    val statistic: Statistic
    val steps: List
    val stepsExpectedFromNoise: Double
class Trend.Point
    constructor(String, Runs)
    fun band(Statistic): Band
    val label: String
    val machine: Machine
    val probe: Probe
    val runs: Runs
    fun reading(Statistic): Double
class Trend.Step
    constructor(Trend.Point, Trend.Point, Difference)
    val difference: Difference
    val from: Trend.Point
    val to: Trend.Point
class Verdict
    constructor(Goal, Boolean, Measurement, Double, Stage, Tell.CannotTell)
    val goal: Goal
    val measured: Measurement
    val met: Boolean
    val overBy: Double
    val refused: Tell.CannotTell
    val stage: Stage
class Verdict.Companion
class WarmUp
    val over: Long
```

### `io.github.matthewjones372:proofload-engine`

```text
top-level in CalibrationKt
    fun calibrate(Long): Floor
top-level in ExclusiveKt
    fun exclusive(Engine, Progress): Engine
class Proofload
    constructor()
    constructor(Engine, Progress)
    constructor(Progress)
    fun calibrate(): Floor
    fun run(Search): Capacity
    fun run(Simulation): RunResult
    fun summary(): String
    fun trace(Scenario, Feeder)
top-level in RunWithinKt
    fun runWithin(Proofload, Allowance, Simulation): Ran
top-level in TraceKt
    fun trace(Scenario, Feeder)
class VirtualThreads : Engine
    constructor()
    constructor(Progress)
    fun run(Simulation): RunResult
top-level in VirtualThreadsKt
    fun run(Search, Progress): Capacity
    fun run(Simulation, Progress): RunResult
```

### `io.github.matthewjones372:proofload-export`

```text
class Density : Enum
    val Full: Density
    val Summary: Density
    val entries: EnumEntries
    fun valueOf(String): Density
    fun values(): Array<Density>
top-level in HistogramLogKt
    fun histogramLog(RunResult): String
    fun writeHistogramLog(RunResult, Path): Path
top-level in OpenMetricsKt
    fun openMetrics(RunResult, String): String
    fun writeOpenMetrics(RunResult, Path, String): Path
top-level in RunJsonKt
    fun json(RunResult, Density): String
    fun writeJson(RunResult, Path, Density): Path
```

### `io.github.matthewjones372:proofload-grpc`

```text
class Answers : StreamObserver
    constructor()
    fun onCompleted()
    fun onError(Throwable)
    fun onNext(Object)
class Grpc
    fun call(MethodDescriptor, Function0): GrpcAction
    fun deadline(Long): Grpc
    val channel: Channel
    val managed: ManagedChannel
    val target: String
    fun over(ManagedChannel): Grpc
    fun target(String): Grpc
    fun traced(): Grpc
class GrpcAction : Action
    val name: String
    fun run(StepScope)
top-level in GrpcActionKt
    fun exec(ScenarioBuilder, GrpcAction)
    fun send(StepScope, GrpcAction): Object
top-level in GrpcKt
    val grpc: Grpc
class GrpcServerStream
    val name: String
class GrpcStatus : Reason
    constructor(Status.Code)
    val code: Status.Code
    val described: String
class GrpcStream : Action
    val name: String
    fun run(StepScope)
class NoCadence : Reason
    val described: String
class NoFirstAnswer : Reason
    val described: String
class NotSending : Reason
    val described: String
class NotStreaming : Reason
    val described: String
class Stream
    val matched: Long
    val unsolicited: Long
    fun outstanding(Long): Outstanding
class StreamEnded : Reason
    val described: String
top-level in StreamsKt
    fun awaiting(ScenarioBuilder, String, Int, Long)
    fun cadence(ScenarioBuilder, String, Int, Long)
    fun done(ScenarioBuilder, String)
    fun firstAnswer(ScenarioBuilder, String, Long)
    val streaming: SessionKey
    fun open(ScenarioBuilder, GrpcServerStream, Object)
    fun open(ScenarioBuilder, GrpcStream)
    fun send(ScenarioBuilder, String, Object)
    fun serverStream(Grpc, MethodDescriptor, Function2): GrpcServerStream
    fun stream(Grpc, MethodDescriptor, Function1): GrpcStream
```

### `io.github.matthewjones372:proofload-grpc-dynamic`

```text
class DeclaredGrpcStatus : Reason
    constructor(Status.Code)
    val code: Status.Code
    val described: String
class DynamicCall : Action
    fun declaring(Array<Status.Code>): DynamicCall
    fun expecting(Status.Code): DynamicCall
    val name: String
    fun run(StepScope)
top-level in DynamicCallKt
    fun call(Grpc, Descriptors.MethodDescriptor, String): DynamicCall
    fun call(Grpc, Schema, String, String): DynamicCall
    fun exec(ScenarioBuilder, DynamicCall)
    fun send(StepScope, DynamicCall): DynamicMessage
top-level in MessagesKt
    fun asJson(Message): String
    fun messageFrom(Descriptors.Descriptor, String): DynamicMessage
top-level in ReflectedKt
    fun reflected(Grpc, Long): Schema
class Schema
    val methods: List
    fun method(String): Descriptors.MethodDescriptor
top-level in SchemaKt
    fun descriptorSet(Path): Schema
    fun descriptorSet(Array<Byte>): Schema
```

### `io.github.matthewjones372:proofload-http`

```text
interface Body
class Body.Streamed : Body
    constructor(Long, Function0)
    val bytes: Long
    val open: Function0
class Body.Text : Body
    constructor(String)
    val text: String
class CheckFailed : Reason
    constructor(String)
    val check: String
    val described: String
class DeclaredStatus : Reason
    constructor(Int)
    val code: Int
    val described: String
class EventStream
    val delivered: Long
    val heartbeats: Long
top-level in EventsKt
    fun cadence(ScenarioBuilder, String, Int, Long)
    fun firstEvent(ScenarioBuilder, String, Long)
    val eventStream: SessionKey
    fun open(ScenarioBuilder, String, SseTarget)
    fun stopReading(ScenarioBuilder, String)
interface Exchange
class Exchange.Answered : Exchange
    constructor(Response)
    val response: Response
class Exchange.Failed : Exchange
    constructor(Reason)
    val reason: Reason
class Http
    fun baseUrl(String): Http
    fun delete(String): HttpAction
    fun get(String): HttpAction
    fun head(String): HttpAction
    fun over(Transport): Http
    fun patch(String): HttpAction
    fun post(String): HttpAction
    fun put(String): HttpAction
    fun traced(): Http
    fun withCookies(): Http
class HttpAction : Action, Targeted
    fun body(String): HttpAction
    fun bodyFrom(Long, Function0): HttpAction
    fun capture(SessionKey, Function1): HttpAction
    fun checking(String, Function1): HttpAction
    fun declaring(Array<Int>): HttpAction
    fun discardingBody(): HttpAction
    fun expecting(Int): HttpAction
    fun following(Int): HttpAction
    val host: String
    val name: String
    fun header(String, String): HttpAction
    fun retrying(Int, Function1, Long): HttpAction
    fun run(StepScope)
    fun timeout(Duration): HttpAction
top-level in HttpActionKt
    fun exec(ScenarioBuilder, HttpAction)
    fun send(StepScope, HttpAction): Response
top-level in HttpKt
    val http: Http
class HttpStatus : Reason
    constructor(Int)
    val code: Int
    val described: String
class JdkHttpClient : Transport
    constructor()
    constructor(HttpClient)
    fun exchange(Request): Exchange
class NoFirstEvent : Reason
    val described: String
class NotStreaming : Reason
    val described: String
class NothingCaptured : Reason
    constructor(String)
    val described: String
    val key: String
class Request
    val body: Body
    val discardingBody: Boolean
    val headers: Map
    val method: String
    val timeout: Long
    val uri: URI
class Response
    constructor(Int, String, Map, Long)
    constructor(Int, List, String)
    val body: String
    val bytes: Long
    val status: Int
    fun header(String): String
class Sse
    fun at(String): SseTarget
    fun baseUrl(String): Sse
    fun traced(): Sse
top-level in SseKt
    val sse: Sse
class SseTarget
class StreamEnded : Reason
    val described: String
class TooManyRedirects : Reason
    constructor(Int)
    val described: String
    val max: Int
interface Transport
    fun exchange(Request): Exchange
class UnfilledPath : Reason
    constructor(String)
    val described: String
    val placeholder: String
```

### `io.github.matthewjones372:proofload-java`

```text
class Actions
    fun of(Consumer): Action
class Goals
    fun failureRateUnder(Double): Goal
    fun failureRateUnder(StepName, Double): Goal
    fun goodputAtLeast(StepName, Duration, Double): Goal
    fun goodputAtLeast(StepName, Duration, Double, Clock): Goal
    fun p50Under(StepName, Duration): Goal
    fun p50Under(StepName, Duration, Clock): Goal
    fun p95Under(StepName, Duration): Goal
    fun p95Under(StepName, Duration, Clock): Goal
    fun p999Under(StepName, Duration): Goal
    fun p999Under(StepName, Duration, Clock): Goal
    fun p99Under(StepName, Duration): Goal
    fun p99Under(StepName, Duration, Clock): Goal
class Https
    fun baseUrl(String): Http
    fun capturing(HttpAction, SessionKey, Function): HttpAction
    fun checking(HttpAction, String, Function): HttpAction
class Offereds
    fun asked(Offered): Rate
    fun left(Offered): Rate
    fun of(RunResult): Offered
    fun over(Offered): Duration
class Proofload
    fun create(): Proofload
class Rates
    fun perMinute(Double): Rate
    fun perSecond(Double): Rate
class Results
    fun count(RunResult, StepName): Long
    fun failed(RunResult, StepName): Long
    fun max(RunResult, StepName): Duration
    fun max(RunResult, StepName, Clock): Duration
    fun ok(RunResult, StepName): Long
    fun p50(RunResult, StepName): Duration
    fun p50(RunResult, StepName, Clock): Duration
    fun p95(RunResult, StepName): Duration
    fun p95(RunResult, StepName, Clock): Duration
    fun p99(RunResult, StepName): Duration
    fun p99(RunResult, StepName, Clock): Duration
    fun ran(RunResult, StepName): Boolean
    fun verdicts(RunResult): List
class Scenarios
    fun named(String): Scenarios.Builder
class Scenarios.Builder
    fun build(): Scenario
    fun exec(StepName, Action): Scenarios.Builder
    fun exec(String, Action): Scenarios.Builder
    fun pause(Duration): Scenarios.Builder
class Searches
    fun offered(Rung): Rate
    fun rate(Capacity): Rate
    fun rate(Rung): Rate
    fun sustainable(Scenario, Rate, Duration, List): Search
    fun warmingUp(Search, Duration): Search
class SessionKeys
    fun of(Class, String): SessionKey
class Shares
    fun percent(Double): Share
class Simulations
    fun at(Scenario, Rate, Duration): Simulation
    fun at(Scenario, Rate, Duration, Array<Goal>): Simulation
class Steps
    fun named(String): StepName
```

### `io.github.matthewjones372:proofload-jdbc`

```text
class Database
    fun query(String): JdbcAction
    fun update(String): JdbcAction
class Jdbc
    fun on(DataSource): Database
class JdbcAction : Action
    fun binding(Function1): JdbcAction
    val name: String
    fun run(StepScope)
top-level in JdbcKt
    fun exec(ScenarioBuilder, JdbcAction)
    val jdbc: Jdbc
top-level in ReadingKt
    fun getRows(StepStats): Long
    fun getWaitedForPool(StepStats): Timing
class SqlState : Reason
    constructor(String)
    val code: String
    val described: String
```

### `io.github.matthewjones372:proofload-junit5`

```text
top-level in DifferencesKt
    fun assertNotWorseThan(Difference, Double, Boolean)
class LoadRunSummary : RuntimeException
interface LoadTest : Annotation
class ProofloadExtension : ParameterResolver, TestExecutionExceptionHandler
    constructor()
    fun handleTestExecutionException(ExtensionContext, Throwable)
    fun resolveParameter(ParameterContext, ExtensionContext): Proofload
    fun supportsParameter(ParameterContext, ExtensionContext): Boolean
interface RunsOn
    val engine: Engine
```

### `io.github.matthewjones372:proofload-kafka`

```text
class Acks : Enum
    val All: Acks
    val Leader: Acks
    val None: Acks
    val entries: EnumEntries
    fun valueOf(String): Acks
    fun values(): Array<Acks>
class BrokerRefused : Reason
    constructor(String)
    val described: String
    val what: String
top-level in ConsumingKt
    fun completions(Topic, Consumer): Completions
class Header
    val name: String
class Kafka
    fun acks(Acks): Kafka
    fun brokers(String): Kafka
    val producer: Producer
    fun over(Producer): Kafka
    fun setting(String, String): Kafka
    fun topic(String): Topic
top-level in KafkaKt
    val kafka: Kafka
class NothingToSend : Reason
    val described: String
top-level in ProduceKt
    fun emit(ScenarioBuilder, String, Topic, Correlation)
    fun produce(ScenarioBuilder, String, Topic)
class Topic
    fun correlatedBy(String): Topic
    val name: String
    fun keyed(Function1): Topic
    fun value(Function1): Topic
```

### `io.github.matthewjones372:proofload-kotest`

```text
class NotWorseThan : Matcher
    val acceptable: Double
    val orCannotTell: Boolean
    fun test(Difference): MatcherResult
top-level in ProofloadKt
    fun proofload(Engine): Proofload
    fun proofload(Continuation): Object
```

### `io.github.matthewjones372:proofload-mcp`

```text
top-level in ServerKt
    fun main()
```

### `io.github.matthewjones372:proofload-openapi`

```text
top-level in DocumentKt
    fun planFromDocument(String, String, String, Set, Long): Declaration
    fun planFromDocument(Path, String): Declaration
top-level in LegalKt
    fun legalFor(Map, String, Object, Long): String
```

### `io.github.matthewjones372:proofload-otel`

```text
top-level in LiveKt
    fun otlpEvery(Long, String, String, Long): Progress
top-level in OtlpKt
    fun metricData(RunResult, String): List
    fun sendOtlp(RunResult, String, String, Long): Sent
interface Sent
class Sent.Accepted : Sent
class Sent.Refused : Sent
    constructor(String)
    val why: String
```

### `io.github.matthewjones372:proofload-pelican`

```text
top-level in ProofloadTransportKt
    fun proofloadTransport(RunRecorder, List, Duration): ClientTransport
class Status : Reason
    constructor(Int)
    val code: Int
    val described: String
```

### `io.github.matthewjones372:proofload-plan`

```text
class Declaration
    val VERSION: String
    constructor(String, String, String, String, List, DeclaredLoad, List, Map, Long)
    val baseUrl: String
    val brokers: String
    val draw: Map
    val goals: List
    val load: DeclaredLoad
    val scenario: String
    val seed: Long
    val steps: List
    val version: String
class Declaration.Companion
top-level in DeclarationKt
    fun asSimulation(Declaration, List): Simulation
    fun requests(Declaration): List
interface DeclaredDraw
class DeclaredDraw.Digits : DeclaredDraw
    constructor(Int)
    val count: Int
class DeclaredDraw.OneOf : DeclaredDraw
    constructor(List)
    val values: List
class DeclaredDraw.Uniform : DeclaredDraw
    constructor(Long, Long)
    val from: Long
    val keys: Long
class DeclaredDraw.Uuids : DeclaredDraw
class DeclaredDraw.Zipf : DeclaredDraw
    constructor(Long, Double)
    val keys: Long
    val skew: Double
interface DeclaredGoal
    val step: String
class DeclaredGoal.FailureRate : DeclaredGoal
    constructor(String, Double)
    val step: String
    val under: Double
class DeclaredGoal.Percentile : DeclaredGoal
    val percentile: String
    val step: String
    val under: Long
interface DeclaredLoad
class DeclaredLoad.Constant : DeclaredLoad
    val over: Long
    val rate: Double
class DeclaredLoad.Ramp : DeclaredLoad
    val from: Double
    val over: Long
    val to: Double
class DeclaredLoad.Staged : DeclaredLoad
    constructor(List)
    val stages: List
interface DeclaredStep
    val name: String
    val pauseAfter: Duration
class DeclaredStep.Completes : DeclaredStep
    val by: String
    val completes: String
    val group: String
    val name: String
    val on: String
    val pauseAfter: Duration
    val within: Long
class DeclaredStep.Produce : DeclaredStep
    val body: String
    val key: String
    val name: String
    val pauseAfter: Duration
    val settings: Map
    val topic: String
class DeclaredStep.Request : DeclaredStep
    val body: String
    val declared: List
    val expecting: Int
    val headers: Map
    val method: String
    val name: String
    val path: String
    val pauseAfter: Duration
top-level in EmittingKt
    fun asKotlin(Declaration, String, String): String
class Lowered
    constructor()
    constructor(List, Feeder, Completing)
    val completing: Completing
    val feeder: Feeder
    val steps: List
interface Lowering
    fun lower(DeclaredStep, Declaration): Lowered
top-level in ReadingKt
    fun readPlan(String): Declaration
    fun readPlan(Path): Declaration
top-level in WritingKt
    fun asYaml(Declaration): String
```

### `io.github.matthewjones372:proofload-plan-kafka`

```text
class KafkaSteps : Lowering
    constructor()
    constructor(Kafka, Consumer)
    fun lower(DeclaredStep, Declaration): Lowered
top-level in KafkaStepsKt
    val kafkaLowerings: List
```

### `io.github.matthewjones372:proofload-record`

```text
class Answer
    constructor(Int, List, String)
    val body: String
    val headers: List
    val status: Int
class Arguments
    constructor(Path, String, Path, String, Regex, Regex)
    val exclude: Regex
    val file: Path
    val har: Path
    val include: Regex
    val out: Path
    val packageName: String
    val scenario: String
class Capture
    constructor(String, String)
    val key: String
    val name: String
class Draft
    constructor(String, List, List)
    val baseUrl: String
    val captures: List
    val elsewhere: List
    val steps: List
top-level in DraftKt
    fun draft(List, Regex, Regex): Draft
class DraftStep
    val absolute: Boolean
    val after: Long
    val body: String
    val captures: List
    val dropped: List
    val droppedParameters: List
    val expecting: Int
    val headers: List
    val maybe: List
    val method: String
    val path: String
    val stoodFor: Int
    val unanswered: Boolean
top-level in EmitKt
    fun asKotlin(Draft, String, String, String): String
top-level in HarKt
    fun readHar(String): List
class Header
    constructor(String, String)
    val name: String
    val value: String
top-level in MainKt
    fun main(Array<String>)
class Recorded
    val answer: Answer
    val at: Long
    val body: String
    val headers: List
    val method: String
    val named: String
    val origin: String
    val path: String
    val query: String
    val target: String
    val url: String
top-level in RedactionKt
    val REDACTED: String
    fun isCredential(Header): Boolean
    fun looksLikeAJwt(String): Boolean
    fun withoutCredentialParameters(String): Pair
    fun withoutSecrets(String): String
```

### `io.github.matthewjones372:proofload-report-github`

```text
top-level in MarkdownKt
    fun markdown(RunResult, Comparison, Floor): String
top-level in PagesKt
    fun writePagesIndex(Path): Path
interface StepSummary
class StepSummary.Appended : StepSummary
    constructor(Path)
    val path: Path
class StepSummary.NotOnActions : StepSummary
top-level in StepSummaryKt
    fun appendToStepSummary(RunResult, Comparison, Floor, Function1): StepSummary
```

### `io.github.matthewjones372:proofload-report-html`

```text
top-level in CapacityPageKt
    fun toHtmlReport(Capacity): String
    fun writeHtmlReport(Capacity, Path): Path
top-level in HtmlReportKt
    fun toHtmlReport(RunResult, Comparison, Floor, List): String
    fun writeHtmlReport(RunResult, Path, Comparison, Floor, List): Path
top-level in TrendPageKt
    fun toHtmlReport(Trend): String
    fun writeHtmlReport(Trend, Path): Path
```

### `io.github.matthewjones372:proofload-websocket`

```text
class Connection
    val matched: Long
    val unsolicited: Long
    fun outstanding(Long): Outstanding
top-level in ConnectionKt
    fun close(ScenarioBuilder, String)
    val connection: SessionKey
    fun open(ScenarioBuilder, String, WsTarget)
class Disconnected : Reason
    val described: String
top-level in MessagesKt
    fun awaiting(ScenarioBuilder, String, Int, Long)
    fun send(ScenarioBuilder, String, WsFrame, Correlation)
class NotConnected : Reason
    val described: String
class Ws
    fun at(String): WsTarget
    fun baseUrl(String): Ws
    fun binary(Array<Byte>): WsFrame
    fun text(String): WsFrame
interface WsFrame
class WsFrame.Binary : WsFrame
class WsFrame.Text : WsFrame
top-level in WsKt
    val ws: Ws
class WsTarget
```

<!-- End of the rendered surface. -->

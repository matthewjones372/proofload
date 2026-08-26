# Evidence

Specs cite this file by key. It exists so that no number in this repository is
one somebody remembered, and so the README can argue for the design without
turning into a literature review.

Two rules. A claim with no primary source does not get made. A source that is a
vendor, or one blog experiment, is marked as such and the marking travels with
the claim wherever it is quoted.

## Load generation and measurement

- `[OPENCLOSED]` Schroeder, Wierman, Harchol-Balter, "Open Versus Closed: A
  Cautionary Tale", NSDI 2006.
  https://www.usenix.org/legacy/event/nsdi06/tech/full_papers/schroeder/schroeder.pdf
  Mean response time under an open model can exceed a closed one by an order of
  magnitude or more, and conclusions about scheduling invert between the two. A
  closed harness caps the queue at its virtual-user count by construction, so an
  optimisation that looks worthless under one can be transformative in
  production.

- `[CO]` Tene, "How NOT to Measure Latency", QCon 2013.
  https://www.infoq.com/presentations/latency-response-time
  Coordinated omission: a generator that stops sending during a stall records one
  slow sample where a real client population would have accumulated thousands.

- `[CO-WRK2]` wrk2. https://github.com/giltene/wrk2
  Measures latency "from the time the transmission should have occurred
  according to the constant throughput configured for the run". This is what
  `responseTime` already does here.

- `[CO-MEASURED]` Wakart, "Fixing YCSB's Coordinated Omission problem", 2015.
  http://psy-lob-saw.blogspot.com/2015/03/fixing-ycsb-coordinated-omission.html
  At a 1000 req/s target with injected delays: uncorrected max latency 44 ms,
  corrected max 46 seconds, same run.

- `[CO-DB]` Friedrich, Wingerath, Ritter, "Coordinated Omission in NoSQL
  Database Benchmarking", BTW 2017.
  https://www.btw2017.informatik.uni-stuttgart.de/slidesandpapers/E4-11-107/paper_web.pdf
  Corrected values roughly an order of magnitude higher at lower percentiles; the
  gap persists at 256 threads and widens with load.

- `[POISSON]` Paxson and Floyd, "Wide Area Traffic: The Failure of Poisson
  Modeling", IEEE/ACM ToN 1995.
  https://web.stanford.edu/class/cs244/papers/paxson1995.pdf
  Session arrivals are well modelled as Poisson; arrivals within a connection are
  not, and the burstiness persists at high multiplexing rather than averaging
  out.

- `[COV]` Brady and Gunther, "How to Emulate Web Traffic Using Standard Load
  Testing Tools", CMG 2016. https://arxiv.org/pdf/1607.05356
  Validate the arrival process you produced by measuring the inter-arrival
  coefficient of variation and confirming it is near 1.0.

- `[INJECTOR]` Hashemian, Krishnamurthy, Arlitt, "Web Workload Generation
  Challenges", HP Labs HPL-2010-163, 2010.
  https://cs.uwaterloo.ca/~brecht/courses/854-Experimental-Performance-Evaluation-2018/readings/new/Workload-Generation-Challenges-HPL-2010-163.pdf
  The generator is routinely the bottleneck, and threads do not make it scalable.
  Cross-check its own numbers against an independent measurement.

## JVM measurement rigour

- `[WARMUP]` Barrett, Bolz-Tereick, Killick, Mount, Tratt, "Virtual Machine
  Warmup Blows Hot and Cold", OOPSLA 2017. https://arxiv.org/abs/1602.00602
  Changepoint analysis over 3,660 process executions of small deterministic
  benchmarks: at most 43.5% of (VM, benchmark) pairs consistently reach a steady
  state of peak performance. "Slowdown", where the steady state is worse than the
  start, and "no steady state" are both real categories, and the same benchmark
  on the same VM behaves differently between processes. The ACM abstract phrases
  the headline differently (43.3 to 56.5% conforming); cite the version read.

- `[WARMUP-JMH]` Traini, Cortellessa, Di Pompeo, Tucci, "Towards effective
  assessment of steady state performance in Java software", EMSE 2023.
  https://arxiv.org/abs/2209.15369
  586 JMH benchmarks, 10 forks each, 5,860 series. 89.1% of forks reach a steady
  state but only 56.5% of benchmarks have every fork reach one. Warm-up
  estimation error against the detected steady state: median 28 s, mean 90 s,
  accurate in 19% of forks. JMH's defaults did worse than the developers'
  estimates.

- `[REPLICATION]` Georges, Buytaert, Eeckhout, "Statistically Rigorous Java
  Performance Evaluation", OOPSLA 2007.
  https://dri.es/files/oopsla07-georges.pdf
  Multiple VM invocations as the unit of replication, discard the first, report
  confidence intervals. Of 50 surveyed papers only 4 reported intervals, and the
  prevalent "best of N" methods were misleading in up to 16% of comparisons.

- `[NOVARIANCE]` `[SPEND]` `[COV-CRITIQUE]` Kalibera and Jones, "Rigorous
  Benchmarking in Reasonable Time", ISMM 2013. https://kar.kent.ac.uk/33611/
  71 of 90 surveyed papers gave no measure of variation at all. Repetitions
  should be spent where the variance is, by a cost-weighted formula; in one
  DaCapo case execution-level variation was 30% against 3% at iteration level.
  They criticise the coefficient-of-variation steady-state heuristic directly,
  with a case where it demanded 247 iterations against the 5 actually needed.

- `[EFFECTSIZE]` Kalibera and Jones, "Quantifying Performance Changes with Effect
  Size Confidence Intervals", arXiv:2007.10899.
  https://ar5iv.labs.arxiv.org/html/2007.10899
  Report "A is 4% plus or minus 1.5% faster than B, with 95% confidence", by
  hierarchical bootstrap over the levels of the experiment, judged against a
  declared practical threshold. An interval straddling the threshold is a
  legitimate third answer, not a failure to decide.

- `[BIAS]` Mytkowicz, Diwan, Hauswirth, Sweeney, "Producing Wrong Data Without
  Doing Anything Obviously Wrong", ASPLOS 2009.
  https://users.cs.northwestern.edu/~robby/courses/322-2013-spring/mytkowicz-wrong-data.pdf
  Changing the byte size of unused environment variables moved a measured speedup
  from 0.88 to 1.09. Randomise what cannot be controlled; interleave arms rather
  than batching them.

- `[DUET]` Bulej, Horky, Tuma, Farquet, Prokopec, "Duet Benchmarking", ICPE 2020.
  https://arxiv.org/abs/2001.05811
  Running both versions in parallel on one machine with synchronised operations
  narrowed confidence intervals 2.3 to 12.5 times on DaCapo and ScalaBench, and
  23.8 to 82.4 times on SPEC CPU 2017.

- `[INTERLEAVE]` Laaber, Scheuner, Leitner, "Software microbenchmarking in the
  cloud. How bad is it really?", EMSE 2019.
  https://link.springer.com/article/10.1007/s10664-019-09681-1
  Variability runs from 0.03% to over 100% depending on benchmark and instance
  type, but same-instance randomised interleaved trials with a rank-sum test
  reliably detect slowdowns of about 10%.

## CI noise and regression detection

- `[CINOISE]` CodSpeed, "Benchmarks in CI without noise". **Vendor source.**
  https://codspeed.io/blog/benchmarks-in-ci-without-noise
  2.66% coefficient of variation on GitHub-hosted runners; a 2% gate fires
  falsely about 45% of the time; roughly 7% is needed for 1% false positives.
  Their bare-metal runners measure 0.56%.

- `[CINOISE-2]` Quansight Labs, GitHub Actions benchmarking study.
  https://labs.quansight.org/blog/github-actions-benchmarks
  Over 16 days: mean performance ratio 1.0, standard deviation 0.05, range 0.51
  to 1.36, 3.7% false positives. Reliable only above 50% regressions unless both
  commits are measured inside the same job.

- `[CINOISE-3]` Akinshin, "Performance stability of GitHub Actions".
  https://aakinshin.net/posts/github-actions-perf-stability/
  100 builds across three OS images; consecutive builds of the same revision
  differed by multiples. Concludes the default runner pool should not be used for
  performance comparison across builds.

- `[CHANGEPOINT]` Daly, Brown, Ingo, O'Leary, Bradford, "The Use of Change Point
  Detection to Identify Software Performance Regressions in a CI System", ICPE
  2020. https://arxiv.org/pdf/2003.00584
  MongoDB's threshold system produced about 2,393 alerts in five months, of which
  24 were useful. E-divisive means over the series brought production false
  positives to roughly 30%, at a 3 to 6 day detection latency.

- `[HUNTER]` `[CANARY]` Fleming et al., "Hunter: Using Change Point Detection to
  Hunt for Performance Regressions", ICPE 2023. https://arxiv.org/pdf/2301.03034
  Use at least 30 days of history, and stabilise the hardware first: "change
  point detection cannot fix noisy data". A canary workload exercising only the
  infrastructure separates a slower runner from a slower service.

## What to measure

- `[GOODPUT]` Cho, Saeed, Fried, Park, Alizadeh, Belay, "Overload Control for
  Microsecond-scale RPCs with Breakwater", OSDI 2020.
  https://www.usenix.org/system/files/osdi20-cho.pdf
  Goodput is "the throughput of requests whose response time is less than the
  SLO". Their harness is open-loop Poisson per client and sweeps demand from 0.1
  to 2 times server capacity.

- `[GOODPUT-2]` AWS Builders' Library, "Using load shedding to avoid overload".
  https://d1.awsstatic.com/builderslibrary/pdfs/using-load-shedding-to-avoid-overload.pdf
  The same definition operationally, plus: a service that has not been load
  tested to the point where it breaks, and far beyond, should be assumed to fail
  in the least desirable way possible.

- `[SLI]` Google SRE Workbook, "Implementing SLOs".
  https://sre.google/workbook/implementing-slos/
  An SLI is good events over total events, which for latency is a share under a
  threshold rather than a percentile in isolation.

- `[SIGNALS]` Google SRE Book, "Monitoring Distributed Systems".
  https://sre.google/sre-book/monitoring-distributed-systems/
  The four golden signals; track failed-request latency separately, because a
  slow error is worse than a fast error; collect latencies into exponentially
  bucketed histograms rather than averages.

- `[TAIL]` Dean and Barroso, "The Tail at Scale", CACM 2013.
  https://www.barroso.org/publications/TheTailAtScale.pdf
  With a 100-way fan-out to servers whose p99 is 1 s, 63% of user requests exceed
  1 s. Their measured table: a single leaf p99 of 10 ms becomes a whole-request
  p99 of 140 ms.

- `[PERCENTILES]` Hartmann, "Statistics for Engineers", ACM Queue 2016.
  https://queue.acm.org/detail.cfm?id=2903468
  A fleet percentile cannot be derived from per-node percentiles. Store
  histograms, which merge, and derive quantiles after merging.

- `[SHAPE]` Same source. A production distribution with a flat mode near 5 ms and
  a second climbing from 10 to 50 ms turned out to be a bug in a session handler.
  The shape carried the diagnosis; no summary statistic would have shown it.

- `[QUEUEING]` Gunther, "How to Quantify Scalability", and the M/M/1 relation
  R = S / (1 - rho). https://www.perfdynamics.com/Manifesto/USLscalability.html
  Latency is hyperbolic in utilisation: twice service time at 50%, ten times at
  90%, twenty times at 95%. The knee is a property of queues, not of the code.

- `[METASTABLE]` Bronson, Aghayev, Charapko, Zhu, "Metastable Failures in
  Distributed Systems", HotOS 2021.
  https://sigops.org/s/conferences/hotos/2021/papers/hotos21-s11-bronson.pdf
  A trigger causes a bad state that persists after the trigger is removed, so the
  rate at which a system recovers is far below the rate at which it broke. Huang
  et al., OSDI 2022, attribute at least 4 of 15 major AWS outages in a decade to
  this class. https://www.usenix.org/system/files/osdi22-huang-lexiang.pdf

## Streaming and pipelines

- `[EVENTTIME]` `[SUSTAINABLE]` Karimov, Rabl, Katsifodimos, Samarev, Heiskanen,
  Markl, "Benchmarking Distributed Stream Data Processing Systems", ICDE 2018.
  https://arxiv.org/abs/1802.08496
  Event-time latency is measured from the event's creation, processing-time
  latency from its arrival at the input operator. Under backpressure the second
  stays artificially low while the first escalates, so the metric that looks best
  is the one that lies. Sustainable throughput is the highest load handled
  without continuously increasing event-time latency, and they find it by
  decreasing the rate rather than pushing until it breaks.

- `[THEODOLITE]` Henning and Hasselbring, "A Configurable Method for
  Benchmarking Scalability of Cloud-Native Applications", EMSE 2022.
  https://link.springer.com/article/10.1007/s10664-022-10162-1
  Demand and capacity metrics: load intensity to minimum resources, and its
  inverse. Five or fewer repetitions and five-minute runs were enough per point,
  which argues for more points rather than longer ones.

- `[LINEARROAD]` Arasu et al., "Linear Road: A Stream Data Management Benchmark",
  VLDB 2004. https://www.vldb.org/conf/2004/RS12P1.PDF
  The L-rating is the maximum scale factor at which the system still meets its
  response time and accuracy requirements. Correctness is part of passing.

- `[ORACLE]` Hesse, Matthies, Perscheid, Uflacker, Plattner, "ESPBench", ICPE
  2021. https://arxiv.org/abs/2103.06775
  A streaming benchmark that ships a result validator, on the principle that a
  throughput number from a pipeline that drops records is worthless.

## JVM runtime

- `[ZGC]` Morling, "Lower Java Tail Latencies With ZGC". **One blog experiment;
  directionally sound, magnitudes illustrative.**
  https://www.morling.dev/blog/lower-java-tail-latencies-with-zgc/
  G1 and ZGC practically identical up to p99; ZGC's advantage appears at p99.9
  and p99.99, with G1 pauses over 20 ms against ZGC around 50 microseconds. It
  reverses under CPU pressure at a very high allocation rate.

- `[HICCUP]` jHiccup. https://www.azul.com/jhiccup/
  Measure platform-induced pauses separately from application latency, so a stall
  in the measuring JVM is attributable rather than invisible.

- `[NANOTIME]` Shipilev, "Nanotrusting the Nanotime".
  https://shipilev.net/blog/2014/nanotrusting-nanotime/
  `System.nanoTime()` costs roughly 25 ns with about 25 ns granularity on Linux,
  which bounds what any per-step timing can resolve.

## Other tools, in their own words

Quote these rather than characterising them. Every one is the tool's own
documentation.

- `[GATLING-PCT]` Gatling, "Latency percentiles for load testing analysis".
  **Vendor source.**
  https://gatling.io/blog/latency-percentiles-for-load-testing-analysis
  Response times are bucketed on arrival as integer milliseconds and percentiles
  computed from bucket counts, with approximation error stated as under 10% of
  the true value. Background: https://github.com/gatling/gatling/issues/2031

- `[GATLING-THROTTLE]` Gatling simulation documentation. **Vendor source.**
  https://docs.gatling.io/concepts/simulation/
  Throttling disables pauses, caps throughput, and pushes excess traffic into an
  unbounded queue.

- `[K6-DROPPED]` Grafana k6, dropped iterations. **Vendor source.**
  https://grafana.com/docs/k6/latest/using-k6/scenarios/concepts/dropped-iterations/
  An arrival-rate executor reports when it could not hit the configured rate.

- `[HYPERFOIL]` Hyperfoil. https://hyperfoil.io/docs/overview/ and
  https://hyperfoil.io/blog/news/2020-12-9-compensation/
  Open model by default, records the situation when it runs out of resources, and
  can report real and compensated latency side by side.

- `[WRK2-MAINT]` wrk2 issue 77, the author on maintenance.
  https://github.com/giltene/wrk2/issues/77

- `[OMB]` OpenMessaging Benchmark, pub delay.
  https://openmessaging.cloud/docs/benchmarks/
  Reports how far production fell behind the ideal schedule. The same idea as
  `fellBehind()`.

## Latency and money, if it is ever needed

Two numbers repeated everywhere have no retrievable primary study: "Amazon found
100 ms cost 1% of sales" and "Google found 500 ms cost 20% of traffic". Neither
goes in this repository. Use these instead.

- `[BRUTLAG]` Brutlag, "Speed Matters for Google Web Search", 2009.
  https://services.google.com/fh/files/blogs/google_delayexp.pdf
  400 ms of injected delay cost 0.59% of daily searches per user, growing to
  0.74% in the second three-week window and persisting after the delay was
  removed.

- `[DELOITTE]` "Milliseconds Make Millions", Deloitte for Google, 2020.
  https://www.thinkwithgoogle.com/_qs/documents/9757/Milliseconds_Make_Millions_report_hQYAbZJ.pdf
  A 0.1 s mobile improvement across 37 brands: retail conversion up 8.4%, travel
  up 10.1%. Report the counter-case too: lead generation conversion moved 1.9%
  the other way.

## What the README should be able to say

Not a spec, because documentation is not a change. But this file exists partly
to make the README arguable, and the argument has an order:

1. **The problem is not that load tools are slow. It is that they are
   agreeable.** Four mechanisms produce numbers wrong in the flattering
   direction: a closed loop that stops sending when the target stalls
   `[OPENCLOSED]`, latency measured from the actual send rather than the
   intended one `[CO]`, a percentile from a sketch too coarse to answer the
   question `[GATLING-PCT]`, and a peak reported as a capacity `[QUEUEING]`.
2. **What Kestrel does about each**, tied to something the reader can open:
   `InjectionProfile.departures()` states every offset before anything is sent;
   `responseTime` counts from the departure the profile promised; `behind` and
   `fellBehind()` make the generator's own backlog a reported number;
   `Histogram.PRECISION` is 1/128, so the error bar on a percentile is 0.78% and
   it is stated rather than implied.
3. **A comparison table about method, not speed.** Columns are criteria:
   workload model, where the latency clock starts, percentile error, does the
   tool report its own saturation, can it say a difference is not resolvable,
   does it detect steady state. Kestrel appears in every row including the ones
   it loses: wrk2 is more correct on coordinated omission than anything here and
   unmaintained `[WRK2-MAINT]`; Hyperfoil reports compensated and real latency
   side by side and Kestrel does not `[HYPERFOIL]`; Gatling has a distributed
   mode and Kestrel has none.
4. **What this tool cannot tell you**, in the voice of spec 0011.

No speed claims, ever. "Kestrel is N times faster" is the genre of statement
this whole design exists to argue against, and one line of it would cost more
credibility than the table buys.

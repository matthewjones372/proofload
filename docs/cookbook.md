# Cookbook

Short recipes, each a few lines of code with a note on why it is written that
way. They live in the guide for their topic; this page is the index.

## Running

- [A load test in JUnit](running.md#junit), [in Kotest](running.md#kotest), [in zio-test](running.md#zio-test)
- [Without a test framework](running.md#without-a-test-framework)
- [See what a scenario does before running it](running.md#see-what-a-scenario-does-before-running-it)
- [Know how long it will take](running.md#know-how-long-it-will-take)
- [Quieten the progress lines](running.md#quieten-the-progress-lines)
- [Watch a long soak from a dashboard](running.md#watch-a-two-hour-soak-from-a-dashboard)
- [One run at a time, on the whole machine](running.md#one-run-at-a-time-on-the-whole-machine)
- [Run on an engine of your own](running.md#run-on-an-engine-of-your-own)

## Shaping the load

- [Flat, ramped and staged](workload.md#flat-ramped-and-staged)
- [A fixed population of looping users](workload.md#fifty-users-looping)
- [Poisson arrivals](workload.md#stop-sending-on-a-metronome)
- [Think time](workload.md#think-time)
- [Replay recorded arrivals](workload.md#replay-the-arrivals-you-actually-had)
- [Loops and conditions](workload.md#loops-and-conditions)
- [Two journeys in one run](workload.md#two-journeys-in-one-run)
- [More than one injector](workload.md#more-than-one-injector)
- [Bound what a run on this machine may do](workload.md#bound-what-a-run-on-this-machine-may-do)

## Data

- [Feed each user its own data](data.md#feed-each-user-its-own-data)
- [A fixed list](data.md#a-fixed-list), [a CSV file](data.md#a-csv-file)
- [Generated data with a stated cardinality and skew](data.md#generated-data-with-a-stated-cardinality-and-skew)
- [Carry a value from one step to the next](data.md#carry-a-value-from-one-step-to-the-next)
- [A token that expires mid-run](data.md#a-token-that-expires-mid-run)

## Requests and protocols

- [Check the body, not just the status](protocols.md#check-the-body-not-just-the-status)
- [Cookies and redirects](protocols.md#cookies-and-redirects)
- [Retries](protocols.md#retries)
- [Large responses](protocols.md#large-responses)
- [Trace context on every request](protocols.md#trace-context)
- [Another HTTP client](protocols.md#another-http-client)
- [Server-sent events](protocols.md#server-sent-events)
- [WebSockets](protocols.md#websockets)
- [gRPC](protocols.md#grpc), [without generated stubs](protocols.md#without-generated-stubs)
- [Kafka, and the answer on another topic](protocols.md#kafka)
- [JDBC](protocols.md#jdbc)
- [Pelican](protocols.md#pelican)
- [Your own client](protocols.md#your-own-client)
- [Work that finishes somewhere else](protocols.md#work-that-finishes-somewhere-else)

## Starting from something other than Kotlin

- [A YAML plan](plans.md#a-plan), [run from the command line](plans.md#the-command-line)
- [From an OpenAPI document](plans.md#from-an-openapi-document)
- [From a HAR recording](plans.md#from-a-har-recording)

## Asking the question

- [Assert, or declare goals](goals-and-results.md#assert-or-declare-goals)
- [Judge a goal in every stage](goals-and-results.md#judge-a-goal-where-it-was-asked)
- [Ask what actually failed](goals-and-results.md#ask-what-actually-failed)
- [Did the generator keep up?](goals-and-results.md#did-the-generator-keep-up)
- [Read only the part that settled](goals-and-results.md#read-only-the-part-that-settled)
- [Find the rate it sustains](goals-and-results.md#find-the-rate-it-sustains)
- [Catch a leak with JFR beside the soak](goals-and-results.md#catch-a-leak-with-jfr-beside-the-soak)

## Keeping the answer

- [Write an HTML report](reports-and-baselines.md#write-an-html-report)
- [A GitHub job summary](reports-and-baselines.md#a-github-job-summary)
- [Publish reports to GitHub Pages](reports-and-baselines.md#publish-the-reports-to-github-pages)
- [A baseline in GitHub Actions](reports-and-baselines.md#a-baseline-in-github-actions)
- [Compare several runs a side](reports-and-baselines.md#more-than-one-run-and-a-verdict-worth-having)
- [Follow a statistic across builds](reports-and-baselines.md#what-a-statistic-has-been-doing)
- [Do not gate a merge on latency](reports-and-baselines.md#do-not-gate-a-merge-on-latency)
- [Send the numbers somewhere else](exporting.md)

## A TODO in code generated from a HAR file

`proofload-record` removes credentials from what it generates. Each credential
header becomes `TODO("the <name> header was dropped; see docs/cookbook.md")`,
which throws when the scenario is built until you replace it. Put a real credential there, from
configuration rather than source, or fetch a token outside the measured path
with [`refreshing`](data.md#a-token-that-expires-mid-run).
[plans.md](plans.md#from-a-har-recording) lists everything the recorder removes.

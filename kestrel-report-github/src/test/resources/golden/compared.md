**1 of 2 steps measurably changed since the last run.**

| Step   |    Was |    Now | Change                |
| :----- | -----: | -----: | :-------------------- |
| browse | 3.00ms | 3.00ms | not distinguishable   |
| pay    | 20.0ms | 30.0ms | worse (28.0ms–33.0ms) |

Compared at p99 of response time. A sampling interval bounds which sample the p99 landed on, given how many there were. It does not bound how far a repeat of this run would land from it, because two runs of one unchanged target drift by the machine as well as by the code. So this says these samples differ, not that the target did — repeated runs are what answers the second.

| Step   | Requests |    OK | Failed |    p50 |    p95 |    p99 |    Max |
| :----- | -------: | ----: | -----: | -----: | -----: | -----: | -----: |
| browse |       10 |    10 |      0 | 2.01ms | 3.01ms | 3.01ms | 3.01ms |
| pay    |      100 |   100 |      0 | 20.1ms | 30.0ms | 30.0ms | 30.0ms |

110 requests, 110 ok, 0 failed. Started 2026-08-26T09:00:00Z.

Latency is response time, measured from the departure the profile promised. Each percentile is the top of its histogram bucket, so it is within 0.78% and never interpolated.

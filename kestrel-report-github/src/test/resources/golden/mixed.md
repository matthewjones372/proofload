**Mix**

| Arm      | Planned users |  Asked | Departed |
| :------- | ------------: | -----: | -------: |
| browse   |           160 | 80.00% |   76.92% |
| checkout |            40 | 20.00% |   23.08% |

**Asked** is the arm's share of the users the plan named. **Departed** is its share of the 195 users the run counted: the most any one step of the arm was reached by, so an arm whose users abandoned it at its first step reports fewer than left.

| Step   | Arm      | Requests | Reached |    OK | Failed |    p50 |    p95 |    p99 |    Max |
| :----- | :------- | -------: | ------: | ----: | -----: | -----: | -----: | -----: | -----: |
| home   | browse   |      150 |     150 |   150 |      0 | 20.1ms | 30.0ms | 30.0ms | 30.0ms |
| search | browse   |      240 |     120 |   240 |      0 | 20.1ms | 30.0ms | 30.0ms | 30.0ms |
| cart   | checkout |       45 |      45 |    45 |      0 | 20.1ms | 30.0ms | 30.0ms | 30.0ms |
| pay    | checkout |       45 |      45 |    45 |      0 | 20.1ms | 30.0ms | 30.0ms | 30.0ms |

480 requests, 480 ok, 0 failed. Started 2026-08-26T09:00:00Z.

Arrivals were evenly spaced, which understates queueing against the same mean rate in production.

Latency is response time, measured from the departure the profile promised. Each percentile is the top of its histogram bucket, so it is within 0.78% and never interpolated.

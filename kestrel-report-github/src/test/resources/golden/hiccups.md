| Step  | Requests | Reached |    OK | Failed |    p50 |    p95 |    p99 |    Max |
| :---- | -------: | ------: | ----: | -----: | -----: | -----: | -----: | -----: |
| pay   |      100 |       — |   100 |      0 | 20.1ms | 30.0ms | 30.0ms | 30.0ms |

The injector's own JVM stalled for 14.0ms at p99 and 30.0ms at worst, measured on a thread no request ran on. A tail that size is this machine as readily as the target.

100 requests, 100 ok, 0 failed. Started 2026-08-26T09:00:00Z.

Latency is response time, measured from the departure the profile promised. Each percentile is the top of its histogram bucket, so it is within 0.78% and never interpolated.

| Step   | Requests | Reached |    OK | Failed |    p50 |    p95 |    p99 |    Max |
| :----- | -------: | ------: | ----: | -----: | -----: | -----: | -----: | -----: |
| browse |       10 |       — |    10 |      0 | 2.01ms | 3.01ms | 3.01ms | 3.01ms |
| pay    |      100 |       — |   100 |      0 | 20.1ms | 30.0ms | 30.0ms | 30.0ms |

```
pay  p50 20.1ms  p99 30.0ms
10ms  ############  100
```

110 requests, 110 ok, 0 failed. Started 2026-08-26T09:00:00Z.

Latency is response time, measured from the departure the profile promised. Each percentile is the top of its histogram bucket, so it is within 0.78% and never interpolated.

| Step     | Requests | Reached |    OK | Failed |    p50 |   p95 |   p99 |   Max |
| :------- | -------: | ------: | ----: | -----: | -----: | ----: | ----: | ----: |
| checkout |     1000 |       — |  1000 |      0 | 10.0ms | 2.00s | 2.00s | 2.00s |

```
checkout  p50 10.0ms  p99 2.00s
 10ms  ############  900
100ms
   1s  #  100
```

1000 requests, 1000 ok, 0 failed. Started 2026-08-26T09:00:00Z.

Latency is response time, measured from the departure the profile promised. Each percentile is the top of its histogram bucket, so it is within 0.78% and never interpolated.

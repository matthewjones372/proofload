> **Behind schedule:** 100ms late at p99, 100ms at worst. The response times below include that backlog.

| Step   | Requests |    OK | Failed |    p50 |    p95 |    p99 |    Max |
| :----- | -------: | ----: | -----: | -----: | -----: | -----: | -----: |
| browse |       10 |    10 |      0 | 2.01ms | 3.01ms | 3.01ms | 3.01ms |
| pay    |      100 |    97 |      3 | 20.1ms | 30.0ms | 30.0ms | 30.0ms |

**Failures**

| Step  | Failure    | Count |
| :---- | :--------- | ----: |
| pay   | status 503 |     3 |

110 requests, 107 ok, 3 failed. Started 2026-08-26T09:00:00Z.

Latency is response time, measured from the departure the profile promised. Each percentile is the top of its histogram bucket, so it is within 0.78% and never interpolated.

| Step      | Requests |    OK | Failed |    p50 |    p95 |    p99 |    Max |
| :-------- | -------: | ----: | -----: | -----: | -----: | -----: | -----: |
| GET /a\|b |        5 |     2 |      3 | 1.00ms | 1.00ms | 1.00ms | 1.00ms |

**Failures**

| Step      | Failure                                  | Count |
| :-------- | :--------------------------------------- | ----: |
| GET /a\|b | unexpected \`\</td\>\` \| status \<500\> |     3 |

5 requests, 2 ok, 3 failed. Started 2026-08-26T09:00:00Z.

Latency is response time, measured from the departure the profile promised. Each percentile is the top of its histogram bucket, so it is within 0.78% and never interpolated.

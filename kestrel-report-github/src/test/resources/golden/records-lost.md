> **Records that never arrived:** settled — 41 unmatched, 12 in flight. An unmatched record is one the sink had the whole drain window to answer for and did not; an in-flight one left too late to be given that window.

| Step      | Requests |    OK | Failed |    p50 |    p95 |    p99 |    Max |
| :-------- | -------: | ----: | -----: | -----: | -----: | -----: | -----: |
| submitted |      113 |   113 |      0 | 20.1ms | 30.0ms | 30.0ms | 30.0ms |
| settled   |       60 |    60 |      0 | 20.1ms | 30.0ms | 30.0ms | 30.0ms |

173 requests, 173 ok, 0 failed. Started 2026-08-26T09:00:00Z.

Latency is response time, measured from the departure the profile promised. Each percentile is the top of its histogram bucket, so it is within 0.78% and never interpolated.

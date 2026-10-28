# Trainer Guide — Fleet Telemetry Platform (Day 2)

This repo = the student starter kit **plus** a reference solution, fault-injection branches and tools.
Students clone the separate **starter** repo. Don't share this one with them before the session.

| Ref | What it is |
|---|---|
| tag `starter` | exactly what students get |
| `main` / tag `solution` | reference solution, both stacks (M1–M3 complete, tests) |
| `incident/inj1-poison-crashloop` | INJ-1 code change (consumer can't survive a bad message) |
| `incident/inj2-odometer-double-conversion` | INJ-2 |
| `incident/inj3-slow-fleet-api` | INJ-3 |
| `incident/inj4-bad-db-config` | INJ-4 |
| `trainer/inject_poison.sh` | publishes schema-v2 "poison" messages straight to Kafka |
| `tools/gen_seed.py` | regenerates `db/init/02_seed.sql` + `data/vehicles.csv` (seed 42) |

---

## T-3 days: trainer smoke test (≈ 20 min, on a machine with internet)

```bash
docker compose up -d && ./scripts/check_env.sh                 # ALL GOOD

# Python reference
cd python && python3 -m venv .venv && source .venv/bin/activate && pip install -r requirements.txt
pytest -q                                                       # expect 26 passed
uvicorn app.main:app --port 8080 &  python -m app.consumer &
cd .. && python3 simulator/simulate.py --vehicles 10 --count 10 --interval 0.2
python3 simulator/simulate.py --mode duplicate --vehicles 5 --count 5
python3 simulator/simulate.py --mode overspeed
curl -s localhost:8080/v1/fleets/1/latest | head -c 300; ./scripts/kafka.sh lag   # LAG 0
# stop both python processes, then:

# Java reference
cd java && mvn -q test && mvn spring-boot:run      # API + consumer; repeat the simulator commands above
```
Then repeat the checks on `starter` (`git checkout starter`): health is UP, `POST /v1/heartbeats` returns 501, the starter test passes.

> Verification status when this kit was prepared: Python stack, SQL schema/seed and simulator were run end to end against Postgres; the live Kafka path and the Java build could not be run in the build environment (Maven Central / Docker Hub unavailable there). Java main sources were type-checked and the core logic executed. **Run the smoke test above before Day 1.**

**Offline fallback:** after the images are pulled, `docker save apache/kafka:3.8.0 postgres:15-alpine provectuslabs/kafka-ui:v0.7.2 -o fleet-images.tar` and copy to 3 USB drives (`docker load -i fleet-images.tar`). For Java, run `mvn dependency:go-offline` on each laptop during pre-work. For Python, `pip download -r requirements.txt -d wheels/`.

---

## Day 2 timing (matches the v4 plan)

| Block | Activity | Catch-up if a pod is stuck |
|---|---|---|
| 2.1 (09:00–10:30) | HLD/LLD whiteboard 30 min · M1 60 min, **no AI** | After 45 min, show the solution's `models.py` / `HeartbeatRequest.java` validation only |
| 2.2 (10:45–12:15) | M2 consumer, idempotency, DLQ | Let them copy `repository.insert*` from `solution`, but they must explain ON CONFLICT |
| 2.3 (13:00–14:30) | M3 + pod extension (AI allowed), M4 deploy demo | Pods behind: skip the extension, finish `/latest` + overspeed |
| 2.4 (14:45–16:15) | **Break-the-system** (below) | Run incidents on the reference solution if a pod's code isn't running |

**Which code do incidents run on?** INJ-1 (poison message) and INJ-4 (config) work on each pod's **own** system.
INJ-2 and INJ-3 are code changes: either (a) give the pod the diff to "merge" into their code (`git diff solution incident/inj2-odometer-double-conversion -- python/` or `-- java/`), or (b) run them on the reference solution checked out at the incident branch — "a teammate's service you're on call for". (b) is closer to the real interview (debugging unfamiliar code).

---

## 2.4 Break-the-system — run sheet

For each incident (≈ 20 min): 2 min setup, 12 min pod works, 6 min debrief. Rotate the **incident commander (IC)** so every student leads at least once. Keep the simulator running in the background throughout:
`python3 simulator/simulate.py --vehicles 30 --count 100000 --interval 1`

The IC must: (1) state impact (who / what / since when), (2) mitigate before root-causing, (3) find the root cause from evidence, (4) fix + add a test or check, (5) post a 3-line status update. Score with the R2 rubric (1–4).

### INJ-1 — Poison message (P0, data freshness SLA)
- **Inject:** pod's own system: `./trainer/inject_poison.sh 3`. Reference solution: `git checkout incident/inj1-poison-crashloop` first (the solution's DLQ would otherwise handle it correctly — which is itself a good debrief point).
- **Symptom to announce:** "Customers report vehicle locations are stale — some fleets only."
- **Evidence:** `./scripts/kafka.sh lag` → LAG growing on 1–3 partitions only. Python: consumer crashes with a pydantic ValidationError and, when restarted, crashes again on the same offset. Java: log shows the same offset retried every second forever.
- **Root cause:** schema-v2 message (`speed` is an object, no `speed_kmph`) — no DLQ / infinite retry → partition blocked → every VIN hashed to that partition stops updating.
- **Mitigate:** skip / park the bad offset — stop the consumer, then
  `docker compose exec kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --group telemetry-writer --reset-offsets --topic telemetry.raw:<P> --to-offset <N+1> --execute`
  (P/N from the error log) — or deploy DLQ handling; restart and confirm lag drains. Skipping = data loss for that one event; parking to a DLQ doesn't lose it — discuss.
- **Prevent:** DLQ + alert on DLQ rate; per-partition lag alert (not average); schema registry / versioned contract; never "retry forever" on non-retryable errors.
- **Model update:** *"P0 — Since 14:52, location data is delayed for vehicles on 3 of 12 partitions (~25% of vehicles). Cause: a malformed message from a new OEM firmware blocked processing. Mitigated 15:05 by parking it to the DLQ; backlog draining, normal by ~15:10. Next update 15:15."*
- **Debrief:** why key by VIN made this "some fleets only"; at-least-once and offset commits.

### INJ-2 — Odometer double conversion (P1 → P0 once billing uses it)
- **Inject:** `git checkout incident/inj2-odometer-double-conversion` (commit message: "OEM-B feeds report miles -> convert to km (FLT-231)").
- **Symptom:** "A lender says ~1/4 of their OEM-B vehicles show odometer ~60% too high since the last deploy."
- **Evidence:** `SELECT source_oem, avg(odometer_km) ... GROUP BY source_oem` / compare a VIN before and after deploy time; `git log -p` on normalize; the unit test `test_miles_converted_once` / `milesConvertedOnce` **fails** on this branch (someone merged with red CI).
- **Root cause:** OEM-B adapter converts miles→km and the normaliser converts again because `odometer_unit` is still `mi` (×1.609²).
- **Mitigate:** revert the commit; pause any downstream export/billing.
- **Fix data:** this becomes **Day-3 SQL ticket U5** (backfill: divide by 1.609344 for OEM-B rows since the deploy time — idempotently).
- **Prevent:** per-OEM unit tests, invariant check (odometer can't jump > 2,000 km/day), CI must block merges.

### INJ-3 — Slow fleet API (P1, customer dashboard SLA)
- **Inject:** `git checkout incident/inj3-slow-fleet-api` (commit: "VIN match is case-insensitive (FLT-482)").
- **Symptom:** "MegaFleet's dashboard (`GET /v1/fleets/1/latest`) went from instant to 2+ seconds; p99 alarms."
- **Evidence:** `time curl localhost:8080/v1/fleets/1/latest` vs `/fleets/9/latest`; enable SQL logging or `pg_stat_statements`; `EXPLAIN ANALYZE ... WHERE upper(vin) = upper('...')` → **Seq Scan** on ~56k rows, ×78 vehicles.
- **Root cause:** N+1 queries (one per vehicle) **and** a non-sargable predicate (`upper(vin)`) that can't use the `(vin, ts_utc)` index.
- **Fix:** one `DISTINCT ON` query (see `solution`); normalise VIN case at ingest instead of in the query. Measure before/after.
- **Debrief:** why it was fast on the developer's laptop with 5 vehicles.

### INJ-4 — Bad config deploy (P0, total outage)
- **Inject:** `git checkout incident/inj4-bad-db-config` and restart the app (or on a pod's own system: restart it with `DB_HOST=pg-primary.fleet.internal`).
- **Symptom:** "All API calls failing since 16:02."
- **Evidence:** `/health` → 503 `db: down`; logs show unknown host / connection timeout; `git log` shows "new primary after migration (FLT-560)" at 16:01.
- **Mitigate:** roll back the config / redeploy previous version — **before** investigating DNS.
- **Prevent:** config validation at startup, canary deploy, readiness probe that fails the rollout.
- **Communication focus:** this one is about speed of rollback and a clear update, not deep debugging.

---

## Answer key: M2/M3 design questions
- **Key by VIN:** per-vehicle ordering + even spread; hot-fleet risk only if keyed by customer. A single noisy VIN is still bounded.
- **At-least-once:** offset committed after DB commit → crash = re-delivery → `ON CONFLICT DO NOTHING` makes it harmless. Exactly-once would need transactions across Kafka + DB (or the outbox pattern) — not worth it here.
- **Out-of-order events:** stored correctly (ts is data, not arrival order); the overspeed rule reads by `ts_utc` so late events can create/merge runs — discuss tolerance windows.
- **Pagination:** keyset on `ts_utc` is O(log n) per page and stable under inserts; OFFSET rescans and shifts.
- **Scale the consumer:** up to 12 instances (= partitions) in the same group; DB write batching; partitioned telemetry table by month; hot (Postgres/TSDB) vs cold (object storage/Parquet).

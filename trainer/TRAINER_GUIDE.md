# Trainer Guide — Fleet Telemetry Platform (Day 2)

This repo = the student starter kit **plus** a reference solution, fault-injection branches and tools.
Students clone the separate **starter** repo. Don't share this one with them before the session.

| Ref | What it is |
|---|---|
| tag `starter` | the original student kit (the starter repo's `main` adds later fixes to README, `check_env.sh`, the simulator and `pom.xml`) |
| `main` / tag `solution` | reference solution, both stacks (M1–M3 complete, tests) |
| `incident/inj1-poison-crashloop` | INJ-1 code change (consumer can't survive a bad message) |
| `incident/inj2-odometer-double-conversion` | INJ-2 |
| `incident/inj3-slow-fleet-api` | INJ-3 |
| `incident/inj4-bad-db-config` | INJ-4 |
| `trainer/inject_poison.sh` | publishes schema-v2 "poison" messages straight to Kafka |
| `tools/gen_seed.py` | regenerates `db/init/02_seed.sql` + `data/vehicles.csv` (seed 42) |
| `scripts/day3_setup.sh` + `db/day3/` | Day 3 setup students run (also in the starter repo) |
| `trainer/day3_answers.sql`, `trainer/day3_fix3_transfer.py` | Day 3 answer key |
| `tools/gen_day3_log.py` | regenerates `data/day3/app.log` + the FIX-3 CSV |

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
- **Evidence:** Java: log shows the same offset retried every second forever, and `./scripts/kafka.sh lag` shows LAG growing on 1–3 partitions only. Python: the consumer process crashes with a pydantic ValidationError (crashing again on restart), so LAG grows on **every** partition that gets traffic — the "some fleets only" symptom is Java-specific; on Python it's "all fleets stale".
- **Root cause:** schema-v2 message (`speed` is an object, no `speed_kmph`) — no DLQ / infinite retry → partition blocked → every VIN hashed to that partition stops updating.
- **Mitigate:** skip / park the bad offset — stop the consumer, then
  `docker compose exec kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --group telemetry-writer --reset-offsets --topic telemetry.raw:<P> --to-offset <N+1> --execute`
  (Java: P/N from the error log. Python: the traceback doesn't print them — the poison partitions are the ones whose CURRENT-OFFSET in `./scripts/kafka.sh lag` points at a v2 message; check with `docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic telemetry.raw --partition <P> --offset <N> --max-messages 1`) — or deploy DLQ handling; restart and confirm lag drains. Skipping = data loss for that one event; parking to a DLQ doesn't lose it — discuss.
- **Prevent:** DLQ + alert on DLQ rate; per-partition lag alert (not average); schema registry / versioned contract; never "retry forever" on non-retryable errors.
- **Model update:** *"P0 — Since 14:52, location data is delayed for vehicles on 3 of 12 partitions (~25% of vehicles). Cause: a malformed message from a new OEM firmware blocked processing. Mitigated 15:05 by parking it to the DLQ; backlog draining, normal by ~15:10. Next update 15:15."*
- **Debrief:** why key by VIN made this "some fleets only"; at-least-once and offset commits.

### INJ-2 — Odometer double conversion (P1 → P0 once billing uses it)
- **Inject:** `git checkout incident/inj2-odometer-double-conversion` (commit message: "FLT-231: OEM-B feeds report miles, convert to km").
- **Symptom:** "A lender says ~1/4 of their OEM-B vehicles show odometer ~60% too high since the last deploy."
- **Evidence:** `SELECT source_oem, avg(odometer_km) ... GROUP BY source_oem` / compare a VIN before and after deploy time; `git log -p` on normalize; the unit tests `test_miles_converted_once` + `test_km_is_unchanged` / `milesConvertedOnce` + `kmUnchanged_andVinUpperCased` **fail** on this branch (the km test fails too because its fixture is an OEM-B device — the bug also hits OEM-B devices that report km) (someone merged with red CI).
- **Root cause:** OEM-B adapter converts miles→km and the normaliser converts again because `odometer_unit` is still `mi` (×1.609²).
- **Mitigate:** revert the commit; pause any downstream export/billing.
- **Fix data:** this becomes **Day-3 SQL ticket U5** (backfill: divide by 1.609344 for OEM-B rows since the deploy time — idempotently).
- **Prevent:** per-OEM unit tests, invariant check (odometer can't jump > 2,000 km/day), CI must block merges.

### INJ-3 — Slow fleet API (P1, customer dashboard SLA)
- **Inject:** `git checkout incident/inj3-slow-fleet-api` (commit: "FLT-482: case-insensitive VIN match for fleet view").
- **Symptom:** "MegaFleet's dashboard (`GET /v1/fleets/1/latest`) went from instant to 2+ seconds; p99 alarms." (Expect roughly 20–30× slower than `main` — about 0.7 s on a fast laptop, 2+ s on older ones; always compare against `/fleets/9/latest` and against `main`.)
- **Evidence:** `time curl localhost:8080/v1/fleets/1/latest` vs `/fleets/9/latest`; enable SQL logging or `pg_stat_statements`; `EXPLAIN ANALYZE ... WHERE upper(vin) = upper('...')` → **Seq Scan** on ~56k rows, ×78 vehicles.
- **Root cause:** N+1 queries (one per vehicle) **and** a non-sargable predicate (`upper(vin)`) that can't use the `(vin, ts_utc)` index.
- **Fix:** one `DISTINCT ON` query (see `solution`); normalise VIN case at ingest instead of in the query. Measure before/after.
- **Debrief:** why it was fast on the developer's laptop with 5 vehicles.

### INJ-4 — Bad config deploy (P0, total outage)
- **Inject:** `git checkout incident/inj4-bad-db-config` and restart the app (or on a pod's own system: restart it with `DB_HOST=pg-primary.fleet.internal`).
- **Symptom:** "All API calls failing since 16:02."
- **Evidence:** `/health` → 503 `db: down`; logs show unknown host / connection timeout; `git log` shows "FLT-560: point services at new DB primary" just before the outage.
- **Mitigate:** roll back the config / redeploy previous version — **before** investigating DNS.
- **Prevent:** config validation at startup, canary deploy, readiness probe that fails the rollout.
- **Communication focus:** this one is about speed of rollback and a clear update, not deep debugging.

---

## Day 3 — SQL and data-fix practice

**Before the first session:** every student runs `./scripts/day3_setup.sh` from the repo root (Docker running). It keeps their Day 2 data, is safe to run twice ("already applied"), and prints a summary. Only use `--reset` for a broken database: it deletes Day 2 data. Check one laptop: `select count(*) from ops_tickets` must be 6.

**Answer key:** `trainer/day3_answers.sql`: all 12 SQL questions and FIX-1 to FIX-6 written the safe way, with the expected result after `./scripts/day3_setup.sh --reset` on a fresh database. Run it on your own copy:
`docker compose exec -T postgres psql -U fleet -d fleet -v ON_ERROR_STOP=1 -f - < trainer/day3_answers.sql` (safe to re-run). Answers marked *(time)* depend on the clock (today's date, or the time the setup ran), so a student's number can differ a little: check their query, not just the number.
Script answers: `trainer/day3_fix3_transfer.py` (FIX-3 from the CSV, `--dry-run`, one transaction) and the `awk` one-liner for the log task at the end of the answer key.

**What the setup plants (all picked by VIN/id order, so every laptop is the same):**

| Where | What | The trap |
|---|---|---|
| all seed telemetry | moved so the newest reading is 1 hour old; speeds replaced by fixed values | none, it just makes "last 24 hours" / "today" questions and SQL 12 work the same everywhere |
| SQL 2 | 8 active vehicles (2 each from customers 7–10) with no readings in the last 24 hours | leaving out `status = 'active'` adds the 12 deprovisioned vehicles |
| FIX-1 | ticket lists 5 VINs "for customer 4" | the 3rd VIN belongs to Prime Auto Dealers (customer 10): don't deactivate it, ask the requester |
| FIX-2 | FleetCo, by name only | 1 cancelled + 2 expired rows to skip, 1 active row with NULL `end_date` (open-ended) |
| FIX-3 | `data/day3/transfer_cityride_to_autofin.csv`, 28 rows | 2 VINs that don't exist, 1 duplicated row; 25 vehicles to move |
| FIX-4 | 17 vehicles with duplicate devices (18 extra rows) | some duplicates have `last_seen_at` NULL: needs a NULLS LAST + tie-break rule |
| FIX-5 | OEM-B `odometer_km` × 1.609344 since the time in the ticket (3 hours before setup) | running the fix twice divides twice: guard on the backup table / still-wrong rows |
| FIX-6 | GeoBox firmware < 3.2: last 6 hours stored 5:30 ahead (some in the future) | a plain shift can hit the unique (vin, ts_utc) constraint; must be safe to re-run; only the 21 non-OEM-B vehicles are affected (so FIX-5 and FIX-6 never touch the same rows) |
| log task | `data/day3/app.log`, 2,000 lines, fixed times 08:00–10:00Z on 2026-10-27 | lines without a vin and a few malformed lines; "last hour" means the hour ending at the last line |

Files for Day 3 are regenerated with `python3 tools/gen_day3_log.py .` (deterministic); commit the output to both repos.

---

## Answer key: M2/M3 design questions
- **Key by VIN:** per-vehicle ordering + even spread; hot-fleet risk only if keyed by customer. A single noisy VIN is still bounded.
- **At-least-once:** offset committed after DB commit → crash = re-delivery → `ON CONFLICT DO NOTHING` makes it harmless. Exactly-once would need transactions across Kafka + DB (or the outbox pattern) — not worth it here.
- **Out-of-order events:** stored correctly (ts is data, not arrival order); the overspeed rule reads by `ts_utc` so late events can create/merge runs — discuss tolerance windows.
- **Pagination:** keyset on `ts_utc` is O(log n) per page and stable under inserts; OFFSET rescans and shifts.
- **Scale the consumer:** up to 12 instances (= partitions) in the same group; DB write batching; partitioned telemetry table by month; hot (Postgres/TSDB) vs cold (object storage/Parquet).

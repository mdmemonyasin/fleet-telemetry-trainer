-- =====================================================================================================
-- Day 3 answer key (TRAINER ONLY — don't share with students)
--
-- Expected results are for a fresh database right after:   ./scripts/day3_setup.sh --reset
-- Run the whole file (queries first, then the fixes) with:
--   docker compose exec -T postgres psql -U fleet -d fleet -v ON_ERROR_STOP=1 -f - < trainer/day3_answers.sql
-- Re-running it is safe; FIX-5 and FIX-6 then change 0 rows (see their notes).
--
-- Results marked (time) depend on the clock: when the setup ran, or today's date. Their expected value
-- is given for a setup run during the working day (UTC), with the query run soon after.
-- =====================================================================================================

\set ON_ERROR_STOP 1
\pset footer on

-- ---------------------------------------------------------------------------------------------------
-- SQL 1. Active vehicles per customer, only customers with more than 10
-- Expect 5 rows: MegaFleet Logistics 78, CityRide Rentals 25, FleetCo 18, GreenCab EV 14, Highway Haulers 12
-- ---------------------------------------------------------------------------------------------------
SELECT c.name, count(*) AS active_vehicles
FROM vehicles v
JOIN customers c ON c.id = v.customer_id
WHERE v.status = 'active'
GROUP BY c.id, c.name
HAVING count(*) > 10
ORDER BY active_vehicles DESC;

-- ---------------------------------------------------------------------------------------------------
-- SQL 2. Vehicles with no data in the last 24 hours
-- Expect 8 rows: exactly the 8 planted vehicles (2 each from customers 7, 8, 9, 10). No other active vehicle
-- is silent. (One of them, 1FA2XU6A3H3KKGGGV, is also the wrong-customer VIN in FIX-1.)
-- If a student leaves out "status = 'active'" they also get the 12 deprovisioned vehicles (20 rows):
-- those never had telemetry. Ask which answer the business wants.
-- ---------------------------------------------------------------------------------------------------
SELECT v.vin, v.customer_id
FROM vehicles v
WHERE v.status = 'active'
  AND NOT EXISTS (SELECT 1 FROM telemetry t WHERE t.vin = v.vin AND t.ts_utc > now() - interval '24 hours')
ORDER BY v.vin;

-- ---------------------------------------------------------------------------------------------------
-- SQL 3. Latest reading for every vehicle
-- Expect 180 rows: 188 active vehicles minus the 8 silent ones, which have no readings left at all (the seed
-- covers 25 hours, and the setup removed their last 26 hours).
-- Trap: the 21 vehicles hit by the IST bug (FIX-6) have readings in the FUTURE, so their "latest" is wrong
-- until FIX-6 is done. A careful answer adds  AND ts_utc <= now()  or flags it.
-- ---------------------------------------------------------------------------------------------------
SELECT vin, ts_utc, speed_kmph, odometer_km, lat, lon
FROM (
    SELECT t.*, row_number() OVER (PARTITION BY t.vin ORDER BY t.ts_utc DESC) AS rn
    FROM telemetry t
) x
WHERE rn = 1
ORDER BY vin;

-- ---------------------------------------------------------------------------------------------------
-- SQL 4. Subscriptions ending in the next 30 days, with customer name
-- (time) end_date values are fixed dates in Nov/Dec 2026, so the count depends on today's date:
-- 77 rows on 2026-10-28.
-- Watch for: NULL end_date (open-ended) never matches; cancelled ones should be excluded.
-- ---------------------------------------------------------------------------------------------------
SELECT c.name AS customer, s.vin, s.plan, s.end_date
FROM subscriptions s
JOIN customers c ON c.id = s.customer_id
WHERE s.status = 'active'
  AND s.end_date >= current_date
  AND s.end_date <  current_date + 30
ORDER BY s.end_date, c.name;

-- ---------------------------------------------------------------------------------------------------
-- SQL 5. Distance driven per vehicle per day (LAG)
-- (time) Expect one row per (vehicle, UTC day) with at least 2 readings: 2 days for each of the 180 reporting
-- vehicles = 360 rows (fewer/more if the setup ran near midnight UTC).
-- Trap: OEM-B vehicles show a huge jump on the day of the bad release (FIX-5) until it is fixed; the
-- distance after FIX-5 is ~4.2 km per 5-minute reading.
-- ---------------------------------------------------------------------------------------------------
SELECT vin, date_trunc('day', ts_utc) AS day, round(sum(step), 1) AS km
FROM (
    SELECT vin, ts_utc,
           odometer_km - lag(odometer_km) OVER (PARTITION BY vin ORDER BY ts_utc) AS step
    FROM telemetry
) x
WHERE step IS NOT NULL
GROUP BY vin, date_trunc('day', ts_utc)
ORDER BY vin, day;

-- ---------------------------------------------------------------------------------------------------
-- SQL 6. Devices on firmware older than 3.2, by provider
-- Expect 3 rows (device rows, all vehicles): GeoBox 36, OEM-Native 68, TrackOne 34   (total 138)
-- Trap: comparing as text breaks for '10.0' < '3.2'. Compare the parts as numbers.
-- ---------------------------------------------------------------------------------------------------
SELECT provider, count(*) AS devices
FROM devices
WHERE (split_part(firmware, '.', 1)::int, split_part(firmware, '.', 2)::int) < (3, 2)
GROUP BY provider
ORDER BY provider;

-- ---------------------------------------------------------------------------------------------------
-- SQL 7. Top 3 vehicles by distance for each customer
-- Expect 33 rows (3 for each of the 11 customers with active vehicles).
-- Trap: before FIX-5 the OEM-B vehicles dominate because of the doubled odometer.
-- ---------------------------------------------------------------------------------------------------
SELECT customer_id, vin, km, rnk
FROM (
    SELECT v.customer_id, v.vin, max(t.odometer_km) - min(t.odometer_km) AS km,
           row_number() OVER (PARTITION BY v.customer_id ORDER BY max(t.odometer_km) - min(t.odometer_km) DESC, v.vin) AS rnk
    FROM vehicles v
    JOIN telemetry t ON t.vin = v.vin
    GROUP BY v.customer_id, v.vin
) x
WHERE rnk <= 3
ORDER BY customer_id, rnk;

-- ---------------------------------------------------------------------------------------------------
-- SQL 8. Vehicles with more than one device
-- Expect 17 rows (one vehicle has 3 device rows, 16 have 2).
-- ---------------------------------------------------------------------------------------------------
SELECT vin, count(*) AS devices
FROM devices
GROUP BY vin
HAVING count(*) > 1
ORDER BY devices DESC, vin;

-- ---------------------------------------------------------------------------------------------------
-- SQL 9. Customers whose vehicles are all deactivated
-- Expect 1 row: OldRoute Transport.
-- Watch for: a customer with NO vehicles at all would also match a plain NOT EXISTS; require at least one.
-- ---------------------------------------------------------------------------------------------------
SELECT c.id, c.name
FROM customers c
WHERE EXISTS (SELECT 1 FROM vehicles v WHERE v.customer_id = c.id)
  AND NOT EXISTS (SELECT 1 FROM vehicles v WHERE v.customer_id = c.id AND v.status = 'active');

-- ---------------------------------------------------------------------------------------------------
-- SQL 10. Readings in an IST time window, stored in UTC
-- Example: readings between 09:00 and 10:00 IST today  =  03:30–04:30 UTC.
-- (time) 2,160 rows = 180 reporting vehicles x 12 readings, when the setup ran after 05:30 UTC.
-- Trap: writing ts_utc BETWEEN '09:00' AND '10:00' (no conversion) is 5h30m off.
-- ---------------------------------------------------------------------------------------------------
SELECT count(*) AS readings
FROM telemetry
WHERE ts_utc >= (current_date + time '09:00') AT TIME ZONE 'Asia/Kolkata'
  AND ts_utc <  (current_date + time '10:00') AT TIME ZONE 'Asia/Kolkata';

-- ---------------------------------------------------------------------------------------------------
-- SQL 11. Percentage of vehicles reporting today, by brand (oem)
-- (time) Expect 4 rows. With the setup run during the UTC day: OEM-A 85.4 (41 of 48: 7 silent),
-- OEM-B 100.0, OEM-C 100.0, OEM-D 97.7 (42 of 43: 1 silent).
-- Watch for: integer division (100 * a / b) and counting telemetry rows instead of distinct vehicles.
-- ---------------------------------------------------------------------------------------------------
SELECT v.oem,
       count(DISTINCT t.vin) AS reporting,
       count(DISTINCT v.vin) AS active,
       round(100.0 * count(DISTINCT t.vin) / count(DISTINCT v.vin), 1) AS pct
FROM vehicles v
LEFT JOIN telemetry t
       ON t.vin = v.vin
      AND t.ts_utc >= date_trunc('day', now())
      AND t.ts_utc <= now()
WHERE v.status = 'active'
GROUP BY v.oem
ORDER BY v.oem;

-- ---------------------------------------------------------------------------------------------------
-- SQL 12. Speeding readings (over 100 km/h) per vehicle in the last 7 days, ignoring NULLs
-- Expect 180 rows (every vehicle that reported) and 8,911 readings in total, right after the setup.
-- (The setup replaces the seed's random speeds with fixed ones, so this is the same on every laptop.)
-- (time) The IST-bug rows in the future are excluded by ts_utc <= now(); the total grows a little as
-- they "come due" later in the day, until FIX-6 is done.
-- Note: speed_kmph is NOT NULL in this schema, so the NULL filter is defensive — good discussion point:
-- count(speed_kmph) vs count(*), and "> 100" is never true for NULL anyway.
-- ---------------------------------------------------------------------------------------------------
SELECT vin, count(*) AS speeding_readings
FROM telemetry
WHERE ts_utc >= now() - interval '7 days'
  AND ts_utc <= now()
  AND speed_kmph IS NOT NULL
  AND speed_kmph > 100
GROUP BY vin
ORDER BY speeding_readings DESC, vin;

SELECT count(*) AS total_speeding_readings
FROM telemetry
WHERE ts_utc >= now() - interval '7 days' AND ts_utc <= now()
  AND speed_kmph IS NOT NULL AND speed_kmph > 100;


-- =====================================================================================================
-- DATA FIXES — the safe way:
--   1 SELECT with the exact condition + count   2 back up   3 BEGIN   4 change with the same condition
--   5 check the rows changed   6 verify   7 COMMIT   8 one line in audit_log
-- Backups use CREATE TABLE IF NOT EXISTS so the file can be re-run; the first backup is the one kept.
-- =====================================================================================================

-- ---------------------------------------------------------------------------------------------------
-- FIX-1. Deactivate 5 vehicles for Speedway Motors (customer 4)
-- Trap: the 3rd VIN in the ticket belongs to Prime Auto Dealers (customer 10). Don't deactivate it:
-- reply to the requester. Expect: SELECT of the 5 shows one with customer_id 10; the fix changes 4 rows.
-- ---------------------------------------------------------------------------------------------------
CREATE TEMP TABLE fix1_vins AS
    SELECT (regexp_matches(body, '[A-HJ-NPR-Z0-9]{17}', 'g'))[1]::char(17) AS vin
    FROM ops_tickets WHERE id = 'FIX-1';

SELECT v.vin, v.customer_id, v.status FROM vehicles v JOIN fix1_vins f USING (vin) ORDER BY v.customer_id, v.vin;  -- 5 rows, one is customer 10

SELECT count(*) AS will_change FROM vehicles v                                                                  -- expect 4
WHERE v.vin IN (SELECT vin FROM fix1_vins) AND v.customer_id = 4 AND v.status = 'active';

CREATE TABLE IF NOT EXISTS bkp_fix_1 AS
    SELECT * FROM vehicles v WHERE v.vin IN (SELECT vin FROM fix1_vins) AND v.customer_id = 4 AND v.status = 'active';

BEGIN;
UPDATE vehicles v SET status = 'deprovisioned'
WHERE v.vin IN (SELECT vin FROM fix1_vins) AND v.customer_id = 4 AND v.status = 'active';                        -- expect UPDATE 4
SELECT count(*) AS deprovisioned_now FROM vehicles WHERE vin IN (SELECT vin FROM bkp_fix_1) AND status = 'deprovisioned';  -- 4
COMMIT;
INSERT INTO audit_log (table_name, change, ticket)
VALUES ('vehicles', '4 Speedway Motors vehicles deprovisioned; 1 VIN of customer 10 left alone, asked requester', 'FIX-1');

-- ---------------------------------------------------------------------------------------------------
-- FIX-2. Extend FleetCo's active subscriptions by 12 months
-- FleetCo has 20 subscriptions: 16 active with an end date, 1 active with NULL end_date (open-ended),
-- 1 cancelled, 2 expired. Expect 16 rows changed.
-- Traps: look the customer up by name (no id in the ticket); skip cancelled/expired; a NULL end_date
-- stays NULL (NULL + 12 months is NULL anyway, and open-ended already never ends) — mention it in the reply.
-- ---------------------------------------------------------------------------------------------------
SELECT s.status, s.end_date IS NULL AS open_ended, count(*)
FROM subscriptions s JOIN customers c ON c.id = s.customer_id
WHERE c.name = 'FleetCo' GROUP BY 1, 2 ORDER BY 1, 2;

SELECT count(*) AS will_change FROM subscriptions                                                              -- expect 16
WHERE customer_id = (SELECT id FROM customers WHERE name = 'FleetCo') AND status = 'active' AND end_date IS NOT NULL;

CREATE TABLE IF NOT EXISTS bkp_fix_2 AS
    SELECT * FROM subscriptions
    WHERE customer_id = (SELECT id FROM customers WHERE name = 'FleetCo') AND status = 'active' AND end_date IS NOT NULL;

BEGIN;
UPDATE subscriptions s SET end_date = (b.end_date + interval '12 months')::date
FROM bkp_fix_2 b
WHERE s.id = b.id AND s.end_date = b.end_date;                 -- expect UPDATE 16 (0 if re-run: already extended)
SELECT count(*) AS extended FROM subscriptions s JOIN bkp_fix_2 b USING (id)
WHERE s.end_date = (b.end_date + interval '12 months')::date;                                                  -- 16
COMMIT;
INSERT INTO audit_log (table_name, change, ticket)
VALUES ('subscriptions', 'FleetCo: 16 active subscriptions extended by 12 months (1 open-ended left as is)', 'FIX-2');

-- ---------------------------------------------------------------------------------------------------
-- FIX-3. Move vehicles from CityRide Rentals to AutoFin Capital (CSV)
-- The CSV has 28 rows: 25 CityRide vehicles, 2 VINs that don't exist (2GB7RL6AJKZSA2KSK, 2GBJV8DD7U5S29TWD)
-- and 1 duplicated row (1FAZJMJ49DHKWLLXR). Expect 25 vehicles moved; report the 2 unknown VINs.
-- Script answer: trainer/day3_fix3_transfer.py (validates each row, --dry-run, one transaction).
-- SQL version below: the CSV rows are inlined; from a laptop you'd load them with
--   docker compose exec -T postgres psql -U fleet -d fleet -c "\copy fix3_csv FROM STDIN CSV HEADER" < data/day3/transfer_cityride_to_autofin.csv
-- Discussion: should the vehicles' subscriptions move too? (subscriptions.customer_id) Ask the business.
-- ---------------------------------------------------------------------------------------------------
CREATE TEMP TABLE fix3_csv (vin TEXT, from_customer_id INT, to_customer_id INT);
INSERT INTO fix3_csv VALUES
    ('1FA6CVNCNCXW927TC', 2, 5),
    ('1FAF4MVXGFXVW54L5', 2, 5),
    ('1FAG20Y0KN929CCCJ', 2, 5),
    ('1FAR5ZB3D290S1F0R', 2, 5),
    ('1FAZJMJ49DHKWLLXR', 2, 5),
    ('2GB001CK5005EJ02X', 2, 5),
    ('2GBB006JFEW2733EJ', 2, 5),
    ('2GBBM81HTT5PV8NHJ', 2, 5),
    ('2GB7RL6AJKZSA2KSK', 2, 5),
    ('2GBJTD70GHVFLU59K', 2, 5),
    ('3HC3B8VW7S2W6EDL5', 2, 5),
    ('3HCDYUH04251YM880', 2, 5),
    ('3HCF296SPZDDV87VA', 2, 5),
    ('3HCH3WB7TRD7L1KSC', 2, 5),
    ('3HCTNUJWF9LKLYCBF', 2, 5),
    ('3HCYKJCV7J75AFBTP', 2, 5),
    ('3HCZEZSGYJCZYM67M', 2, 5),
    ('3HCZS3MF0E99B17C1', 2, 5),
    ('5YJ5FXZEVWLM09RHN', 2, 5),
    ('2GBJV8DD7U5S29TWD', 2, 5),
    ('5YJGDV13SLXXNL896', 2, 5),
    ('5YJK9JXXL2WY998W7', 2, 5),
    ('5YJS5V5RSW7N056V1', 2, 5),
    ('1FAZJMJ49DHKWLLXR', 2, 5),
    ('5YJT020C2DN0VE195', 2, 5),
    ('5YJVSWHDS36EH8B9S', 2, 5),
    ('5YJYKXXZ2J09GXS6H', 2, 5),
    ('5YJZ4UDE209LBK5CJ', 2, 5);

SELECT c.vin AS unknown_vin FROM (SELECT DISTINCT vin FROM fix3_csv) c                                         -- 2 rows
WHERE NOT EXISTS (SELECT 1 FROM vehicles v WHERE v.vin = c.vin);
SELECT vin, count(*) AS times_in_csv FROM fix3_csv GROUP BY vin HAVING count(*) > 1;                           -- 1 row

SELECT count(*) AS will_change FROM vehicles v                                                                 -- expect 25
WHERE v.vin IN (SELECT vin FROM fix3_csv) AND v.customer_id = 2;

CREATE TABLE IF NOT EXISTS bkp_fix_3 AS
    SELECT * FROM vehicles v WHERE v.vin IN (SELECT vin FROM fix3_csv) AND v.customer_id = 2;

BEGIN;
UPDATE vehicles v SET customer_id = 5
WHERE v.vin IN (SELECT vin FROM fix3_csv) AND v.customer_id = 2;      -- expect UPDATE 25 (0 if already done)
SELECT count(*) AS still_at_cityride FROM vehicles WHERE customer_id = 2;                                      -- 0
COMMIT;
INSERT INTO audit_log (table_name, change, ticket)
VALUES ('vehicles', '25 vehicles moved CityRide Rentals -> AutoFin Capital; 2 unknown VINs and 1 duplicate row in CSV reported', 'FIX-3');

-- ---------------------------------------------------------------------------------------------------
-- FIX-4. Remove duplicate device records, keep the most recent
-- 17 vehicles have duplicates; expect 18 rows deleted (one vehicle has 3 rows), 200 device rows left.
-- Tie rule: latest last_seen_at wins, NULL counts as oldest (NULLS LAST), then the highest id.
-- ---------------------------------------------------------------------------------------------------
CREATE TEMP VIEW fix4_extra AS
    SELECT id FROM (
        SELECT id, row_number() OVER (PARTITION BY vin ORDER BY last_seen_at DESC NULLS LAST, id DESC) AS rn
        FROM devices
    ) x WHERE rn > 1;

SELECT count(*) AS will_delete FROM fix4_extra;                                                                -- expect 18

CREATE TABLE IF NOT EXISTS bkp_fix_4 AS SELECT * FROM devices WHERE id IN (SELECT id FROM fix4_extra);

BEGIN;
DELETE FROM devices WHERE id IN (SELECT id FROM fix4_extra);                     -- expect DELETE 18 (0 if re-run)
SELECT count(*) AS vins_with_duplicates FROM (SELECT vin FROM devices GROUP BY vin HAVING count(*) > 1) x;     -- 0
SELECT count(*) AS device_rows FROM devices;                                                                   -- 200
COMMIT;
INSERT INTO audit_log (table_name, change, ticket)
VALUES ('devices', '18 duplicate device rows deleted (kept latest last_seen_at, NULLS LAST, then highest id)', 'FIX-4');

-- ---------------------------------------------------------------------------------------------------
-- FIX-5. OEM-B odometer readings too high since the last release (x 1.609344 applied twice)
-- Expect 1,125 rows fixed (45 active OEM-B vehicles x 25 readings between the release and the newest seed
-- reading). It can be 1,080 (x 24) depending on the minute the setup ran: the setup summary prints the exact number.
-- SAFE TO RUN TWICE: the backup records each wrong row's id and wrong value; the UPDATE only touches rows
-- whose value still equals that wrong value. After the first run they differ, so a second run changes 0.
-- (Re-running "WHERE oem = 'OEM-B' AND ts_utc >= T" without that guard would divide again.)
-- Rounding: odometer_km is NUMERIC(12,1), so a fixed value can be 0.1 km off the original.
-- ---------------------------------------------------------------------------------------------------
CREATE TEMP TABLE fix5_t AS
    SELECT substring(body FROM 'release at (\S+),')::timestamptz AS t FROM ops_tickets WHERE id = 'FIX-5';

SELECT count(*) AS will_change FROM telemetry tl JOIN vehicles v USING (vin)                                   -- expect 1125 the first time
WHERE v.oem = 'OEM-B' AND tl.ts_utc >= (SELECT t FROM fix5_t);

CREATE TABLE IF NOT EXISTS bkp_fix_5 AS
    SELECT tl.id, tl.vin, tl.ts_utc, tl.odometer_km
    FROM telemetry tl JOIN vehicles v USING (vin)
    WHERE v.oem = 'OEM-B' AND tl.ts_utc >= (SELECT t FROM fix5_t);

BEGIN;
UPDATE telemetry tl SET odometer_km = round(b.odometer_km / 1.609344, 1)
FROM bkp_fix_5 b
WHERE tl.id = b.id AND tl.odometer_km = b.odometer_km;          -- expect UPDATE 1125, then UPDATE 0 on a re-run
-- verify: the first reading after T should continue smoothly from the last one before T (step ~4.2 km)
SELECT count(*) AS vins_still_jumping FROM (                                                                   -- 0
    SELECT vin,
           odometer_km - lag(odometer_km) OVER (PARTITION BY vin ORDER BY ts_utc) AS step
    FROM telemetry WHERE vin IN (SELECT vin FROM bkp_fix_5)
) x WHERE step > 100;
COMMIT;
INSERT INTO audit_log (table_name, change, ticket)
VALUES ('telemetry', 'OEM-B odometer_km divided by 1.609344 for rows since the release (guarded by bkp_fix_5)', 'FIX-5');

-- ---------------------------------------------------------------------------------------------------
-- FIX-6. GeoBox devices on firmware below 3.2 sent IST times as UTC (+5:30) for 6 hours
-- Expect 1,260 rows moved back (21 vehicles x 60 readings). The stored window is the ticket's window + 5:30.
-- Note: 11 more GeoBox <3.2 vehicles are OEM-B; they have no rows in the stored window, so including
-- them is harmless (the setup leaves them out so FIX-5 and FIX-6 never overlap).
-- SAFE TO RUN TWICE: the backup stores each row's wrong ts_utc; only rows still at that time move. The
-- move can't collide: sources are (window + 5:30), destinations are the window, which is now empty.
-- In production you'd do this in batches (e.g. WHERE id BETWEEN ...), same guard, one commit per batch.
-- ---------------------------------------------------------------------------------------------------
CREATE TEMP TABLE fix6_window AS
    SELECT (regexp_matches(body, 'Between (\S+) and (\S+) '))[1]::timestamptz AS w_from,
           (regexp_matches(body, 'Between (\S+) and (\S+) '))[2]::timestamptz AS w_to
    FROM ops_tickets WHERE id = 'FIX-6';
CREATE TEMP TABLE fix6_vins AS
    SELECT DISTINCT v.vin FROM vehicles v JOIN devices d USING (vin)
    WHERE v.status = 'active' AND d.provider = 'GeoBox'
      AND (split_part(d.firmware, '.', 1)::int, split_part(d.firmware, '.', 2)::int) < (3, 2);   -- 32 vehicles

SELECT count(*) AS will_change, count(DISTINCT vin) AS vehicles FROM telemetry                                -- expect 1260, 21
WHERE vin IN (SELECT vin FROM fix6_vins)
  AND ts_utc >  (SELECT w_from FROM fix6_window) + interval '5 hours 30 minutes'
  AND ts_utc <= (SELECT w_to   FROM fix6_window) + interval '5 hours 30 minutes';

CREATE TABLE IF NOT EXISTS bkp_fix_6 AS
    SELECT id, vin, ts_utc FROM telemetry
    WHERE vin IN (SELECT vin FROM fix6_vins)
      AND ts_utc >  (SELECT w_from FROM fix6_window) + interval '5 hours 30 minutes'
      AND ts_utc <= (SELECT w_to   FROM fix6_window) + interval '5 hours 30 minutes';

BEGIN;
UPDATE telemetry tl SET ts_utc = b.ts_utc - interval '5 hours 30 minutes'
FROM bkp_fix_6 b
WHERE tl.id = b.id AND tl.ts_utc = b.ts_utc;                   -- expect UPDATE 1260, then UPDATE 0 on a re-run
SELECT count(*) AS future_rows FROM telemetry WHERE ts_utc > now() + interval '5 minutes';                     -- 0
SELECT count(*) AS back_in_window FROM telemetry tl JOIN bkp_fix_6 b USING (id)                                -- 1260
WHERE tl.ts_utc = b.ts_utc - interval '5 hours 30 minutes';
COMMIT;
INSERT INTO audit_log (table_name, change, ticket)
VALUES ('telemetry', 'GeoBox fw<3.2: readings in the IST window moved back 5:30 (guarded by bkp_fix_6)', 'FIX-6');

-- Final integrity check: no duplicate (vin, ts_utc)                                                            -- 0
SELECT count(*) AS duplicate_vin_ts FROM (SELECT vin, ts_utc FROM telemetry GROUP BY 1, 2 HAVING count(*) > 1) x;


-- =====================================================================================================
-- SCRIPT 2 (shell): ERROR lines per service in data/day3/app.log, in the hour ending at the last line
-- (2026-10-27T10:00:00Z). Malformed lines (no [service]) are skipped.
--
--   awk '$1 >= "2026-10-27T09:00:00Z" && $2 == "ERROR" && $3 ~ /^\[/ {gsub(/[][]/, "", $3); n[$3]++} END {for (s in n) print n[s], s}' data/day3/app.log | sort -rn
--
-- Expect:  70 consumer / 39 billing / 29 api / 22 ingest
-- (Whole file, all 2 hours: compare with  grep -c ' ERROR \[' data/day3/app.log )
-- =====================================================================================================

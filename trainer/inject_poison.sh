#!/usr/bin/env bash
# INJ-1: publish "schema v2" telemetry straight to Kafka, as if a new OEM firmware feed bypassed the Ingest API.
# Works against ANY student consumer (Java or Python). Run from the repo root while docker compose is up.
#
#   ./trainer/inject_poison.sh            # 1 poison message for the first active vehicle
#   ./trainer/inject_poison.sh 5          # 5 poison messages (different VINs -> different partitions)
set -euo pipefail
N="${1:-1}"
VINS=$(awk -F, 'NR>1 && $4=="active" {print $1}' data/vehicles.csv | head -n "$N")
TS=$(date -u +%Y-%m-%dT%H:%M:%SZ)
for VIN in $VINS; do
  echo "${VIN}|{\"schema\":\"v2\",\"vin\":\"${VIN}\",\"ts_utc\":\"${TS}\",\"speed\":{\"value\":48.5,\"unit\":\"kmph\"},\"odo\":{\"value\":10234.5,\"unit\":\"km\"},\"position\":{\"lat\":12.97,\"lon\":77.59},\"heading\":null,\"source_oem\":\"OEM-C\",\"fw\":\"5.0.1\"}"
done | docker compose exec -T kafka /opt/kafka/bin/kafka-console-producer.sh \
        --bootstrap-server localhost:9092 --topic telemetry.raw \
        --property parse.key=true --property key.separator='|'
echo "Injected $N schema-v2 message(s) for: $VINS"
echo "Now keep the simulator running and watch: ./scripts/kafka.sh lag"

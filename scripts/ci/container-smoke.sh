#!/usr/bin/env bash
# Validate the already-healthy packaged stack; Compose owns its lifecycle.
set -euo pipefail
umask 077
response_dir=$(mktemp -d "${TMPDIR:-/tmp}/heimdall-container-smoke.XXXXXX")
trap 'rm -rf -- "$response_dir"' EXIT

health_status=$(curl --silent --show-error --connect-timeout 5 --max-time 10 \
  --output "$response_dir/health.json" --write-out '%{http_code}' \
  http://localhost:8080/actuator/health)
test "$health_status" = 200
python3 - "$response_dir/health.json" <<'PY'
import json
import sys

with open(sys.argv[1]) as response:
    assert json.load(response)["status"] == "UP", "Backend health is not UP"
print("Backend health: HTTP 200 / UP")
PY

migration_counts=$(docker compose exec -T postgres \
  psql -X -qAt -v ON_ERROR_STOP=1 -U heimdall -d heimdall \
  -c "SET statement_timeout = '10s'; SELECT count(*) FILTER (WHERE success), count(*) FILTER (WHERE NOT success) FROM flyway_schema_history;")
IFS='|' read -r successful failed <<< "$migration_counts"
[[ "$successful" =~ ^[0-9]+$ && "$failed" = 0 ]]
(( successful > 0 ))
echo "Flyway: $successful successful migrations, $failed failed"

create_status=$(curl --silent --show-error --connect-timeout 5 --max-time 10 \
  --output "$response_dir/create.json" --write-out '%{http_code}' \
  -H 'Content-Type: application/json' \
  -d '{"vin":"7FC99000000000999","softwareVersion":"1.0.0"}' \
  http://localhost:8080/api/v1/vehicles)
test "$create_status" = 201
vehicle_id=$(python3 - "$response_dir/create.json" <<'PY'
import json
import sys
import uuid

with open(sys.argv[1]) as response:
    vehicle = json.load(response)
assert isinstance(vehicle.get("credential"), str) and vehicle["credential"], "Missing provisioning credential"
print(uuid.UUID(vehicle["id"]))
PY
)
echo 'Vehicle provisioning: HTTP 201; response valid (credential not printed)'

get_status=$(curl --silent --show-error --connect-timeout 5 --max-time 10 \
  --output "$response_dir/get.json" --write-out '%{http_code}' \
  "http://localhost:8080/api/v1/vehicles/$vehicle_id")
test "$get_status" = 200
python3 - "$response_dir/get.json" "$vehicle_id" <<'PY'
import json
import sys

with open(sys.argv[1]) as response:
    vehicle = json.load(response)
assert vehicle["id"] == sys.argv[2], "Vehicle ID mismatch"
assert vehicle["vin"] == "7FC99000000000999", "VIN mismatch"
assert vehicle["softwareVersion"] == "1.0.0", "Software version mismatch"
assert "credential" not in vehicle, "GET must not expose the provisioning credential"
print("Vehicle read-back: HTTP 200; ID, VIN and version match; credential absent")
PY

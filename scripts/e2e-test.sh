#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROJECT_NAME="${COMPOSE_PROJECT_NAME:-event-ledger-e2e}"
COMPOSE=(docker compose --project-name "$PROJECT_NAME" -f "$ROOT_DIR/docker-compose.yml")
GATEWAY_URL="${GATEWAY_URL:-http://localhost:8080}"
TMP_DIR="$(mktemp -d)"

cleanup() {
  "${COMPOSE[@]}" down --remove-orphans --volumes >/dev/null 2>&1 || true
  rm -rf "$TMP_DIR"
}
trap cleanup EXIT

assert_status() {
  local expected="$1"
  local actual="$2"
  local context="$3"
  if [[ "$actual" != "$expected" ]]; then
    echo "$context: expected HTTP $expected, received $actual" >&2
    exit 1
  fi
}

post_event() {
  local body="$1"
  local output="$2"
  shift 2
  curl -sS -o "$output" -w "%{http_code}" \
    -X POST "$GATEWAY_URL/events" \
    -H "Content-Type: application/json" \
    "$@" \
    --data "$body"
}

echo "Starting Event Ledger containers..."
"${COMPOSE[@]}" up --build --detach --wait

late_event='{
  "eventId": "evt-e2e-late",
  "accountId": "acct-e2e",
  "type": "CREDIT",
  "amount": 100.00,
  "currency": "USD",
  "eventTimestamp": "2026-05-15T16:00:00Z",
  "metadata": {"source": "e2e"}
}'
early_event='{
  "eventId": "evt-e2e-early",
  "accountId": "acct-e2e",
  "type": "DEBIT",
  "amount": 30.00,
  "currency": "USD",
  "eventTimestamp": "2026-05-15T12:00:00Z"
}'
middle_event='{
  "eventId": "evt-e2e-middle",
  "accountId": "acct-e2e",
  "type": "CREDIT",
  "amount": 5.00,
  "currency": "USD",
  "eventTimestamp": "2026-05-15T14:00:00Z"
}'

status="$(curl -sS -D "$TMP_DIR/headers" -o "$TMP_DIR/late.json" -w "%{http_code}" \
  -X POST "$GATEWAY_URL/events" \
  -H "Content-Type: application/json" \
  -H "X-Trace-Id: trace-e2e-001" \
  --data "$late_event")"
assert_status "201" "$status" "Initial event submission"
grep -qi '^X-Trace-Id: trace-e2e-001' "$TMP_DIR/headers"
"${COMPOSE[@]}" logs account-service | grep -q '"traceId":"trace-e2e-001"'

status="$(post_event "$early_event" "$TMP_DIR/early.json")"
assert_status "201" "$status" "Out-of-order debit submission"
status="$(post_event "$middle_event" "$TMP_DIR/middle.json")"
assert_status "201" "$status" "Middle event submission"

duplicate_event='{
  "eventId": "evt-e2e-late",
  "accountId": "acct-other",
  "type": "DEBIT",
  "amount": 999.00,
  "currency": "USD",
  "eventTimestamp": "2026-05-16T16:00:00Z"
}'
status="$(post_event "$duplicate_event" "$TMP_DIR/duplicate.json")"
assert_status "200" "$status" "Duplicate event submission"

curl -fsS "$GATEWAY_URL/events?account=acct-e2e" > "$TMP_DIR/events.json"
curl -fsS "$GATEWAY_URL/accounts/acct-e2e/balance" > "$TMP_DIR/balance.json"

python3 - "$TMP_DIR" <<'PY'
import json
import pathlib
import sys

directory = pathlib.Path(sys.argv[1])
events = json.loads((directory / "events.json").read_text())
event_ids = [event["eventId"] for event in events]
expected_ids = ["evt-e2e-early", "evt-e2e-middle", "evt-e2e-late"]
if event_ids != expected_ids:
    raise SystemExit(f"event order mismatch: expected {expected_ids}, received {event_ids}")

duplicate = json.loads((directory / "duplicate.json").read_text())
if duplicate["accountId"] != "acct-e2e" or duplicate["type"] != "CREDIT":
    raise SystemExit("duplicate submission did not return the original event")

balance = json.loads((directory / "balance.json").read_text())
if float(balance["balance"]) != 75.0 or balance["currency"] != "USD":
    raise SystemExit(f"balance mismatch: {balance}")
PY

echo "Stopping Account Service to verify graceful degradation..."
"${COMPOSE[@]}" stop account-service >/dev/null

unavailable_event='{
  "eventId": "evt-e2e-unavailable",
  "accountId": "acct-e2e",
  "type": "CREDIT",
  "amount": 1.00,
  "currency": "USD",
  "eventTimestamp": "2026-05-15T18:00:00Z"
}'
status="$(post_event "$unavailable_event" "$TMP_DIR/unavailable.json")"
assert_status "503" "$status" "Submission while Account Service is unavailable"

status="$(curl -sS -o "$TMP_DIR/stored.json" -w "%{http_code}" \
  "$GATEWAY_URL/events/evt-e2e-late")"
assert_status "200" "$status" "Gateway-local event read during outage"

status="$(curl -sS -o "$TMP_DIR/balance-unavailable.json" -w "%{http_code}" \
  "$GATEWAY_URL/accounts/acct-e2e/balance")"
assert_status "503" "$status" "Balance query during Account Service outage"

echo "End-to-end checks passed."

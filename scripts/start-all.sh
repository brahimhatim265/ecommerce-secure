#!/usr/bin/env bash
# Starts the 3 services in the right order: user-service, payment-service, then order-service.
# One background process per service; logs go to scripts/logs/<service>.log. Press Ctrl+C to stop everything.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOG_DIR="$ROOT/scripts/logs"
DELAY_SECONDS="${START_DELAY_SECONDS:-15}"
mkdir -p "$LOG_DIR"
cd "$ROOT"

PIDS=()
cleanup() {
  echo "Stopping services..."
  for pid in "${PIDS[@]:-}"; do
    kill "$pid" 2>/dev/null || true
  done
}
trap cleanup EXIT INT TERM

start() {
  local service="$1"
  echo "Starting $service ..."
  ./mvnw -q -pl "$service" spring-boot:run >"$LOG_DIR/$service.log" 2>&1 &
  PIDS+=("$!")
}

start user-service
sleep "$DELAY_SECONDS"
start authorization-server
sleep "$DELAY_SECONDS"
start payment-service
sleep "$DELAY_SECONDS"
start order-service

echo "All services launched (logs in $LOG_DIR)."
echo "  user-service    : http://localhost:8081/swagger-ui.html"
echo "  order-service   : http://localhost:8082/swagger-ui.html"
echo "  payment-service : http://localhost:8083/swagger-ui.html"
echo "  authorization-server : http://localhost:9000/.well-known/openid-configuration"
wait

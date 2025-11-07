#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAR="$PROJECT_ROOT/target/queuectl-0.1.0-jar-with-dependencies.jar"

queuectl_cmd() {
  if command -v queuectl >/dev/null 2>&1; then
    queuectl "$@"
  else
    java -jar "$JAR" "$@"
  fi
}

echo "[demo] Building project"
mvn -q -f "$PROJECT_ROOT/pom.xml" -DskipTests package

echo "[demo] Resetting environment"
queuectl_cmd worker stop || true
rm -rf "$PROJECT_ROOT/queuectl_runtime" "$PROJECT_ROOT/job_logs" "$PROJECT_ROOT/queue.db"

echo "[demo] Configuring defaults"
queuectl_cmd config set max_retries 3
queuectl_cmd config set backoff_base 2
queuectl_cmd config set default_timeout_seconds 0

echo "[demo] Enqueueing sample jobs"
queuectl_cmd enqueue '{"id":"priority-job","command":"echo PRIORITY","priority":10}'
queuectl_cmd enqueue '{"id":"standard-job","command":"echo standard"}'
queuectl_cmd enqueue '{"id":"delayed-job","command":"echo delayed executed","delay_seconds":5}'
queuectl_cmd enqueue '{"id":"timeout-job","command":"sleep 5 && echo SHOULD NOT SEE","timeout_seconds":2,"max_retries":1}'
queuectl_cmd enqueue '{"id":"retry-job","command":"bash -lc \"echo failing && exit 1\"","max_retries":2}'

echo "[demo] Starting workers"
queuectl_cmd worker start --count 2

echo "[demo] Initial status"
queuectl_cmd status

echo "[demo] Waiting for first wave to finish"
sleep 4
queuectl_cmd status

echo "[demo] Waiting for delayed job and retries"
sleep 6
queuectl_cmd status

echo "[demo] Metrics overview"
queuectl_cmd metrics

echo "[demo] Listing all jobs"
queuectl_cmd list

echo "[demo] Dead Letter Queue"
queuectl_cmd dlq list

echo "[demo] Showing log for timeout-job"
queuectl_cmd logs timeout-job || true

echo "[demo] Retrying DLQ entries"
DLQ_IDS=$(queuectl_cmd dlq list | python3 - <<'PY'
import json, sys
try:
    data = json.load(sys.stdin)
except Exception:
    sys.exit(0)
for item in data:
    jid = item.get("id")
    if jid:
        print(jid)
PY
)
for job in $DLQ_IDS; do
  queuectl_cmd dlq retry "$job" || true
done

echo "[demo] Final status after retries"
sleep 3
queuectl_cmd status

echo "[demo] Final metrics"
queuectl_cmd metrics

echo "[demo] Stopping workers"
queuectl_cmd worker stop

echo "[demo] Demonstration complete"


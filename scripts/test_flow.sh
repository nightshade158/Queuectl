#!/usr/bin/env bash
set -euo pipefail

JAR="$(dirname "$0")/../target/queuectl-0.1.0-jar-with-dependencies.jar"

if [[ ! -f "$JAR" ]]; then
  echo "Build the project first: mvn -DskipTests package" >&2
  exit 1
fi

queuectl() {
  java -jar "$JAR" "$@"
}

queuectl enqueue '{"id":"ok","command":"echo hi"}'
queuectl worker start --count 1
sleep 2
queuectl status
queuectl enqueue '{"id":"bad","command":"bash -lc \"exit 1\"", "max_retries":1}'
sleep 5
queuectl dlq list
queuectl dlq retry bad || true
queuectl worker stop



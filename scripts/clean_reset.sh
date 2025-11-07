#!/usr/bin/env bash
set -euo pipefail

# Resolve project root relative to this script
PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$PROJECT_ROOT"

have_cmd() { command -v "$1" >/dev/null 2>&1; }

# Determine how to invoke queuectl
JAR="$PROJECT_ROOT/target/queuectl-0.1.0-jar-with-dependencies.jar"
run_queuectl() {
  if have_cmd queuectl; then
    queuectl "$@"
  elif [[ -x "$PROJECT_ROOT/bin/queuectl" ]]; then
    "$PROJECT_ROOT/bin/queuectl" "$@"
  elif [[ -f "$JAR" ]]; then
    java -jar "$JAR" "$@"
  else
    echo "queuectl not found. Building and installing..." >&2
    bash "$PROJECT_ROOT/scripts/install.sh"
    queuectl "$@"
  fi
}

echo "Stopping workers and clearing runtime..."
run_queuectl worker stop || true
pkill -f 'com.queuectl.Cli worker run' >/dev/null 2>&1 || true
rm -rf "$PROJECT_ROOT/queuectl_runtime"

echo "Removing local database (clean slate)..."
rm -f "$PROJECT_ROOT/queue.db"

echo "Cleaning Maven build outputs..."
mvn -q -f "$PROJECT_ROOT/pom.xml" clean
rm -rf "$PROJECT_ROOT/target"

# Optional: purge local repository cache for this artifact
if have_cmd mvn; then
  mvn -q -f "$PROJECT_ROOT/pom.xml" dependency:purge-local-repository -DreResolve=true -DmanualInclude='com.queuectl:queuectl' || true
fi

echo "Rebuilding and reinstalling wrapper..."
mvn -q -f "$PROJECT_ROOT/pom.xml" -DskipTests package
bash "$PROJECT_ROOT/scripts/install.sh"

echo "Running tests..."
mvn -q -f "$PROJECT_ROOT/pom.xml" test || true
bash "$PROJECT_ROOT/scripts/test_flow.sh" || true

echo "Smoke check: status"
run_queuectl status || true
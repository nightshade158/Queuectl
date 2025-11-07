#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAR="${PROJECT_ROOT}/target/queuectl-0.1.0-jar-with-dependencies.jar"
WRAPPER_CONTENT='#!/usr/bin/env bash
exec java -jar "__JAR_PATH__" "$@"
'

echo "Building queuectl jar..."
mvn -q -f "${PROJECT_ROOT}/pom.xml" -DskipTests package

if [[ ! -f "$JAR" ]]; then
  echo "Build failed: jar not found at $JAR" >&2
  exit 1
fi

install_wrapper() {
  local target_bin="$1"
  echo "Installing wrapper to $target_bin/queuectl"
  mkdir -p "$target_bin"
  printf "%s" "${WRAPPER_CONTENT/__JAR_PATH__/${JAR}}" > "$target_bin/queuectl"
  chmod +x "$target_bin/queuectl"
}

if command -v sudo >/dev/null 2>&1; then
  if sudo -n true 2>/dev/null; then
    tmpfile="$(mktemp)"
    printf "%s" "${WRAPPER_CONTENT/__JAR_PATH__/${JAR}}" > "$tmpfile"
    chmod +x "$tmpfile"
    sudo mkdir -p /usr/local/bin
    sudo mv "$tmpfile" /usr/local/bin/queuectl
    echo "Installed /usr/local/bin/queuectl"
    exit 0
  fi
fi

# Fallback to user local bin
USER_BIN="$HOME/.local/bin"
install_wrapper "$USER_BIN"
case ":${PATH}:" in
  *":${USER_BIN}:"*) ;; 
  *) echo "Note: add ${USER_BIN} to your PATH (e.g., echo 'export PATH=\"$USER_BIN:\$PATH\"' >> ~/.bashrc && source ~/.bashrc)" ;; 
esac
echo "Installed $USER_BIN/queuectl"



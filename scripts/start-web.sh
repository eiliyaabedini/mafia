#!/usr/bin/env bash
set -euo pipefail
MAFIA_REPO="$(cd "$(dirname "$0")/.." && pwd)"
MAFIA_TARGET=wasmJs
MAFIA_PORT=8080
MAFIA_BUILD=1
MAFIA_BACKGROUND=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --skip-build) MAFIA_BUILD=0; shift ;;
    --background) MAFIA_BACKGROUND=1; shift ;;
    --target) MAFIA_TARGET="${2:?Missing target}"; shift 2 ;;
    --port) MAFIA_PORT="${2:?Missing port}"; shift 2 ;;
    *) echo "Usage: $0 [--skip-build] [--background] [--target wasmJs|js] [--port 8080]" >&2; exit 2 ;;
  esac
done
case "$MAFIA_TARGET" in wasmJs|js) ;; *) echo 'Target must be wasmJs or js.' >&2; exit 2 ;; esac
if [[ "$MAFIA_BUILD" == 1 ]]; then
  (cd "$MAFIA_REPO" && ./gradlew ":webApp:${MAFIA_TARGET}BrowserDistribution")
fi
if [[ "$MAFIA_BACKGROUND" == 1 ]]; then
  mkdir -p "$MAFIA_REPO/webApp/build"
  MAFIA_LOG="$MAFIA_REPO/webApp/build/server-${MAFIA_PORT}.log"
  MAFIA_PID_FILE="$MAFIA_REPO/webApp/build/server-${MAFIA_PORT}.pid"
  nohup python3 "$MAFIA_REPO/scripts/serve-web.py" --target "$MAFIA_TARGET" --port "$MAFIA_PORT" > "$MAFIA_LOG" 2>&1 < /dev/null &
  MAFIA_PID=$!
  echo "$MAFIA_PID" > "$MAFIA_PID_FILE"
  sleep 1
  if ! kill -0 "$MAFIA_PID" 2>/dev/null; then
    cat "$MAFIA_LOG" >&2
    exit 1
  fi
  echo "Production server: http://localhost:${MAFIA_PORT}/ (PID ${MAFIA_PID})"
  echo "Log: $MAFIA_LOG"
else
  exec python3 "$MAFIA_REPO/scripts/serve-web.py" --target "$MAFIA_TARGET" --port "$MAFIA_PORT"
fi

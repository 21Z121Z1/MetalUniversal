#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

RUN_ROOT="${METALLUM_AGENT_RUN_ROOT:-$ROOT/build/agent-runs}"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
OUT="${METALLUM_AGENT_DOCTOR_OUT:-$RUN_ROOT/doctor-$STAMP}"
mkdir -p "$OUT"

failures=0
warnings=0

check() {
  local name="$1"
  local ok="$2"
  local detail="$3"
  if [[ "$ok" == "1" ]]; then
    printf 'PASS %-28s %s\n' "$name" "$detail"
  else
    printf 'FAIL %-28s %s\n' "$name" "$detail" >&2
    failures=$((failures + 1))
  fi
}

warn() {
  local name="$1"
  local detail="$2"
  printf 'WARN %-28s %s\n' "$name" "$detail" >&2
  warnings=$((warnings + 1))
}

os_name="$(uname -s 2>/dev/null || true)"
arch="$(uname -m 2>/dev/null || true)"
check "macOS" "$([[ "$os_name" == "Darwin" ]] && echo 1 || echo 0)" "$os_name"
check "Apple Silicon" "$([[ "$arch" == "arm64" ]] && echo 1 || echo 0)" "$arch"

for command in git java javac swiftc xcodebuild python3; do
  if command -v "$command" >/dev/null 2>&1; then
    check "$command" 1 "$(command -v "$command")"
  else
    check "$command" 0 "not found"
  fi
done

java_version="$(java -version 2>&1 | head -n1 || true)"
java_major="$(java -version 2>&1 | sed -nE 's/.*version "([0-9]+).*/\1/p' | head -n1)"
check "Java 25" "$([[ "$java_major" == "25" ]] && echo 1 || echo 0)" "${java_version:-unknown}"

swift_version="$(swiftc --version 2>/dev/null | head -n1 || true)"
xcode_version="$(xcodebuild -version 2>/dev/null | tr '\n' ' ' || true)"
branch="$(git branch --show-current 2>/dev/null || true)"
head_sha="$(git rev-parse HEAD 2>/dev/null || true)"
status_porcelain="$(git status --porcelain=v1 2>/dev/null || true)"

if [[ -n "$status_porcelain" ]]; then
  warn "working tree" "contains local changes; preserve or commit intentional work before baseline measurement"
fi
if [[ "$branch" != "feature/iris-metal-performance" ]]; then
  warn "branch" "expected feature/iris-metal-performance, found ${branch:-detached}"
fi

if [[ ! -d run/shaderpacks ]] || ! find run/shaderpacks -maxdepth 1 -type f \( -name '*.zip' -o -name '*.jar' \) -print -quit | grep -q .; then
  warn "shader-pack fixtures" "run/shaderpacks has no local pack archive; translation/client acceptance will be incomplete"
fi

if [[ -n "${WORLD:-}" ]] && [[ ! -d "run/saves/$WORLD" ]]; then
  warn "WORLD" "run/saves/$WORLD does not exist"
elif [[ -z "${WORLD:-}" ]]; then
  warn "WORLD" "not set; client performance validation wrapper will require it"
fi

console_locked="unknown"
if command -v ioreg >/dev/null 2>&1; then
  if ioreg -n Root -d1 2>/dev/null | grep -Eq '"(IOConsoleLocked|CGSSessionScreenIsLocked)" ?= ?Yes'; then
    console_locked="true"
    warn "WindowServer" "console appears locked; visible presentation/client validation is not trustworthy"
  else
    console_locked="false"
  fi
fi

printf '%s\n' "$status_porcelain" > "$OUT/git-status.txt"

python3 - "$OUT/environment.json" <<'PY'
import json, os, platform, subprocess, sys

def run(*args):
    try:
        return subprocess.check_output(args, text=True, stderr=subprocess.STDOUT).strip()
    except Exception as exc:
        return f"unavailable: {exc}"

payload = {
    "timestamp_utc": run("date", "-u", "+%Y-%m-%dT%H:%M:%SZ"),
    "repository": os.getcwd(),
    "branch": run("git", "branch", "--show-current"),
    "head": run("git", "rev-parse", "HEAD"),
    "git_status": run("git", "status", "--porcelain=v1"),
    "platform": platform.platform(),
    "machine": platform.machine(),
    "java": run("java", "-version"),
    "javac": run("javac", "-version"),
    "swiftc": run("swiftc", "--version"),
    "xcode": run("xcodebuild", "-version"),
    "world": os.environ.get("WORLD"),
    "shader_pack": os.environ.get("SHADER_PACK"),
}
with open(sys.argv[1], "w", encoding="utf-8") as fh:
    json.dump(payload, fh, indent=2, ensure_ascii=False)
    fh.write("\n")
PY

printf '\nEnvironment artifact: %s\n' "$OUT/environment.json"
printf 'Warnings: %d; failures: %d\n' "$warnings" "$failures"

if (( failures > 0 )); then
  exit 1
fi

#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
WORLD="${WORLD:-Codex Autonomous Eval A1}"

if [[ ! -d "$ROOT/run/saves/$WORLD" ]]; then
	printf 'World does not exist: %s\n' "$ROOT/run/saves/$WORLD" >&2
	exit 2
fi

cd "$ROOT"
exec ./gradlew --no-daemon runClientMetal4IrisMetalFx -Pworld="$WORLD" "$@"

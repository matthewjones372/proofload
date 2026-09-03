#!/usr/bin/env bash
# Proves that `apiCheck` catches a break, by making one.
#
# A test cannot do this: breaking a module's public API from inside that module
# breaks the module the test lives in. So this removes a public function, runs
# the gate, and puts the function back — and fails loudly if the gate did not
# notice, which is the only outcome worth reporting.
#
# Run it from the repository root. It edits one file and restores it on the way
# out, whatever happens.
set -uo pipefail

FILE="kestrel-core/src/main/kotlin/io/github/matthewjones372/kestrel/Progress.kt"
GONE="fun Progress.throttled"
BACKUP="$(mktemp)"

restore() {
  cp "$BACKUP" "$FILE"
  rm -f "$BACKUP"
}
trap restore EXIT

cp "$FILE" "$BACKUP"

# Remove one public function and the class behind it, so the surface really moves.
python3 - "$FILE" <<'PY'
import re, sys
path = sys.argv[1]
text = open(path).read()
start = text.index("fun Progress.throttled")
end = text.index("\n\n", start)
open(path, "w").write(text[:start] + text[start:end].replace("fun Progress.throttled", "internal fun Progress.throttled") + text[end:])
PY

echo "--- removed '$GONE' from the public surface; running ./gradlew apiCheck"
OUTPUT="$(./gradlew apiCheck 2>&1)"
STATUS=$?

if [ "$STATUS" -eq 0 ]; then
  echo "FAILED: apiCheck passed with a public function removed. The gate is not working."
  exit 1
fi

if ! grep -q "throttled" <<<"$OUTPUT"; then
  echo "FAILED: apiCheck failed, but did not name what moved:"
  echo "$OUTPUT" | tail -40
  exit 1
fi

echo "--- apiCheck failed and named it, which is the gate working:"
grep -n "throttled" <<<"$OUTPUT" | head -5

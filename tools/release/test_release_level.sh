#!/usr/bin/env bash
# Checks tools/release/release_level.sh. Run from the repository root; CI runs it.
set -uo pipefail
script="$(dirname "$0")/release_level.sh"
fails=0

check() {
  local want="$1"; shift
  local got
  got=$(printf '%s\0' "$@" | "$script")
  if [ "$got" != "$want" ]; then
    echo "FAIL: want $want, got $got for: $(printf '[%s] ' "$@")"
    fails=$((fails + 1))
  fi
}

check none                                     # nothing merged
check minor "feat: background image under the keys"
check minor "Feat(theme): exact colour picker"
check patch "fix: h taps read as j"
check patch "perf(decoder): cache the trie walk"
check patch "refactor: split WordComposer"
check patch "Hint keys a thumb habitually misses"   # no prefix still ships
check patch "feature: not a known type, still ships"
check none  "docs: README releases section"
check none  "chore: bump Gradle"
check none  "ci: allow starting CI by hand"
check none  "test: golden for clothes"
check major "feat!: new settings format"
check major "fix(engine)!: drop v1 traces"
check major "$(printf 'feat: new trace format\n\nBREAKING CHANGE: v1 traces no longer replay')"
check patch "$(printf 'fix: x\n\nmentions a BREAKING CHANGE: mid-line only')"
check minor "fix: a" "feat: b" "docs: c"            # biggest wins
check major "feat: a" "chore!: drop Android 9"
check none  "docs: a" "ci: b"
check patch "docs: a" "Merge branch main"
check none  "$(printf 'release:skip\nfeat: big but held back')"
check minor "$(printf 'release:minor\nfix: labelled up')"
check major "$(printf 'release:major\ndocs: labelled up')"
check patch "$(printf 'release:skip\nfeat: held')" "fix: y"
check patch "feat:no space after colon is not a prefix"

if [ "$fails" -gt 0 ]; then echo "$fails failure(s)"; exit 1; fi
echo "release_level.sh: all checks pass"

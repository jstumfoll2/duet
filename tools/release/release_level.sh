#!/usr/bin/env bash
# Decides how far a release moves the version, semantic-release style, from
# Conventional Commit prefixes on pull request titles (or commit messages).
#
#   printf '%s\0' "$title_and_body" ... | tools/release/release_level.sh
#
# Each NUL-terminated record is one change: its first line is the title, the
# rest is the body. Prints the biggest step any record asks for:
#
#   major  a "!" before the colon (feat!: or fix(ui)!:), or a body line
#          starting "BREAKING CHANGE:" / "BREAKING-CHANGE:"
#   minor  feat: a new feature or setting
#   patch  fix, perf, refactor, build, revert, and any title without a
#          recognised prefix (so an unlabelled change still ships)
#   none   docs, chore, ci, test, style: nothing in the app changed
#
# A record may also start with a "release:LEVEL" line (from a pull request
# label), which wins over its title: release:skip drops the record,
# release:major/minor/patch sets its level.
set -euo pipefail

rank() { case "$1" in none) echo 0 ;; patch) echo 1 ;; minor) echo 2 ;; major) echo 3 ;; esac; }

level_of() {
  local record="$1" title body type
  title=$(head -n1 <<<"$record")
  body=$(tail -n +2 <<<"$record")
  if [[ "$title" =~ ^release:(skip|patch|minor|major)$ ]]; then
    [ "${BASH_REMATCH[1]}" = skip ] && echo none || echo "${BASH_REMATCH[1]}"
    return
  fi
  if grep -qE '^BREAKING[ -]CHANGE:' <<<"$body"; then echo major; return; fi
  # type(scope)!: description  (type case-insensitive, scope optional)
  local prefix='^([A-Za-z]+)(\([^)]*\))?(!)?: '
  if [[ "$title" =~ $prefix ]]; then
    [ -n "${BASH_REMATCH[3]}" ] && { echo major; return; }
    type=$(tr '[:upper:]' '[:lower:]' <<<"${BASH_REMATCH[1]}")
    case "$type" in
      feat) echo minor ;;
      docs|chore|ci|test|tests|style) echo none ;;
      *) echo patch ;;
    esac
    return
  fi
  echo patch
}

best=none
while IFS= read -r -d '' record || [ -n "$record" ]; do
  [ -z "$record" ] && continue
  level=$(level_of "$record")
  if [ "$(rank "$level")" -gt "$(rank "$best")" ]; then best=$level; fi
done
echo "$best"

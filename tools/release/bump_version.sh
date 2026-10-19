#!/usr/bin/env bash
# Bumps the app version in app/build.gradle, the one place it is declared.
#
#   tools/release/bump_version.sh patch|minor|major [path/to/build.gradle]
#
# versionName goes up by the given semver part (lower parts reset to 0) and
# versionCode always goes up by one, because Android only installs an update
# whose versionCode is higher. Prints the new versionName on stdout.
set -euo pipefail

part="${1:?usage: bump_version.sh patch|minor|major [build.gradle]}"
gradle="${2:-app/build.gradle}"

name=$(sed -n 's/^ *versionName "\([0-9]*\.[0-9]*\.[0-9]*\)" *$/\1/p' "$gradle")
code=$(sed -n 's/^ *versionCode \([0-9][0-9]*\) *$/\1/p' "$gradle")
if [ "$(printf '%s\n' "$name" | wc -l)" -ne 1 ] || [ -z "$name" ] \
   || [ "$(printf '%s\n' "$code" | wc -l)" -ne 1 ] || [ -z "$code" ]; then
  echo "expected exactly one versionName \"X.Y.Z\" and one versionCode N in $gradle" >&2
  exit 1
fi

IFS=. read -r major minor patch <<<"$name"
case "$part" in
  major) major=$((major + 1)); minor=0; patch=0 ;;
  minor) minor=$((minor + 1)); patch=0 ;;
  patch) patch=$((patch + 1)) ;;
  *) echo "unknown part '$part': use patch, minor or major" >&2; exit 1 ;;
esac
new_name="$major.$minor.$patch"
new_code=$((code + 1))

sed -i \
  -e "s/^\( *\)versionName \"$name\" *\$/\1versionName \"$new_name\"/" \
  -e "s/^\( *\)versionCode $code *\$/\1versionCode $new_code/" \
  "$gradle"

echo "$new_name"

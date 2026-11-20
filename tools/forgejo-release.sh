#!/usr/bin/env bash
# Mirrors a GitHub release to the Forgejo copy of this repo: same tag, title,
# notes and APK. Replaces a Forgejo release with the same tag (the rolling
# "latest" one changes on every push).
#
#   FORGEJO_TOKEN=... tools/forgejo-release.sh <tag> <title> <notes-file> <apk> [prerelease]
set -euo pipefail

TAG="$1"; TITLE="$2"; NOTES="$3"; APK="$4"; PRE="${5:-false}"
API="${FORGEJO_API:-https://git.oiupoyt.space/api/v1}"
REPO="${FORGEJO_REPO:-oiupoyt/DroidTTY}"
: "${FORGEJO_TOKEN:?FORGEJO_TOKEN is not set}"
auth=(-H "Authorization: token $FORGEJO_TOKEN")

# The release needs its tag on Forgejo, pointing at the commit that was built.
host=$(echo "$API" | sed -E 's#https://([^/]+)/.*#\1#')
git push --force "https://oiupoyt:${FORGEJO_TOKEN}@${host}/${REPO}.git" "HEAD:refs/tags/$TAG" >/dev/null 2>&1

old=$(curl -fsS "${auth[@]}" "$API/repos/$REPO/releases/tags/$TAG" 2>/dev/null | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])' 2>/dev/null || true)
if [ -n "$old" ]; then
  curl -fsS -X DELETE "${auth[@]}" "$API/repos/$REPO/releases/$old" >/dev/null
fi

body=$(python3 -c 'import json,sys; print(json.dumps({"tag_name": sys.argv[1], "name": sys.argv[2], "body": open(sys.argv[3]).read(), "prerelease": sys.argv[4] == "true"}))' "$TAG" "$TITLE" "$NOTES" "$PRE")
id=$(curl -fsS -X POST "${auth[@]}" -H 'Content-Type: application/json' -d "$body" "$API/repos/$REPO/releases" \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])')
curl -fsS -X POST "${auth[@]}" -F "attachment=@$APK" "$API/repos/$REPO/releases/$id/assets?name=$(basename "$APK")" >/dev/null
echo "forgejo release $TAG -> $API/repos/$REPO/releases/$id"

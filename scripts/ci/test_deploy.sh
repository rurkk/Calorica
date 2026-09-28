#!/usr/bin/env bash
# Isolated control-flow test. No real registry, deployment or database is touched.
set -Eeuo pipefail
fixture=$(mktemp -d /tmp/calorica-deploy-test-XXXXXXXX)
trap 'rm -rf "$fixture"' EXIT
mkdir -p "$fixture/bin" "$fixture/shared" "$fixture/old" "$fixture/new"
cp deploy/backend/deploy.sh deploy/backend/compose.yml "$fixture/new/"
printf 'placeholder\n' > "$fixture/shared/backend.env"
printf 'old-config\n' > "$fixture/old/compose.yml"
old="ghcr.io/test/backend@sha256:$(printf 'a%.0s' {1..64})"
new="ghcr.io/test/backend@sha256:$(printf 'b%.0s' {1..64})"
printf '%s\n' "$old" > "$fixture/old/image"
cat > "$fixture/bin/docker" <<'MOCK'
#!/usr/bin/env bash
set -eu
printf '%s %s\n' "$BACKEND_IMAGE" "$*" >> "$CALL_LOG"
case "$*" in
  *'exec -T db'*)
    if [[ "$SCENARIO" = backup-failure ]]; then exit 1; fi
    printf 'fixture-pg-dump\n'
    ;;
  *'up -d --no-deps'*backend)
    if [[ "$SCENARIO" = readiness-failure && "$BACKEND_IMAGE" = "$NEW_IMAGE" ]]; then exit 1; fi
    ;;
esac
MOCK
chmod +x "$fixture/bin/docker"
export PATH="$fixture/bin:$PATH" CALL_LOG="$fixture/calls" NEW_IMAGE="$new"

export SCENARIO=success
bash "$fixture/new/deploy.sh" "$new" "$fixture"
test "$(readlink -f "$fixture/current")" = "$fixture/new"
test "$(cat "$fixture/new/image")" = "$new"
backups=("$fixture/shared/backups/"*.dump)
test -s "${backups[0]}"

ln -sfn "$fixture/old" "$fixture/current"
: > "$CALL_LOG"
export SCENARIO=readiness-failure
if bash "$fixture/new/deploy.sh" "$new" "$fixture"; then exit 1; fi
test "$(readlink -f "$fixture/current")" = "$fixture/old"
grep -F "$old compose" "$CALL_LOG" | grep -F "$fixture/old/compose.yml" > /dev/null

: > "$CALL_LOG"
export SCENARIO=backup-failure
if bash "$fixture/new/deploy.sh" "$new" "$fixture"; then exit 1; fi
if grep -q 'up -d --no-deps' "$CALL_LOG"; then exit 1; fi
test "$(readlink -f "$fixture/current")" = "$fixture/old"
echo 'deploy control-flow checks passed'

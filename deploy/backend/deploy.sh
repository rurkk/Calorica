#!/usr/bin/env bash
set -Eeuo pipefail
umask 077
# Usage: deploy.sh IMAGE@sha256:... /absolute/calorica-root
image=${1:?Image digest required}
root=${2:?Absolute deployment root required}
[[ "$image" =~ ^ghcr\.io/[a-z0-9._/-]+@sha256:[a-f0-9]{64}$ ]] || { echo "Invalid image digest" >&2; exit 2; }
[[ "$root" =~ ^/[a-zA-Z0-9_/-]+$ && "$root" != / ]] || exit 2
release=$(cd -- "$(dirname -- "$0")" && pwd)
[[ -f "$root/shared/backend.env" ]] || { echo "Missing shared/backend.env" >&2; exit 2; }
mkdir -p "$root/shared/backups"
exec 9>"$root/shared/deploy.lock"
flock -n 9 || { echo "Another Calorica deployment is active" >&2; exit 1; }
export BACKEND_IMAGE="$image"
compose() { docker compose --project-name calorica-backend --env-file "$root/shared/backend.env" -f "$release/compose.yml" "$@"; }
compose config --quiet
previous_release=''
previous_image=''
if [[ -L "$root/current" ]]; then
  previous_release=$(readlink -f "$root/current")
  previous_image=$(cat "$previous_release/image")
  [[ "$previous_image" =~ ^ghcr\.io/[a-z0-9._/-]+@sha256:[a-f0-9]{64}$ ]] || exit 2
fi
compose pull
compose up -d --wait --wait-timeout 120 db
# A failed/empty dump blocks migration. Keep custom-format backups outside containers.
backup="$root/shared/backups/predeploy-$(date -u +%Y%m%dT%H%M%SZ)-$$.dump"
# Expand database variables inside the container, never on the host.
# shellcheck disable=SC2016
compose exec -T db sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc' > "$backup.tmp"
test -s "$backup.tmp"
mv "$backup.tmp" "$backup"
rollback() {
  echo "Deployment failed; database is preserved." >&2
  if [[ -n "$previous_image" ]]; then
    echo "Restoring previous application and Compose configuration." >&2
    BACKEND_IMAGE="$previous_image" docker compose --project-name calorica-backend \
      --env-file "$root/shared/backend.env" -f "$previous_release/compose.yml" \
      up -d --no-deps --wait --wait-timeout 150 backend
  else
    compose stop backend
  fi
}
if ! compose up -d --no-deps --wait --wait-timeout 150 backend; then
  rollback
  exit 1
fi
printf '%s\n' "$image" > "$release/image"
ln -sfn "$release" "$root/current.next"
mv -Tf "$root/current.next" "$root/current"
echo "Calorica deployment is healthy: $image"

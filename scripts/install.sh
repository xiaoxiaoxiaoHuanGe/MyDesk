#!/usr/bin/env bash
set -euo pipefail
source_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
target_dir="${1:-/opt/mydesk}"
if [[ "$target_dir" != /opt/mydesk ]]; then
  printf '%s\n' 'This installer only writes to /opt/mydesk. For other locations, copy the release manually.' >&2
  exit 1
fi
if [[ "$source_dir" == "$target_dir" ]]; then
  printf '%s\n' 'Release is already at /opt/mydesk; use deploy/compose.yaml directly.'
  exit 0
fi
command -v docker >/dev/null
docker compose version >/dev/null
mkdir -p "$target_dir/mydesk" "$target_dir/frontend" "$target_dir/deploy" "$target_dir/scripts" "$target_dir/data"
for file in "$source_dir/mydesk/"*.py; do install -m 0644 "$file" "$target_dir/mydesk/"; done
for file in "$source_dir/frontend/"*; do install -m 0644 "$file" "$target_dir/frontend/"; done
for name in requirements.txt .dockerignore; do install -m 0644 "$source_dir/$name" "$target_dir/$name"; done
for name in compose.yaml Dockerfile openresty-location.conf; do
  if [[ -f "$target_dir/deploy/$name" ]]; then
    cp -p "$target_dir/deploy/$name" "$target_dir/deploy/$name.backup.$(date +%Y%m%d%H%M%S)"
  fi
  install -m 0644 "$source_dir/deploy/$name" "$target_dir/deploy/$name"
done
if [[ ! -f "$target_dir/deploy/.env" ]]; then install -m 0600 "$source_dir/deploy/.env.example" "$target_dir/deploy/.env"; fi
if [[ "$(id -u)" == 0 ]]; then chown 10001:10001 "$target_dir/data"; fi
chmod 0700 "$target_dir/data"
printf '%s\n' 'Independent MyDesk files installed. Existing data retained. No containers started.'
printf '%s\n' 'Follow docs/DEPLOY.md to configure the reverse proxy before exposing the service.'

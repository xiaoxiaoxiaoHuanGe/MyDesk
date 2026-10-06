#!/usr/bin/env bash
# Run once as root on the existing Ubuntu /opt/mydesk server.
set -euo pipefail
umask 077
[[ "$(id -u)" == 0 ]] || { echo 'Run this initializer as root.' >&2; exit 1; }
[[ "$(uname -m)" == x86_64 ]] || { echo 'This workflow requires an amd64 server.' >&2; exit 1; }
[[ -f /opt/mydesk/compose.yaml && -d /opt/mydesk/data && ! -L /opt/mydesk/data ]] || { echo 'Existing /opt/mydesk deployment required.' >&2; exit 1; }
for tool in curl python3 docker ssh-keygen; do command -v "$tool" >/dev/null; done
compose_help="$(docker compose up --help)"
[[ "$compose_help" == *--wait-timeout* ]] || { echo 'Update Docker Compose: --wait-timeout support is required.' >&2; exit 1; }
docker inspect mydesk | python3 -c '
import json,sys
from pathlib import Path
c=json.load(sys.stdin)[0]
m=[m for m in c["Mounts"] if m["Destination"]=="/data"]
assert c["State"]["Running"], "MyDesk must already be running"
assert c["Config"]["Labels"].get("com.docker.compose.project")=="mydesk", "Unexpected Compose project"
assert c["HostConfig"]["NetworkMode"]=="host", "Host network required"
assert len(m)==1 and Path(m[0]["Source"]).resolve()==Path("/opt/mydesk/data"), "Unexpected data mount"
'
deploy_host="${MYDESK_DEPLOY_HOST:-}"
deploy_port="${MYDESK_DEPLOY_PORT:-22}"
[[ "$deploy_host" =~ ^[a-zA-Z0-9][a-zA-Z0-9.-]*$ ]] || { echo 'Set MYDESK_DEPLOY_HOST to your public server IP or hostname.' >&2; exit 1; }
[[ "$deploy_port" =~ ^[0-9]{1,5}$ ]] || { echo 'Invalid SSH port.' >&2; exit 1; }
[[ -f /etc/ssh/ssh_host_ed25519_key.pub ]] || { echo 'An existing ed25519 SSH host key is required.' >&2; exit 1; }
sshd_config="$(/usr/sbin/sshd -T -C "user=root,host=$deploy_host,addr=127.0.0.1")"
[[ "$sshd_config" == *'pubkeyauthentication yes'* ]] || { echo 'SSH public-key authentication is disabled.' >&2; exit 1; }
[[ "$sshd_config" != *'permitrootlogin no'* ]] || { echo 'Root SSH is disabled. Use a dedicated deployment account design instead.' >&2; exit 1; }
[[ "$sshd_config" == *'authorizedkeysfile .ssh/authorized_keys'* ]] || { echo 'Non-standard AuthorizedKeysFile: configure deployment authorization manually.' >&2; exit 1; }

# Resolve once; daily updates never download or replace the privileged receiver.
setup_ref="${MYDESK_SETUP_REF:-main}"
[[ "$setup_ref" =~ ^[a-zA-Z0-9_./-]+$ && "$setup_ref" != *..* ]] || { echo 'Invalid setup ref.' >&2; exit 1; }
setup_sha="$(curl --fail --silent --show-error --retry 3 "https://api.github.com/repos/xiaoxiaoxiaoHuanGe/MyDesk/commits/$setup_ref" | python3 -c 'import json,sys; print(json.load(sys.stdin)["sha"])')"
[[ "$setup_sha" =~ ^[0-9a-f]{40}$ ]] || { echo 'Could not resolve the setup commit.' >&2; exit 1; }
install -d -m 0755 /usr/local/libexec/mydesk
receiver_temp="$(mktemp /usr/local/libexec/mydesk/receiver.XXXXXX)"
trap 'rm -f -- "$receiver_temp"' EXIT
curl --fail --silent --show-error --retry 3 "https://raw.githubusercontent.com/xiaoxiaoxiaoHuanGe/MyDesk/$setup_sha/scripts/deploy_server.py" > "$receiver_temp"
python3 -c 'import ast,sys; ast.parse(open(sys.argv[1]).read())' "$receiver_temp"
install -m 0755 "$receiver_temp" /usr/local/libexec/mydesk/deploy_server.py

install -d -m 0700 /root/.mydesk-actions /root/.ssh
key=/root/.mydesk-actions/id_ed25519
if [[ ! -f "$key" ]]; then
  ssh-keygen -q -t ed25519 -N '' -C mydesk-github-actions -f "$key"
fi
public_key="$(ssh-keygen -y -f "$key")"
authorization="restrict,command=\"/usr/bin/python3 -I /usr/local/libexec/mydesk/deploy_server.py\" $public_key mydesk-github-actions"
touch /root/.ssh/authorized_keys
chmod 0600 /root/.ssh/authorized_keys "$key"
if ! grep -Fqx -- "$authorization" /root/.ssh/authorized_keys; then
  printf '\n%s\n' "$authorization" >> /root/.ssh/authorized_keys
fi
host_alias="$deploy_host"
if [[ "$deploy_port" != 22 ]]; then host_alias="[$deploy_host]:$deploy_port"; fi
read -r host_key_type host_key_value _ < /etc/ssh/ssh_host_ed25519_key.pub
printf '%s %s %s\n' "$host_alias" "$host_key_type" "$host_key_value" > /root/.mydesk-actions/known_hosts
cat > /root/.mydesk-actions/README.txt <<EOF
GitHub production environment secrets (never paste the private key into chat):
DEPLOY_HOST = $deploy_host
DEPLOY_PORT = $deploy_port (optional when 22)
DEPLOY_SSH_KEY = complete contents of /root/.mydesk-actions/id_ed25519
DEPLOY_KNOWN_HOSTS = complete contents of /root/.mydesk-actions/known_hosts
Receiver installed from commit $setup_sha.
EOF
cat /root/.mydesk-actions/README.txt
printf '\n%s\n' 'Initialization complete. The running application has not been restarted.'

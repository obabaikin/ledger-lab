#!/bin/sh
# Prepares the server over SSH from the development machine: SSH by key only, security updates, k3s, a kubeconfig.
# Usage: SERVER=<server ip> infra/server/bootstrap.sh. Safe to run again: changes only what differs.
set -eu

: "${SERVER:?usage: SERVER=<server ip> $0}"
K3S_VERSION="v1.36.4+k3s1"
K3S_INSTALLER_URL="https://raw.githubusercontent.com/k3s-io/k3s/v1.36.4%2Bk3s1/install.sh"
K3S_INSTALLER_SHA256="46177d4c99440b4c0311b67233823a8e8a2fc09693f6c89af1a7161e152fbfad"
NODE_READY_ATTEMPTS=60
KUBECONFIG_OUT="${KUBECONFIG_OUT:-$HOME/.kube/ledger-lab-server.yaml}"
TUNNEL_PORT="${TUNNEL_PORT:-16443}"
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"

# sshd takes the first value it reads and the image's 50-cloud-init.conf allows passwords, so this file must sort first.
SSHD_DROP_IN=/etc/ssh/sshd_config.d/00-ledger-lab.conf
APT_DROP_IN=/etc/apt/apt.conf.d/52ledger-lab
KUBELET_DROP_IN=/var/lib/rancher/k3s/agent/etc/kubelet.conf.d/50-crash-loop-backoff.conf
TRAEFIK_CONFIG=/var/lib/rancher/k3s/server/manifests/traefik-config.yaml

# One connection for the whole run: it stays open while sshd reloads, a way back in if the new config locks keys out.
SOCKET_DIR="$(mktemp -d)"
trap 'ssh -o ControlPath="$SOCKET_DIR/master" -O exit "root@$SERVER" 2>/dev/null; rm -rf "$SOCKET_DIR"' EXIT
remote() {
  ssh -o ControlMaster=auto -o ControlPath="$SOCKET_DIR/master" -o ControlPersist=300 "root@$SERVER" "$@"
}

# Copies a file when its content differs; succeeds only if it copied. Always called as a condition, where set -e is
# off, so a failed copy exits explicitly.
put() {
  wanted=$(sha256sum <"$1" | cut -d ' ' -f 1)
  present=$(remote "sha256sum '$2' 2>/dev/null || true" | cut -d ' ' -f 1) || exit 1
  [ "$wanted" = "$present" ] && return 1
  remote "mkdir -p '$(dirname "$2")' && cat >'$2.new' && mv '$2.new' '$2'" <"$1" || exit 1
  echo "updated $2"
}

# Key-only sshd must not be switched on for an operator who got in some other way.
if ! ssh -o ControlPath=none -o BatchMode=yes "root@$SERVER" true; then
  echo "no login by key to root@$SERVER: add the key to /root/.ssh/authorized_keys first" >&2
  exit 1
fi

if put "$HERE/sshd-ledger-lab.conf" "$SSHD_DROP_IN"; then
  remote "sshd -t && systemctl reload ssh"
fi
effective=$(remote "sshd -T" | grep -E '^(passwordauthentication|kbdinteractiveauthentication|permitrootlogin) ' | sort | tr '\n' ' ')
if [ "$effective" != "kbdinteractiveauthentication no passwordauthentication no permitrootlogin without-password " ]; then
  echo "sshd still allows more than keys: $effective" >&2
  exit 1
fi
without_key=$(ssh -o ControlPath=none -o BatchMode=yes -o PubkeyAuthentication=no "root@$SERVER" true 2>&1 || true)
case "$without_key" in
  *"Permission denied (publickey)."*) ;;
  *) echo "sshd offers a way in other than a key: $without_key" >&2; exit 1 ;;
esac

remote "dpkg -s unattended-upgrades >/dev/null 2>&1 \
  || (apt-get update -q && DEBIAN_FRONTEND=noninteractive apt-get install -y -q unattended-upgrades)"
put "$HERE/apt-unattended-upgrades.conf" "$APT_DROP_IN" || true

kubelet_changed=false
put "$ROOT/infra/k3d/kubelet-crash-loop-backoff.yaml" "$KUBELET_DROP_IN" && kubelet_changed=true
put "$HERE/traefik-config.yaml" "$TRAEFIK_CONFIG" || true

# An interrupted install can leave the binary in place without a running service; the installer is safe to rerun.
if ! remote "k3s --version 2>/dev/null" | grep -qF "k3s version $K3S_VERSION " \
  || ! remote "systemctl is-active --quiet k3s"; then
  # The installer checks the k3s binary against the release's sha256sum file; this checks the installer.
  remote "curl -sSfL -o /root/k3s-install.sh '$K3S_INSTALLER_URL' \
    && echo '$K3S_INSTALLER_SHA256  /root/k3s-install.sh' | sha256sum --check --quiet \
    && INSTALL_K3S_VERSION='$K3S_VERSION' sh /root/k3s-install.sh"
elif [ "$kubelet_changed" = true ]; then
  remote "systemctl restart k3s"
fi

attempt=0
until remote "k3s kubectl wait --for=condition=Ready node --all --timeout=5s" >/dev/null 2>&1; do
  attempt=$((attempt + 1))
  if [ "$attempt" -ge "$NODE_READY_ATTEMPTS" ]; then
    echo "the node is not Ready after $attempt attempts" >&2
    remote "k3s kubectl get nodes" >&2 || true
    exit 1
  fi
  sleep 2
done

mkdir -p "$(dirname "$KUBECONFIG_OUT")"
(umask 077 && remote "cat /etc/rancher/k3s/k3s.yaml" \
  | sed "s#https://127.0.0.1:6443#https://127.0.0.1:$TUNNEL_PORT#" >"$KUBECONFIG_OUT")
remote "k3s kubectl get nodes -o wide"
echo "kubeconfig: $KUBECONFIG_OUT"
echo "tunnel:     ssh -fNL $TUNNEL_PORT:127.0.0.1:6443 root@$SERVER"

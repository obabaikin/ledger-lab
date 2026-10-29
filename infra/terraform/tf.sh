#!/bin/sh
# Terraform in the container CI uses, with the Cloudflare and Contabo keys from the local config files.
# Usage: infra/terraform/tf.sh <terraform arguments>, e.g. tf.sh plan, tf.sh apply -target=<resource>
set -eu

HERE="$(cd "$(dirname "$0")" && pwd)"
CONFIG_DIR="${CONFIG_DIR:-$HOME/.config/ledger-lab}"
# The same image as the CI job "infra" (.github/workflows/ci.yml).
TERRAFORM_IMAGE=hashicorp/terraform:1.16.6@sha256:bfffc1ded5fbf0a17beaaf22eb4793980abdb19d0761ff6a3a0efb2ab4d9b316

set -a
# shellcheck source=/dev/null
. "$CONFIG_DIR/cloudflare.env"
# shellcheck source=/dev/null
. "$CONFIG_DIR/contabo.env"
set +a

# A terminal lets apply ask for its confirmation.
TTY=
[ -t 0 ] && TTY=-t

# The Contabo provider writes its cache to $HOME, which the container's user does not own.
exec docker run --rm -i $TTY -u "$(id -u):$(id -g)" -e HOME=/tmp \
  -e CLOUDFLARE_API_TOKEN -e CNTB_OAUTH2_CLIENT_ID -e CNTB_OAUTH2_CLIENT_SECRET -e CNTB_OAUTH2_USER -e CNTB_OAUTH2_PASS \
  -v "$HERE:/w" -w /w "$TERRAFORM_IMAGE" "$@"

# Operations

Running the system in local Kubernetes and on the server, creating invites, and working on the page.
For the services on the host with Docker Compose, see the [README](../../README.md#running-locally).

## Running in local Kubernetes

The same system on k3s in Docker ([k3d](https://k3d.io)), on one node the size of the target server
(12 GB: the bank in 8 GB, the generator beside it). Requirements: JDK 21, Docker and, on `PATH`, k3d 5.9.0, kubectl 1.36.4 and Helm 4.3.0.

```bash
infra/k3d/up.sh     # cluster, the five images, the chart (infra/helm/ledger-lab); safe to run again
infra/k3d/down.sh   # deletes the cluster with its volumes
```

Control answers on `localhost:8083` (an invite is created as below); nothing else is exposed.

- **The ledger runs three instances** with Kafka's static membership: an instance that restarts under
  its name gets its own partitions back without a rebalance of the others.
- **Readiness** is HTTP only, so a rebalance never takes the ledger out of its Service; whether a run may
  start is control's question, answered by the partitions the live instances hold.
- **Every pod has a memory limit**, set from measurements under load; a JVM takes half of it as heap, the generator about two thirds:
  most of its memory is the journals.
- **A crashed pod restarts within a second**: the cluster caps the kubelet's crash-loop backoff at 1 s
  (`infra/k3d/kubelet-crash-loop-backoff.yaml`), otherwise a second crash in a row waits 10 s, then twice as long.
  A cluster created before needs `down.sh` and `up.sh`.

## Running on a server

One Contabo Cloud VPS 6 NVMe (6 vCPU, 12 GB) with k3s; the bank fits in 8 GB, the generator beside it.
The server is ordered by hand, and Terraform does not manage it: there is one server, and a
configuration that can buy one should not exist. Terraform (`infra/terraform`) only reads it and
manages its firewall (inbound SSH, HTTP and HTTPS only) and the domain's DNS record.

```bash
SERVER=<ip> infra/server/bootstrap.sh       # over SSH: key-only login, a host firewall, security updates, k3s, a kubeconfig; safe to run again
ssh -fNL 16443:127.0.0.1:6443 root@<ip>     # the cluster's API is reached only through this tunnel
infra/server/deploy.sh <commit sha>         # cert-manager and the chart, with that commit's images
```

- **Images:** every push to `main` builds the five images in CI and publishes them to GHCR, tagged
  with the commit. Merging deploys nothing.
- **Deploying** is by hand, `deploy.sh <sha>`. It refuses while runs are in progress (`--force`
  overrides), and before Helm when an image is missing. The first deploy generates the database
  passwords into a local file outside the repository; a role added to the chart later gets one on the
  next deploy, and a deploy that would still carry a `change-me` placeholder is refused.
- **Load:** the server's rates are in `infra/server/values.yaml`.
- **HTTPS:** cert-manager gets a Let's Encrypt certificate (HTTP-01 through Traefik); plain HTTP
  redirects to HTTPS, and Traefik limits requests per client address. Prometheus raises an alert
  14 days before the certificate expires.
- **Node and database metrics:** node-exporter (CPU with steal and iowait, pressure, disks) and
  postgres-exporter (commits, checkpoints, locks, connections) are scraped every 5 s. The exporter logs
  in as `monitor`, a member of `pg_monitor`: it sees the statistics, not the data. The role is created or
  updated on every deploy, as the init script runs only on an empty volume. No port of either is
  published; read them through `kubectl port-forward svc/prometheus 9090`.
- **Operator access** is SSH with a key only: `kubectl` and `psql` (via `kubectl exec`) through the
  tunnel. Nothing but the page is published.
- **Hardening:** a host firewall (nftables) under the provider's lets only SSH reach the host itself;
  `bootstrap.sh` puts it on with a timer that removes it again unless a new SSH login gets through.
  Security updates install on their own, and a reboot for a new kernel is done by hand between runs:
  `deploy.sh` says when one is due. The services run as a non-root user without capabilities or a
  Kubernetes API token; responses carry HSTS, `nosniff`, frame-deny and no-referrer headers.
  cert-manager's chart and images are pinned by digest.

## Invites

The page is opened with an invite link, `/?ref=<token>`. Invites are created by hand in the
database; there is no endpoint for them:

```sql
INSERT INTO control.invite_tokens (token, expires_at, quota)
VALUES ('<random token>', now() + interval '30 days', 5);  -- runs per hour
```

## Working on the page

The page is React and TypeScript in `control/page`; its types are generated from `control/openapi.json`.
Gradle downloads Node, runs the page's tests with control's and puts the built page into control's jar,
so `infra/k3d/up.sh` deploys it like the rest. For work on the page alone, with control on
`localhost:8083`:

```bash
cd control/page && npm ci && npm run types && npm run dev   # Vite on localhost:5173, /api proxied to control
```


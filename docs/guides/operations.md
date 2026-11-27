# Operations

Running the system in local Kubernetes and on the server, deploys through Flux, creating invites, and working on the
page.
For the services on the host with Docker Compose, see the [README](../../README.md#running-locally).

## Running in local Kubernetes

The same system on k3s in Docker ([k3d](https://k3d.io)), on one node the size of the target server
(12 GB: the bank, the generator, monitoring, k3s and the OS fit in it together, but for the limits while the gateway
is replaced, [below](#memory)). Requirements: JDK 25, Docker and, on `PATH`, k3d 5.9.0, kubectl 1.36.4 and Helm 4.3.0.

```bash
infra/k3d/up.sh     # cluster, the five images, the chart (infra/helm/ledger-lab); safe to run again
infra/k3d/down.sh   # deletes the cluster with its volumes
```

`up.sh` installs the working tree as one release, for development. `infra/k3d/flux.sh` deploys a commit the way the
server is to be deployed, through Flux ([below](#in-k3d)).

Control answers on `localhost:8083` (an invite is created as below); nothing else is exposed.

- **The ledger runs two instances.** One that stops leaves its Kafka group and hands over only its own partitions,
  while the other keeps reading; a new one is ready once it holds partitions.
- **The gateway is replaced without downtime**: the new pod becomes ready before the old one stops; the old one answers
  what it holds within 40 s. Reporting and the generator are replaced the same way; control stops first. Postgres takes
  150 connections so both pools fit during the swap ([ADR 0020](../adr/0020-gateway-deploys-without-downtime.md)).
- **Readiness** is HTTP only, so a rebalance never takes the ledger out of its Service.
- **Periods** are an hour; to see closings sooner, `up.sh --set periodMinutes=10` with control's closing times
  shortened to fit (`closing.*` in the chart's values; the verdict deadline must stay under the period plus a minute).
- **Every pod has a memory limit**, set from measurements under load; a JVM takes half of it as heap; the gateway's,
  the generator's and control's heap is their memory less a fixed reserve (550, 450 and 320 MiB), set from the start,
  and malloc keeps two arenas, as outside the heap a JVM took about 290 MiB and malloc up to 135 MiB more under load:
  the gateway's heap holds the open submissions, the generator's the requests in flight. An OutOfMemoryError of the
  gateway or control leaves a heap dump in its pod's `/dumps`; control then exits, and keeps its GC log there.
- **A crashed pod restarts within a second**: the cluster caps the kubelet's crash-loop backoff at 1 s
  (`infra/k3d/kubelet-crash-loop-backoff.yaml`), otherwise a second crash in a row waits 10 s, then twice as long.
  A cluster created before needs `down.sh` and `up.sh`.

## Running on a server

One Contabo Cloud VPS 6 NVMe (6 vCPU, 12 GB) with k3s; the bank, the generator, monitoring, k3s and the OS fit in its
12 GB together, but for the limits while the gateway is replaced ([Memory](#memory)).
The server is ordered by hand, and Terraform does not manage it: there is one server, and a
configuration that can buy one should not exist. Terraform (`infra/terraform`) only reads it and
manages its firewall (inbound SSH, HTTP and HTTPS only) and the domain's DNS: the A record and the mail
records (SPF, DKIM, DMARC and Mailjet's ownership record). It runs through `infra/terraform/tf.sh`
(`tf.sh plan`, `tf.sh apply`): the image CI uses, with the Cloudflare and Contabo keys from
`~/.config/ledger-lab/cloudflare.env` and `contabo.env`.

```bash
SERVER=<ip> infra/server/bootstrap.sh       # over SSH: key-only login, a host firewall, security updates, k3s, a kubeconfig; safe to run again
infra/server/tunnel-open.sh                 # the cluster's API is reached only through this tunnel; tunnel-close.sh ends it
infra/server/deploy.sh <release tag>        # cert-manager and the chart, with that release's images
```

- **Images:** every push to `main` builds in CI the images whose module changed and publishes them to GHCR, each
  tagged with a hash of what it is built from; an unchanged module keeps the image it has. Until the server is moved
  to Flux ([below](#deploys-through-flux)), merging deploys nothing.
- **Releases:** only a release reaches the server. A release is a SemVer tag on `main`: a new feature raises
  MINOR (`v0.5.0`), a fix only PATCH (`v0.4.1`). After the merge and its CI run, `git tag -a vX.Y.Z <sha>` and
  `git push origin vX.Y.Z`; the tag adds its name to the images of that commit's modules, nothing is
  rebuilt, so the server runs the images CI tested. The images carry no version: `deploy.sh` passes the tag to
  the chart, and the page shows it. A commit that is not on `main` has no images and cannot be released.
- **Deploying** is by hand, `deploy.sh <release tag>`, while the clients go on sending. It refuses a tag that is
  not on GitHub, and before Helm when a tag's image is missing, which is so until the tag's CI run is green. It
  refuses a server still on the chart of version 1 (below). A release that changes a contract between the services
  is deployed with `deploy.sh <tag> --contract`, which refuses outside a declared maintenance window. The first deploy generates the database
  passwords into a local file outside the repository; a role added to the chart later gets one on the
  next deploy, and a deploy that would still carry a `change-me` placeholder is refused.
- **Load:** the server's rates are in `infra/server/values.yaml`.
- **HTTPS:** cert-manager gets a Let's Encrypt certificate (HTTP-01 through Traefik); plain HTTP
  redirects to HTTPS, and Traefik limits requests per client address. Prometheus raises an alert
  14 days before the certificate expires. Traefik's access log (`kubectl -n kube-system logs deploy/traefik`)
  keeps the time, method, status, duration and router; the client address and `User-Agent` stay out, and so
  do the path, query and `Referer`, since they carry invite tokens and session ids. It is limited in size; the
  oldest entries are overwritten.
- **Node and database metrics:** node-exporter (CPU with steal and iowait, pressure, disks; not the
  node's network, as it runs in its own pod's network) and
  postgres-exporter (commits, checkpoints, locks, connections, and the 100 statements with the most time from
  `pg_stat_statements`) are scraped every 5 s, and so is each container's working-set memory from the kubelet, the
  number the kernel compares with the limit. Postgres logs every statement slower than a second. The exporter logs
  in as `monitor`, a member of `pg_monitor`: it sees the statistics, not the data. The role is created or
  updated on every deploy, as the init script runs only on an empty volume. No port of either is
  published; read them through `kubectl port-forward svc/prometheus 9090`.
- **Memory limits:** every pod of the chart has one, at least a quarter above its peak on the server
  ([results](../results/2026-10-23-server-baseline.md)). Traefik gets 256 MiB in
  `infra/server/traefik-config.yaml`; k3s's other pods (CoreDNS, the metrics server, the local-path
  provisioner) are left as k3s ships them, the one exception: together they peaked under 200 MiB.
- **Right after a deploy** the gateway warms up while it already takes transfers.
- **Maintenance windows:** `infra/server/maintenance.sh add 2026-11-12T02:00Z 2026-11-12T03:00Z` declares one
  (UTC, whole minutes), `list` shows them and `cancel <id>` withdraws one before it starts. The page shows a window to
  every visitor; its bad minutes stay out of the error budget only if it was declared at least 3 hours before its
  start ([SLO](slo.md)).
- **Operator access** is SSH with a key only: `kubectl` and `psql` (via `kubectl exec`) through the
  tunnel. Nothing but the page is published.
- **Hardening:** a host firewall (nftables) under the provider's lets only SSH reach the host itself;
  `bootstrap.sh` puts it on with a timer that removes it again unless a new SSH login gets through.
  Security updates install on their own, and a reboot for a new kernel is done by hand in a maintenance window:
  `deploy.sh` says when one is due. The services run as a non-root user without capabilities or a
  Kubernetes API token; responses carry HSTS, `nosniff`, frame-deny and no-referrer headers.
  cert-manager's chart and images are pinned by digest.

### Moving from runs to one bank

Version 1 (runs, up to `v0.9.0`) and the bank around the clock ([ADR 0021](../adr/0021-one-bank-around-the-clock.md))
share no data: the database's schemas and the volumes differ, and `helm upgrade` does not change a volume. The move
is a fresh install, and `deploy.sh` refuses to upgrade a server whose database lacks the generator's role:

1. Optionally, keep a dump: `kubectl -n ledger-lab exec postgres-0 -- pg_dump -U admin -Fc ledgerlab > <file>`,
   outside the repository.
2. `helm uninstall ledger-lab -n ledger-lab`, then delete the volumes of Postgres, Kafka and Prometheus
   (`kubectl -n ledger-lab get pvc`, `kubectl -n ledger-lab delete pvc <name>`). The namespace and the TLS
   certificate's secret stay.
3. `deploy.sh <release tag>`. The ledger opens the accounts on its first start; the clients start sending once
   the bank is ready.

A maintenance window declared in the old database is gone with it; declare it again after the install, with its
original time of declaration, or its minutes count against the budget.

## Deploys through Flux

How a commit of `main` reaches a cluster by itself, and what the operator does when it does not
([ADR 0024](../adr/0024-deploys-without-a-maintenance-window.md)). It runs in k3d and in the end-to-end test; the server
is moved to it once, by hand ([below](#moving-the-server-to-flux)), and is deployed with `deploy.sh` until then.

The hour of a deploy (UTC):

| Minute | What happens |
|---|---|
| :57 | CI's job `deploy pointer` moves the branch `deploy` to the next commit of `main` with something to deploy, if the site shows the version of the current pointer and the commit's last completed e2e run passed |
| :10 | the window opens: Flux applies the pointer, and the chain of releases starts: `platform`, `reporting`, `ledger`, `gateway`, `generator`, `control` |
| :20 | the window closes; a chain that has started runs to its end |
| :55 | a chain still running is an alert |

A chain replaces one service at a time and only the services whose image changed; control restarts every time, as it
shows the version. A changed service is checked 2 minutes after its new pods are ready: no transfer over 3 s, no `503`
and no pool timeout while it was replaced and since, or it is rolled back and the chain stops. The `503`s of a
stopping gateway are counted by the generator's answers: a generator Prometheus does not read leaves the check
`not checked`, and the service is rolled back. On k3d a chain with all five services checked took 13 to 19 minutes,
with two about 7, with none 2 to 3.

### Installing Flux

```bash
infra/flux/install.sh https://github.com/<owner>/<repository>   # safe to run again; WINDOW=off leaves out the window
```

It installs Flux 2.10 (the chart `fluxcd-community/flux2` 2.20.0, four controllers) into `flux-system`, the source and
the Kustomization `deploy` that read the branch every minute, the Provider and Alert that send the releases' events to
Alertmanager, the rights of Prometheus and kube-state-metrics to read Flux's namespace, and the two CronJobs of the
window. It needs `kubectl` and `helm` pointed at the cluster.

- **A first install can run out of time** (5 minutes): on a slow network the controllers' images took 3 to 4 minutes
  each. Run it again, or set `FLUX_INSTALL_TIMEOUT=15m`; the bank's pods are not touched.
- **Right after an install the window is open**, whatever the minute: the Kustomization is suspended first by the
  closing job at :20. A pointer that is already there is applied at once.
- **Flux's own pods are outside the memory alert**: their limits are in `infra/flux/flux-values.yaml`, not in the chart.

### Values and secrets

A release takes the chart's `values.yaml` at the pointer's commit, then the Secret `ledger-lab-values` (key
`values.yaml`) of the bank's namespace, then its own component, version and image tag from the pointer. The pointer CI
writes names no other values file, so on the server the Secret holds everything set over the chart's defaults: what
`infra/server/values.yaml` sets, `ingress.host`, `passwords`, `keys`, `invites` and `alertmanager.mail`.

```bash
kubectl -n ledger-lab create secret generic ledger-lab-values --from-file=values.yaml=<file outside the repository> \
  --dry-run=client -o yaml | kubectl apply -f -
```

- **Create it before the first pointer.** Flux treats it as optional: without it the releases run the chart's
  placeholders, `change-me` passwords and local image names.
- **Mail:** `alertmanager.mail.to`, `from` and `smarthost` (host:port); the login defaults to the Mailjet keys of the
  invites. With an empty `to` nothing is mailed, and the alerts show on Alertmanager's own page.
- **A changed Secret reaches a release at its next reconciliation**: with the next pointer, within the hour, or at
  once when forced (below). What it changes is not checked by the release check.
- **A changed key** (`keys`) restarts control and Alertmanager. **A changed database password restarts no pod**, and a
  service's role in Postgres also needs `ALTER ROLE` by hand.

### Moving the pointer by hand

The pointer is `release.yaml`, the only file of the branch `deploy`. The operator writes it for the first pointer, after
a rollback (CI waits, as the site does not show the pointer's version) and to go back. From a checkout of the commit to
deploy, as a commit's own scripts write its pointer:

```bash
git fetch origin --tags
version=$(curl -s https://<domain>/api/site | jq -r .version)
infra/flux/deployed-release.sh "$version" origin/deploy > <deployed.yaml>   # the pointer the cluster runs in full
PREVIOUS_RELEASE=<deployed.yaml> infra/flux/release.sh <commit> https://github.com/<owner>/<repository> > <release.yaml>
```

Then commit the file as `release.yaml` on top of `deploy` and push, without force.

- **`PREVIOUS_RELEASE` decides which services are checked**: those whose tag differs from that file's, all five when
  the chart differs. Without it, as for the first pointer, no service is checked. `deployed-release.sh` exits 1 when no
  pointer holds the site's version.
- **Forward** after a rollback: to the commit with the fix. The pointer's commit has to be a first-parent commit of
  `main`, or CI's job fails and waits for a hand. Skipping commits is possible, and breaks a change of contract made as
  two commits when it skips the first.
- **Back:** the pointer to the commit before. CI does not deploy a commit again that the pointer was at before, so it
  stays back until the operator moves it forward, to that commit or a later one.
- **A pointer pushed outside the window waits** for minute 10.
- **CI's job** (`.github/workflows/deploy-pointer.yml`) runs only when the repository variable `DEPLOY_SITE_URL` is
  set. It can be started by hand, but not while a chain runs: control shows the new version 2 minutes before its own
  check ends, and the job would take the chain for done. So would a scheduled run that GitHub starts late, into the
  next hour's chain.
- **Every completed e2e run of a commit counts**, pushed, nightly or by hand: a red nightly run after a green one holds
  the queue until a rerun is green.

### Holding deploys

```bash
kubectl -n flux-system patch kustomization deploy --type merge -p '{"spec":{"suspend":true}}'
kubectl -n flux-system patch cronjob deploy-window-open -p '{"spec":{"suspend":true}}'   # or minute 10 resumes it
```

A chain that has started is not stopped by this. The same two with `false` let deploys go on.

### Mails

| Subject | When |
|---|---|
| `deploy <commit>: OK` | control's release, the last of the chain, is through |
| `deploy <commit>: ROLLED BACK at <service>` | the service was not ready within 10 minutes or failed its check, and is back on its last revision |
| `deploy <commit>: FAILED at <service>` | the same, with no rollback reported within 2 minutes; `ROLLED BACK` follows as a second mail when it ends |
| `deploy <commit>: ROLLBACK FAILED at <service>` | the rollback itself failed |
| `deploy: ROLLED BACK at <service> (rollback to <commit>)` | a rollback whose failure Alertmanager no longer holds (the failure is older than 15 minutes, or Alertmanager restarted); the commit is the one gone back to |
| `NEEDS A HAND NOW: DeployStalled at <service>` | the release is stalled and was not rolled back; sent with the highest importance, the two ways on in its text |
| `alert: <name> (N)` | N alerts of that name hold at once, as for several containers |
| `alert: DeployRunsLate` | a chain still runs at minute 55 |
| `alert: DeployWindowJobFailed` | a job of the window failed: the window may be left open or closed |
| `alert: NodeMemoryLow` | the node has less than 300 MiB available |
| `alert: ContainerMemoryNearLimit` | a container of the chart is above 90 % of its memory limit |
| `alert: DeployMetricMissing`, `alert: DeployStateMissing` | Prometheus lacks the metric a deploy alert reads, so that alert could not fire |
| `alert: CertificateExpiresSoon` | the certificate expires within 14 days |

A failed check's reason is not in the mail; the mail names the command, and the Job stays until the service's next
check:

```bash
kubectl -n ledger-lab logs job/release-check-<service>   # e.g. gateway: guarantee broken in the last 240 s: >3s 1946
```

### When a deploy stops

- **`ROLLED BACK at <service>`.** The service runs its old version, the releases before it the new one, those after it
  were not started. Read the reason, then move the pointer by hand: forward to a fix, or back.
- **`NEEDS A HAND NOW`: stalled.** Seen on k3d when the node restarted in the middle of an upgrade: the service was left
  on its new version with a failed check and nothing to roll back to (`MissingRollbackTarget`). Flux does nothing more.
  Forward, the pointer's version once more, with its check:

  ```bash
  at=$(date +%s); kubectl -n ledger-lab annotate --overwrite helmrelease <service> \
    reconcile.fluxcd.io/requestedAt=$at reconcile.fluxcd.io/forceAt=$at
  ```

  Back: the pointer to the commit before; on k3d the service was ready on its old image 1 min 50 s later and the
  release was no longer stalled. Do not use `helm rollback`: helm-controller owns the release's fields and refuses it,
  and the pod stays on the new image.
- **Flux's own pod dies in the middle of an upgrade.** Nothing to do: on k3d the new helm-controller pod repeated the
  upgrade 45 s later and the chain went through.
- **Start a deploy only on a steady bank.** A check right after a start of the cluster counts what a bank that has
  just started still does slowly: on k3d it rolled back the first checked release (`>3s 8582`).
- **A change made by hand in the cluster.** A field the chart sets is put back at the release's next reconciliation,
  within the hour, or at once with the `requestedAt` annotation alone (on k3d 10 s after it). A field the chart does
  not set, as `kubectl set env` adds one, is left.

### Memory

Limits in MiB while the gateway is replaced, the moment with the most:

| | MiB |
|---|---|
| The chart's pods: the five services 5,058 (two ledger pods), Kafka 1,268, Postgres 912, Prometheus 512, Alertmanager 64, node-exporter, postgres-exporter and kube-state-metrics 64 each | 8,006 |
| The second gateway pod | 1,618 |
| Flux's four controllers: 96, 68, 64, 56 | 284 |
| Traefik 256, cert-manager's three pods 320 | 576 |
| CoreDNS, k3s's own limit | 170 |
| The gateway's release check, whose Job can start while the old pod still takes its last 40 s | 64 |
| k3s 772 and containerd 204, measured, not limits | 976 |
| Together, against the node's 11,406 | 11,694 |

So the limits exceed the node by 288 MiB for the seconds of the gateway's swap, before the OS's own processes. With
neither a second pod nor a check 1,394 MiB are left, and 250 while the generator is replaced and checked. The window's
jobs (64 MiB) run for seconds. Measured use is far below the limits: Flux's controllers took 63, 44, 27
and 24 MiB after four chains, Alertmanager 13, kube-state-metrics 19. `NodeMemoryLow` and `ContainerMemoryNearLimit`
watch it; the first of them, or a kill by the kernel, means finding the memory or a larger server.

### Moving the server to Flux

Once, by hand, in three steps; rehearsed on k3d from `v1.2.1` at 400 TPS, where every pod kept its uid through steps 2
and 3 and the hour of the move ended `BALANCED`. Whether the pods are kept on the server too is to be checked there:
its step 1 goes through `deploy.sh`, the rehearsal's went through `up.sh`.

1. **The last deploy as one release, with the ledger leaving Kafka's static membership.** The ledger up to `v1.2.1`
   joins its group as a static member: a pod that stops holds its partitions until its session times out. Start a pod
   of the new ledger image beside the bank, with a memory limit, and run the tool in it with its input kept open:

   ```bash
   kubectl -n ledger-lab run ledger-transition --restart=Never --image=<new ledger image> --overrides='{"spec": {
     "automountServiceAccountToken": false, "containers": [{"name": "ledger-transition", "image": "<new ledger image>",
     "command": ["sleep", "infinity"],
     "resources": {"requests": {"memory": "320Mi"}, "limits": {"memory": "320Mi"}}}]}}'
   kubectl -n ledger-lab exec -i ledger-transition -- java -cp '/app/BOOT-INF/classes:/app/BOOT-INF/lib/*' \
     com.ledgerlab.ledger.kafka.StaticMemberRemoval kafka:9092 ledger
   ```

   Its first line names the static members it sees, `static members: [...]`, six in the rehearsal. Then upgrade the
   release `ledger-lab` to the new version (`deploy.sh`) and write `ledger-1-` and `ledger-0-` to the tool's input,
   each as soon as that old pod is gone. It answers `ledger-1-: removed [...]` per line. It always exits 0: a line with
   `failed` means the members are still there, and the prefix is sent again. Delete the pod afterwards. In the
   rehearsal the upgrade took 34 s, each prefix removed 3 members, and of 31,908 transfers in the 4 minutes around it
   106 took over 3 s, none was refused. Every changed service restarts at once in this step, for the last time.

   To keep the pods in step 3, the upgrade sets what the pointer will set: each service's image tag,
   `--set services.<service>.tag=$(infra/ci/module-tag.sh <module> <commit>)`, and the version,
   `--set version=$(git describe --tags --match 'v[0-9]*' --long --always <commit>)`. `deploy.sh` with a release tag
   alone sets neither: a pod whose image name changes is replaced, and control is recreated when its version does.
2. **The objects go from the one release to the six**, without `helm uninstall`, which would delete the bank. Every
   object of a component, hooks left out, gets that release's name; then the old release's history is deleted:

   ```bash
   # per component of platform, reporting, ledger, gateway, generator, control; each object as <kind>/<name>
   helm template <component> infra/helm/ledger-lab -n ledger-lab --set component=<component> <the cluster's values>
   kubectl -n ledger-lab annotate --overwrite <kind>/<name> \
     meta.helm.sh/release-name=<component> meta.helm.sh/release-namespace=ledger-lab
   # then, once
   kubectl -n ledger-lab delete secret -l owner=helm,name=ledger-lab
   ```

   The rehearsal moved 32 objects this way.

3. **Flux takes them.** Create the Secret `ledger-lab-values` with exactly the values the release ran with, install
   Flux, and push the first pointer, at the commit the cluster runs and without `PREVIOUS_RELEASE`. In the rehearsal
   the six releases were installed over the running objects in about 2.5 minutes. Then set `DEPLOY_SITE_URL`, and the
   next merge is deployed by CI and Flux. From here on `deploy.sh` is not used: Helm refuses its one release on
   objects that belong to the six (invalid ownership metadata). cert-manager stays the release it installed.

Still to check on the server, as k3d cannot: `NodeMemoryLow` (a k3d node reports no available memory),
`DeployWindowJobFailed` on a real failed job, kube-state-metrics' memory with the server's resource definitions, and
whether an ordinary rollout leaves the clients without a connection or an answer, which the release check does not
count. `StaticMemberRemoval` is deleted from the ledger after the move.

### In k3d

```bash
infra/k3d/flux.sh [<commit>] [<values file in the repository> ...]   # HEAD by default; WAIT=1 waits for the chain
infra/e2e/install-base.sh <base commit>                              # the end-to-end test's bank at a commit
infra/e2e/scenario.py run --base <base commit>                       # deploys HEAD over it, crashes, checks verdicts
```

- **A cluster is deployed either by `up.sh`** (one release, the working tree) **or by `flux.sh`** (six releases, a
  commit); each refuses a cluster of the other. The window is off unless `WINDOW=on`.
- **Flux reads a bare repository** under `build/flux-git`, served to the cluster by `infra/k3d/git-http.py`; its PID is
  in `build/flux-git/server.pid`, and it is stopped by hand. A node restarted with `docker restart` loses the host's
  name in the cluster's DNS and Flux its source; `k3d cluster stop` and `start` bring it back.
- **Mails** go to a mailbox in the cluster: `kubectl -n ledger-lab apply -f infra/k3d/mail-sink.yaml`, then
  `flux.sh <commit> infra/k3d/mail-values.yaml`; adding `infra/k3d/alerts-now-values.yaml` makes every alert fire at
  once. The mailbox shows them on its port 8025.
- **The end-to-end test** waits until the bank is steady (about 3 minutes), moves the pointer, follows the chain, kills
  `ledger-0` and the gateway's pod and waits for the verdicts of the periods it crossed (5-minute periods, up to
  17 minutes). In CI its job has 60 minutes and its waits give up after 52, so the report is still printed.
- **A base from before control's load gauge** (commit `aa28fd8`) is rolled back at the first checked service: the check
  asks the base's control for a metric it does not publish. Only commits of the branch that brought Flux are that
  old; a parent on `main` from before Flux is not installed as a base at all.

## Invites

The page is opened with an invite link, `/?ref=<token>`. A visitor gets one by entering an email on the
start page ([ADR 0017](../adr/0017-invites-by-email.md)); the address is kept with the invite and its visits, in `control.invite_emails`, for 30 days after its last use, or until 24 hours after its owner asked to
delete it by the link in any mail (`/delete?ref=<token>`).

### Mailed invites

`deploy.sh` refuses to deploy until `~/.config/ledger-lab/server.env` (`SERVER`, `DOMAIN`, outside the
repository) also has:

```sh
TURNSTILE_SITE_KEY=<site key>       # Cloudflare Turnstile, a widget for the domain
TURNSTILE_SECRET=<secret key>
MAILJET_API_KEY=<API key>           # Mailjet, the sender domain validated
MAILJET_API_SECRET=<secret key>
OPERATOR_NAME='<name>'              # the imprint and the privacy page's contact
OPERATOR_ADDRESS='<postal address>'
OPERATOR_EMAIL=<contact address>
```

- **Mailjet:** add the domain under Senders & Domains, then put its DKIM value and ownership record into
  `infra/terraform/terraform.tfvars` (see `terraform.tfvars.example`) and run `tf.sh apply`. Each mail
  switches link and open tracking off, so the link goes to the site, not through a tracker. Mail goes from
  `no-reply@<domain>`.
- **Without the keys**, as in k3d: no Turnstile check, and each mail is logged as not sent; the link is
  read from `control.invite_mails`.
- **Limits:** 3 links an address in 24 hours, then one notice, then nothing; 50 mails a day in all.
  Addresses unused for 30 days are deleted; copies of them outside the database would outlive that.

### By hand

Invites for a person are still created by hand in the database; there is no endpoint for them:

```sql
INSERT INTO control.invite_tokens (token, expires_at)
VALUES ('<random token>', now() + interval '30 days');
```

## Working on the page

The page is React and TypeScript in `control/page`; its types are generated from `control/openapi.json`.
Gradle downloads Node, runs the page's tests with control's and puts the built page into control's jar,
so `infra/k3d/up.sh` deploys it like the rest. For work on the page alone, with control on
`localhost:8083`:

```bash
cd control/page && npm ci && npm run types && npm run dev   # Vite on localhost:5173, /api proxied to control
```


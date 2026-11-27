# ADR 0024: Deploys without a maintenance window

Status: accepted · Date: 2026-11-27 · Partly supersedes [ADR 0020](0020-gateway-deploys-without-downtime.md), [ADR 0021](0021-one-bank-around-the-clock.md)

## Context

The gateway already deploys without downtime ([ADR 0020](0020-gateway-deploys-without-downtime.md)). Everything else
was one Helm release, chosen and deployed by hand with a tag: every changed service restarted at once, and the other
services stopped before their new version started. On a bank that runs around the clock
([ADR 0021](0021-one-bank-around-the-clock.md)) a deploy is the most frequent planned disturbance, and its minutes
count against the error budget unless a maintenance window was declared 3 hours ahead.

The goal: a merge reaches the server by itself, and the bank's clients do not notice. While a service is replaced no
transfer takes longer than 3 s to its result, no request is refused with 503 and the gateway's connection pool never
times out; a deploy that breaks this undoes itself and says so. The limits: one server with 12 GB, so no second copy of
the bank; the cluster's API is reached only through an SSH tunnel, so nothing outside can push to it; and the Kafka
messages between the services have no schema registry, so their compatibility rests on the order of the deploys.

## Decision

**Flux pulls a pointer; nothing pushes to the cluster.** Flux runs in the cluster with four controllers (source,
kustomize, helm, notification) and follows the branch `deploy` of the public repository. The branch holds one file,
`release.yaml`, written by `infra/flux/release.sh`: the commit of `main` to run and six Helm releases of the one chart
at that commit. GitHub holds no key of the cluster and the cluster no token of GitHub. Passwords and keys reach the
releases from a Secret in the cluster, never from git.

**Six releases in a chain, one service at a time.** `platform` (Kafka, Postgres, monitoring, what the services share),
then `reporting`, `ledger`, `gateway`, `generator`, `control`; each depends on the one before, and the chart renders
only the objects of its release. A release starts once the one before is ready on the new revision and has passed its
check: on k3d, with a commit that changed only the ledger, the gateway's release started 34 s after the ledger's second
new pod was ready, not before. So at most one extra pod exists at any moment, and a failure names its service.

- **Only what changed.** A service's image tag is a hash of what its image is built from (its module, `common` for
  the bank's services, the Dockerfile, the build files). A service whose tag stays keeps its pods; the end-to-end test
  checks that by their uids. Control restarts on every deploy, as it shows the version, and it is the last of the
  chain: the version on the page is how the outside learns that a chain is through.
- **How each is replaced.** The gateway, reporting and the generator start the new pod beside the old one, which stops
  once the new one is ready. Two generators would double the load, so one holds a sender lease in Postgres (5 s,
  renewed every second) and the new pod stays silent until the old one lets go. Control stops first: it is outside the
  clients' path. The ledger's two pods are replaced one after the other. The ledger joins its Kafka group dynamically
  under the cooperative assignor: a stopping pod leaves the group and only its partitions move, in about a second
  (measured on k3d), and a new pod reports ready only once it holds partitions.

**Every commit of `main`, in order, one an hour.** A CI job at minute 57 of every hour moves the pointer to the next
first-parent commit of `main` that has something to deploy (`infra/ci/move-pointer.sh`), and only if the site reports
the version of the current pointer, so its whole chain is through, and the commit's last completed end-to-end run
passed. Flux by itself would run the newest commit. Each commit is deployed over the one before it because a change
of a contract between the services is two commits, the reader first, then the writer; skipping the first would run a
writer no reader understands. The end-to-end test makes exactly that step: it installs the commit's parent in k3d
through Flux, deploys the commit over it under load and then crashes a ledger pod and the gateway. A commit the pointer
was at before is not deployed again, so a pointer the operator moved back stays back. The job writes nothing but
the branch `deploy`; `main` takes pull requests only.

**A deploy starts between minute 10 and 20 of the hour (UTC).** Outside that window the Kustomization that applies the
pointer is suspended; a chain that has started is not held by it and runs to its end. Two CronJobs open and close the
window. Opening is a patch that removes the pause and returns, as `flux resume` waits for the reconciliation and its
retry once opened a window that had closed meanwhile; closing is `flux suspend`. Each job reads back the state it was
to leave and fails otherwise, and their deadlines (300 s to start, 240 s to end) stay below the window's 10 minutes,
so a late job never acts after the job of the other kind. Their account may get and patch that one Kustomization and
list the Kustomizations of Flux's namespace. This gives the hour a fixed order: the pointer at :57, the start at :10,
an alert when a chain still runs at :55.

**The guarantee is checked per service; a failure rolls that service back.** Flux runs the release's test once its
new pods are ready: a Job that waits 2 minutes, so the new version is judged under load, and then asks Prometheus about
those 2 minutes and the time set for replacing the service's pods before them (90 or 120 s). Exactly 0 transfers over
3 s, 0 refusals (the gateway's `503 busy`, and the `503` of a stopping gateway as its clients counted it) and 0 pool
timeouts keep the guarantee. The check only asks Prometheus and prints one line; no answer or an empty one is "not
checked" and fails.

- Only a service whose tag differs from the last pointer deployed in full is checked, and every service when the chart
  differs from that pointer's. A release without a check does not wait.
- The check leaves out what the service levels leave out ([SLO guide](../guides/slo.md)): a crash from the page until
  60 s after it, and a load raised above 1300 transfers per second until 30 s after its end. Such a check passes as
  "not checked". One difference: the service levels judge such a load after all if an instance restarted during it;
  the check does not look at restarts, since a rollout restarts instances itself.
- A release that is not ready within 10 minutes or fails its test is rolled back to its last revision by Flux and
  stays failed, so the releases after it do not start. On k3d a crash of both ledger pods during the gateway's check
  gave `gateway: guarantee broken in the last 240 s: >3s 1946`: the gateway went back, the ledger kept its new
  version, the generator and control were not touched. The site then reports the old version, so the queue of commits
  waits until the operator moves the pointer.
- Flux also restores a field of the chart that was changed by hand in the cluster.

**The operator learns the outcome by mail.** Flux's events go to Alertmanager (one pod), which mails the ends of a
deploy: `deploy <commit>: OK` after control's release, `FAILED at <service>`, `ROLLED BACK at <service>`. Prometheus
alerts go the same way: a chain still running at :55, the node's available memory below 300 MiB, a container above
90 % of its memory limit, a certificate about to expire.

**A release that is stalled waits for a hand.** A node restarted in the middle of an upgrade left the gateway on its
new version with a failed check and nothing to roll back to (`Stalled`, `MissingRollbackTarget`); Flux then does
nothing more. No automatic way out is built: the operator decides, forward (one forced reconciliation) or back (the
pointer), and `helm rollback` by hand is not a way, as helm-controller owns the release's fields and refuses it.
kube-state-metrics publishes the releases' conditions, and a stalled release that was not rolled back is mailed as
`NEEDS A HAND NOW` with the highest importance and both commands in its text.

**Memory.** Each Flux controller is limited to 1.25 × its peak over seven chains in five hours on k3d: 96, 68, 64 and
56 MiB. Alertmanager and kube-state-metrics get 64 MiB each (13 and 19 MiB measured). The generator was cut from 1280
to 1080 MiB to make room.

## Known trade-offs

- **The memory limits exceed the server while the gateway is replaced.** With the second gateway pod the limits of
  all pods, plus k3s and containerd as measured, come to about 0.2 GB above the node's memory, before the OS's own
  processes ([the sum](../guides/operations.md#memory)). Limits are ceilings, not use, so this is watched instead of
  fixed: the alerts on the node's available memory and on a container near its limit. The first such alert or a kill
  by the kernel means finding the memory or a larger server.
- **The check counts refusals, not every lost answer.** A request the client could not connect or got no answer to
  counts against availability but not in the check. On k3d over 6 hours and about 8 replacements of the gateway such
  answers came only in minutes with a node restart or a crash by the test. To revisit when the server is moved:
  whether an ordinary rollout there produces them.
- **The platform has no check and no rollback by one.** What a change of it does to the transfers shows in the check
  of reporting, the next release. Replacing Kafka or Postgres still stops the bank; this decision does not cover it.
- **The first seconds of a new pod.** A counter that a new pod first reports above zero is not seen by `increase()`,
  so a refusal before the pod's first scrape, a second, is missed.
- **A new metric of the check comes one commit before the check reads it.** The check asks the control that is still
  running; a metric it does not publish yet is an empty answer, and the first checked service is rolled back.
- **What the cluster's Secret changes is not checked**, and a changed database password restarts no pod.
- **Drift is corrected at the release's interval of an hour**, and a field added by hand that the chart does not set
  is not removed.
- **The version on the page is ahead of control's own check** by its 2 minutes. The hourly job runs long after; a job
  started by hand in the middle of a chain can take the chain for done.
- **One node, no canary.** A new version takes all the traffic at once and is judged afterwards.

## Considered

- **An orchestrator of our own.** Built first: a script that released one service per step and a poller on the server.
  Its reviews found about 124 defects, most in shell paths taken rarely (a stop mid-step, a resume). Dropped for the
  standard tool; the queries of its check and the end-to-end checks of money were kept.
- **Argo CD with Argo Rollouts.** Measured on k3d: 348 MiB in 8 pods and 0.4–0.5 GiB more in k3s itself, against
  127 MiB in 5 pods and 63 MiB more for Flux. The server has no room for it.
- **Flagger.** Without a service mesh or an ingress in front of the gateway its canary gets no share of the traffic,
  and it runs up to three gateway pods (its documentation). A thin check under `helm test` took its place; the
  queries are knowledge of the bank in any tool.
- **One Helm release under Flux.** Every changed JVM starts at once, and a rollback takes them all back.
- **Always the newest commit**, as Flux and Argo CD do by default: it skips commits, which the two-commit change of a
  contract does not survive without a schema registry.
- **Kargo for the queue of commits.** Not measured; the queue is one CI script with tests. To revisit if that script
  starts to fail.
- **Blue/green.** Twice the bank's memory.
- **Pushing from CI**, with an SSH key in GitHub Actions or a self-hosted runner on the server: a key to the server
  kept outside it, or a runner's memory and jobs on the bank's one node. Flux pulls and needs neither.
- **GitHub as Flux's source in the end-to-end test.** A pull request's merge commit is on no branch there, and the
  test must not write a pointer to GitHub. Flux reads a bare repository on the runner instead, served over HTTP by a
  small wrapper around `git http-backend` (`infra/k3d/git-http.py`), as Flux takes no `git://` URL.
- **Delaying the failure's mail instead of kube-state-metrics.** helm-controller sends no event and has no metric for
  a stalled release; a delay would still not tell stalled from slow.
- **Kafka's CLI for the move away from static membership.** Kafka 4.2.1 has no command that removes static members;
  a small class in the ledger's image does it once and is to be deleted after the move
  ([operations](../guides/operations.md#moving-the-server-to-flux)).

## Consequences

- A merge needs no hand: CI builds the images that are missing, the end-to-end test deploys the commit over its
  parent, the pointer moves within the hour, the chain runs in the next window and the mail says how it ended. Several
  merges are deployed one an hour.
- A change of a contract between the services is made as two commits, each compatible with the one before.
- A chain with every service checked took 13 to 19 minutes on k3d, with two about 7, with none 2 to 3.
- The server is moved to Flux once, by hand; the rehearsal on k3d kept every pod (same uids) and the hour of the move
  ended `BALANCED`. Until then it is deployed with `deploy.sh` as before.
- Of [ADR 0020](0020-gateway-deploys-without-downtime.md), "the other services still stop on a deploy" no longer
  holds; of [ADR 0021](0021-one-bank-around-the-clock.md), that they stop briefly and that a deploy is by hand.
- What production would add: several nodes, a canary with a share of the traffic, and a schema registry in place of
  the order of commits.

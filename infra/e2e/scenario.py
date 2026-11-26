#!/usr/bin/env python3
"""The end-to-end test against a bank that Flux deploys in k3d (infra/e2e/install-base.sh <commit>).

Under the bank's steady load: a commit deployed over the bank as an earlier commit left it (--base, the step the server
makes from one commit of main to the next) by moving the deploy pointer (infra/k3d/flux.sh), once the bank has kept its
guarantee for as long as a release check looks back. The chain of releases must reach the commit one release after
another, the release check must be given to the services that changed and pass, the pods of those services and of
control must be new and, with the chart unchanged, every other pod must be as it was (by uid and restarts). Without
--base the commit is the one installed, and only the order of its chain is checked.
Then ledger-0 and the gateway's pod are killed: after the chain, as a release check counts a pod killed in its window
as a broken guarantee.
Then every period the scenario crossed must be reconciled BALANCED under load (a share of the generator's steady speed
accepted); the clients' journal and the transfers they got no answer for must count what the gateway accepted, and the
next period's reconciliation must resolve exactly those as accepted; reporting must agree with the books, the money on
the accounts must be what it was, and the page must answer with the commit's version.

Usage: infra/e2e/scenario.py run|steady|collect [--base <commit>] [--commit <commit, default HEAD>]
         [--cluster ledger-lab] [--namespace ledger-lab] [--control-url http://localhost:8083] [--artifacts <dir>]
       infra/e2e/scenario.py mode --event <GitHub event> --changed <exit of changed.sh> --base-flux yes|no
  run      the scenario; one PASS or FAIL line per check, exit 1 if any failed (the cluster's state then goes to
           --artifacts)
  steady   waits until the bank has kept its guarantee under its steady load for a release check's window
  mode     how CI tests a commit against its parent: skip, transition or install
  collect  the cluster's state into --artifacts: logs of every pod (and its previous container), get, describe, events,
           Flux's objects, control's periods
E2E_MUTATE_SUM=1 expects the money on the accounts one cent higher at the end, so a run must fail.
"""

import argparse
import json
import math
import os
import re
import subprocess
import sys
import tempfile
import time
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
import urllib.error
import urllib.request

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
FLUX = ROOT / "infra" / "k3d" / "flux.sh"
# As infra/flux/release.sh names the chart, the chain and the releases with a service's image.
CHART = "infra/helm/ledger-lab"
CHAIN = ("platform", "reporting", "ledger", "gateway", "generator", "control")
SERVICES = ("ledger", "gateway", "generator", "control", "reporting")
CONTROL = "control"
GATEWAY = "gateway"
LEDGER = "ledger"
CRASHED_LEDGER_POD = "ledger-0"
NAME_LABEL = "app.kubernetes.io/name"

# The chart's release check: the scenario asks Prometheus its questions before it moves the pointer.
sys.path.insert(0, str(ROOT / CHART / "files"))
import release_check  # noqa: E402

# As the chart names them (templates/postgres.yaml).
POSTGRES_POD = "postgres-0"
POSTGRES_ADMIN = "admin"
POSTGRES_DATABASE = "ledgerlab"
# The ledger's own books (AccountRepository): every balance plus the credits not yet applied, and the opening total,
# read in one snapshot.
MONEY_SQL = ("SELECT coalesce(sum(balance_cents), 0) + (SELECT coalesce(sum(amount_cents), 0) FROM ledger.deferred_credits),"
             " coalesce(sum(opening_balance_cents), 0) FROM ledger.accounts")
MUTATE_ENV = "E2E_MUTATE_SUM"

# Prometheus as the chart's helper ledger-lab.prometheusUrl names it, reached through the API server.
PROMETHEUS_SERVICE = "prometheus:9090"
ACCEPTED_QUERY = 'sum(increase(gateway_transfers_total{{outcome="accepted"}}[{r}s]))'
# The bank is at its steady speed when it accepted nearly all the generator sends at it.
STEADY_LOAD_SHARE = 0.9
STEADY_TIMEOUT_SECONDS = 900

# Flux's HelmRelease: kind, conditions, the phase of a passed test hook, the chart version of a commit.
HELM_RELEASES = "helmreleases.helm.toolkit.fluxcd.io"
FLUX_NAMESPACE = "flux-system"
READY = "Ready"
STATUS_TRUE = "True"
# The conditions with which a release has stopped the chain: (type, status).
STOPPED = (("Stalled", "True"), ("Remediated", "True"), ("TestSuccess", "False"))
HOOK_SUCCEEDED = "Succeeded"
REVISION_COMMIT_LENGTH = 12
# Twice the longest chain measured on k3d (19 minutes with all five release checks); a release that fails stops the
# wait at once.
CHAIN_TIMEOUT_SECONDS = 2400
ONLY_IMAGES_ENV = "ONLY_IMAGES"
IMAGES_SOURCE_ENV = "IMAGES_SOURCE"
IMAGES_ANY = "any"

# As control's API returns them (PeriodState, Verdict, PeriodNote).
FINAL_STATES = frozenset(("RECONCILED", "FAILED"))
STATE_RECONCILED = "RECONCILED"
VERDICT_BALANCED = "BALANCED"
DISCREPANCY_NOTES = frozenset(("REPORTING_UNAVAILABLE", "PROJECTION_BEHIND_BOOKS"))
PERIODS_LIMIT = 100
# A deploy that replaces control alone and the crash after it may fall into one period of 5 minutes.
MIN_PERIODS = 1
# The least share of the generator's steady speed a checked period must have accepted over its time inside the scenario:
# well below the speed, as a killed gateway and the deploy take traffic away, yet a bank without load fails.
MIN_LOAD_SHARE = 0.25

READY_TIMEOUT_SECONDS = 300
TERMINATING_TIMEOUT_SECONDS = 120
# A pod the deploy disturbed may still restart or be replaced after the chain has ended.
LATE_SNAPSHOT_SECONDS = 30
POLL_SECONDS = 5
VERDICT_POLL_SECONDS = 15
VERDICT_MARGIN_SECONDS = 60
HTTP_TIMEOUT_SECONDS = 10
SECONDS_PER_MINUTE = 60
DURATION_UNITS = {"ms": 0.001, "s": 1, "m": 60, "h": 3600, "d": 86400}
DURATION = re.compile(r"^(\d+)(ms|s|m|h|d)$")

MODE_SKIP = "skip"
MODE_TRANSITION = "transition"
MODE_INSTALL = "install"
# As infra/ci/changed.sh exits.
CHANGED_SOMETHING = 0
CHANGED_NOTHING = 3
PUSH_EVENT = "push"


class ScenarioError(Exception):
    pass


@dataclass
class Check:
    name: str
    ok: bool
    detail: str

    def line(self):
        return f"{'PASS' if self.ok else 'FAIL'} {self.name}: {self.detail}"


def say(text):
    print(f"{time.strftime('%H:%M:%S', time.gmtime())} {text}", flush=True)


def duration_seconds(text):
    """A duration as control's Spring properties read it: one number and one unit, or plain seconds."""
    text = str(text).strip()
    if text.isdigit():
        return int(text)
    match = DURATION.match(text)
    if not match:
        raise ValueError(f"not a duration with a single unit: {text}")
    return int(match.group(1)) * DURATION_UNITS[match.group(2)]


def crossed_starts(start, end, period_seconds):
    """The starts of the periods that share any time with [start, end); the bank cuts periods at multiples of their length."""
    first = int(start // period_seconds) * period_seconds
    return list(range(first, math.ceil(end), period_seconds))


def waited_starts(started, ended, period_seconds):
    """The periods the scenario crossed, and the one after: its reconciliation resolves the transfers the last one left
    unknown to the clients."""
    starts = crossed_starts(started, ended, period_seconds)
    return starts + [starts[-1] + period_seconds]


def verdicts_due(waited, period_seconds, verdict_deadline):
    return waited[-1] + period_seconds + verdict_deadline + VERDICT_MARGIN_SECONDS


def epoch(text):
    return datetime.fromisoformat(text.replace("Z", "+00:00")).timestamp()


def clock(seconds):
    return datetime.fromtimestamp(seconds, timezone.utc).strftime("%H:%M")


def pending_starts(periods, starts):
    final = {int(epoch(period["start"])) for period in periods if period.get("state") in FINAL_STATES}
    return [start for start in starts if start not in final]


def client_numbers(details):
    """The gateway's accepted count and the clients' view of it: journal, statement, unknown, breaks, resolved."""
    report = details.get("report") or {}
    client = report.get("client") or {}
    statement = client.get("statement") or {}
    breaks = {name: (value or {}).get("count") for name, value in (client.get("breaks") or {}).items()}
    return ((report.get("gateway") or {}).get("accepted"), client.get("acceptedByJournal"), statement.get("rows"),
            statement.get("unknownToClient"), breaks, (client.get("unknownResolved") or {}).get("accepted"))


def period_checks(details, following):
    """Four checks a period: its verdict, the clients' journal against the gateway, the transfers the clients got no
    answer for against what the next period's reconciliation resolved (it resolves the previous period's), and
    reporting against the books."""
    period, report = details["period"], details.get("report") or {}
    name = f"period {clock(epoch(period['start']))}-{clock(epoch(period['end']))}"
    verdict_ok = period.get("state") == STATE_RECONCILED and period.get("verdict") == VERDICT_BALANCED
    verdict = Check(f"{name} verdict", verdict_ok,
                    f"{period.get('state')} {period.get('verdict')}"
                    + (f", mismatches {report.get('mismatches')}" if report.get("mismatches") else "")
                    + (f", failure {period.get('failure')}" if period.get("failure") else ""))
    accepted, journal, rows, unknown, breaks, _ = client_numbers(details)
    broken = {name: count for name, count in breaks.items() if count != 0}
    totals_ok = (None not in (accepted, journal, unknown) and accepted == journal + unknown == rows
                 and bool(breaks) and not broken)
    totals = Check(f"{name} clients' totals = gateway's", totals_ok,
                   f"gateway accepted {accepted}, clients' journal {journal} + unknown to them {unknown}, "
                   f"statement rows {rows}, breaks {broken or ('none' if breaks else 'not reported')}")
    resolved = client_numbers(following)[5] if following else None
    resolution = Check(f"{name} unknown resolved by the next period", unknown is not None and resolved == unknown,
                       f"unknown {unknown}, resolved as accepted {resolved}")
    notes = report.get("notes")
    discrepancies = sorted(DISCREPANCY_NOTES & set(notes or []))
    reporting = Check(f"{name} reporting agrees with the books", notes is not None and not discrepancies,
                      f"notes {notes}" if notes is not None else "no report")
    return [verdict, totals, resolution, reporting]


def totals_line(all_details, following):
    """following: the period after the last one checked, whose reconciliation resolves that one's unknown transfers."""
    accepted = journal = resolved = 0
    for details in all_details:
        numbers = client_numbers(details)
        accepted += numbers[0] or 0
        journal += numbers[1] or 0
    for details in all_details[1:] + [following]:
        resolved += (client_numbers(details)[5] or 0) if details else 0
    return (f"over the scenario: gateway accepted {accepted}, clients' journal {journal} + resolved as accepted "
            f"{resolved} in {len(all_details)} periods")


def steady_tps(load):
    """The generator's lowest steady speed as the chart's values set it: the base, or with the day's profile on, the
    lowest the profile goes."""
    profile = load.get("profile") or {}
    if profile.get("enabled"):
        return min(profile["nightTps"], profile["dayMinTps"])
    return load["baseTps"]


def load_check(details, tps, started, ended):
    """The period accepted at least MIN_LOAD_SHARE of the steady speed over the time it shares with the scenario, and
    more than nothing: a bank without traffic balances too."""
    period = details["period"]
    start, end = epoch(period["start"]), epoch(period["end"])
    inside = max(0.0, min(end, ended) - max(start, started))
    least = max(1, math.floor(MIN_LOAD_SHARE * tps * inside))
    accepted = client_numbers(details)[0]
    return Check(f"period {clock(start)}-{clock(end)} under load", accepted is not None and accepted >= least,
                 f"gateway accepted {accepted}, at least {least} ({MIN_LOAD_SHARE:.0%} of {tps} TPS over "
                 f"{inside:.0f}s in the scenario)")


def periods_count_check(all_details):
    return Check("periods checked", len(all_details) >= MIN_PERIODS,
                 f"{len(all_details)}, at least {MIN_PERIODS}")


def money_check(before, after, opening, mutate):
    expected = before + 1 if mutate else before
    detail = (f"before {before} cents, after {after}, opening total {opening}"
              + (f", expected {expected} ({MUTATE_ENV})" if mutate else ""))
    return Check("money on the accounts unchanged", after == expected and after == opening, detail)


def check_window(values):
    """The longest time a release check of the chart looks back: the slowest service's rollout and the settling."""
    rollout = max(int(values["services"][service]["rolloutSeconds"]) for service in SERVICES)
    return rollout + int(values["releaseCheck"]["settleSeconds"])


def steady_state(broken, accepted, tps, window):
    """Whether a release check looking back over the window from now would pass, and the bank ran at its steady speed
    all that time. broken: the count behind each of the release check's guarantees; returns (steady, why)."""
    over = {name: count for name, count in broken.items() if count > 0}
    least = math.ceil(STEADY_LOAD_SHARE * tps * window)
    detail = (f"in the last {window}s the gateway accepted {accepted:.0f}, at least {least} "
              f"({STEADY_LOAD_SHARE:.0%} of {tps} TPS), guarantee " + (f"broken: {over}" if over else "kept"))
    return not over and accepted >= least, detail


def uid_check(before, after, what):
    """before, after: pod -> (uid, restarts of each container), from pods_except."""
    changed = sorted(name for name in before if after.get(name) != before[name])
    added = sorted(set(after) - set(before))
    ok = bool(before) and not changed and not added
    detail = (f"{len(before)} pods kept their uid and restarts" if ok
              else f"replaced or restarted {changed or '-'}, new {added or '-'}")
    return Check(what, ok, detail)


def choose_mode(event, changed, base_has_flux):
    """How a commit is tested against its parent: a push with nothing to deploy is skipped (the job stays green, and
    the deploy pointer passes such a commit without asking); a parent that Flux can deploy is installed and the commit
    deployed over it, control at least being replaced for its version; over a parent from before Flux there is no
    such step to test, so the commit itself is installed."""
    if changed not in (CHANGED_SOMETHING, CHANGED_NOTHING):
        raise ValueError(f"changed.sh failed: exit {changed}")
    if changed == CHANGED_NOTHING and event == PUSH_EVENT:
        return MODE_SKIP
    return MODE_TRANSITION if base_has_flux else MODE_INSTALL


def condition(release, kind):
    for each in (release.get("status") or {}).get("conditions") or []:
        if each.get("type") == kind:
            return each
    return None


def latest(release):
    """The newest revision in the release's history, as Flux lists it first."""
    history = (release.get("status") or {}).get("history") or []
    return history[0] if history else {}


def named(releases):
    return {release["metadata"]["name"]: release for release in releases}


def on_commit(release, commit):
    """Ready on the chart of the commit: Flux names a chart built from a commit <version>+<12 characters of it>.<n>, and
    a release with a test is ready once the test has passed."""
    state = condition(release, READY)
    mark = f"+{commit[:REVISION_COMMIT_LENGTH]}."
    return mark in latest(release).get("chartVersion", "") and state is not None and state.get("status") == STATUS_TRUE


def reached(releases, commit):
    """The releases of the chain that are ready on the commit, in the chain's order."""
    by_name = named(releases)
    return [name for name in CHAIN if name in by_name and on_commit(by_name[name], commit)]


def chain_failure(releases, since):
    """The first release of the chain that has stopped it since the pointer moved, with Flux's words for it; a
    condition from before that time is left from an earlier deploy."""
    by_name = named(releases)
    for name in CHAIN:
        for kind, status in STOPPED:
            found = condition(by_name.get(name, {}), kind)
            if found and found.get("status") == status and epoch(found["lastTransitionTime"]) >= since:
                return f"{name}: {kind} {found.get('status')}, {found.get('reason')}: {found.get('message')}"
    return None


def order_check(releases):
    """Each release of the chain was deployed no earlier than the one before it became ready, its test included: two
    services are never replaced at the same time."""
    by_name = named(releases)
    early, times = [], []
    for before, after in zip(CHAIN, CHAIN[1:]):
        state = condition(by_name.get(before, {}), READY)
        deployed = latest(by_name.get(after, {})).get("lastDeployed")
        if state is None or not deployed:
            early.append(f"{after}: no time of its deploy or of {before} becoming ready")
            continue
        times.append(f"{before} ready {state['lastTransitionTime']}, {after} deployed {deployed}")
        if epoch(deployed) < epoch(state["lastTransitionTime"]):
            early.append(f"{after} deployed {deployed}, before {before} was ready {state['lastTransitionTime']}")
    return Check("each release deployed after the one before it was ready", not early, "; ".join(early or times))


def tags_of(releases):
    """service -> the image tag its release sets."""
    by_name = named(releases)
    tags = {}
    for service in SERVICES:
        values = (by_name.get(service, {}).get("spec") or {}).get("values") or {}
        tags[service] = ((values.get("services") or {}).get(service) or {}).get("tag")
    return tags


def updated_services(before, after):
    return {service for service in SERVICES if before.get(service) != after.get(service)}


def expected_checked(updated, chart_differs):
    """The services infra/flux/release.sh gives the release check: those whose image tag changed, or every one when the
    chart changed, as it can replace any pod."""
    return set(SERVICES) if chart_differs else set(updated)


def release_check_checks(releases, expected):
    """The release check is turned on for the expected services and for no other, and each one's test hook passed."""
    by_name = named(releases)
    assigned = {name for name in SERVICES
                if (((by_name.get(name, {}).get("spec") or {}).get("values") or {}).get("releaseCheck") or {}).get("enabled")}
    failed = {}
    for name in sorted(assigned):
        hooks = latest(by_name[name]).get("testHooks") or {}
        phases = {hook: (state or {}).get("phase") for hook, state in hooks.items()}
        if not phases or any(phase != HOOK_SUCCEEDED for phase in phases.values()):
            failed[name] = phases or "no test ran"
    return [Check("release check given to the services that changed", assigned == expected,
                  f"given to {sorted(assigned) or 'none'}, changed {sorted(expected) or 'none'}"),
            Check("release check passed", not failed,
                  f"failed {failed}" if failed else f"passed for {sorted(assigned) or 'none (none given)'}")]


def service_uids(pods, service):
    return {pod["metadata"]["uid"] for pod in live(pods) if pod["metadata"].get("labels", {}).get(NAME_LABEL) == service}


def replaced_check(before, after, services):
    """Every pod of the services the deploy had to replace is a new one."""
    kept = {}
    for service in sorted(services):
        old, new = service_uids(before, service), service_uids(after, service)
        if not new or new & old:
            kept[service] = sorted(new & old) or "no pods"
    return Check("the deploy replaced the pods of the services it changed", bool(services) and not kept,
                 f"not replaced {kept}" if kept else f"new pods of {sorted(services)}")


def back(pods, killed):
    """ledger-0 and a gateway pod ready again, neither of them one that was killed."""
    now = [pod for pod in live(pods) if ready(pod) and pod["metadata"]["uid"] not in killed]
    return (any(pod["metadata"]["name"] == CRASHED_LEDGER_POD for pod in now)
            and any(pod["metadata"].get("labels", {}).get(NAME_LABEL) == GATEWAY for pod in now))


def site_check(status, body, expected):
    try:
        version = json.loads(body).get("version") if status == 200 else None
    except (ValueError, AttributeError):
        version = None
    return Check("/api/site", status == 200 and version is not None and version == expected,
                 f"status {status}, version {version}, expected {expected}")


def ready(pod):
    return any(each["type"] == READY and each["status"] == STATUS_TRUE
               for each in pod.get("status", {}).get("conditions", []))


def live(pods):
    return [pod for pod in pods if not pod["metadata"].get("deletionTimestamp")]


def pods_except(pods, services):
    return {pod["metadata"]["name"]: (pod["metadata"]["uid"], tuple(sorted(
                (container["name"], container.get("restartCount", 0))
                for container in pod.get("status", {}).get("containerStatuses", []))))
            for pod in live(pods) if pod["metadata"].get("labels", {}).get(NAME_LABEL) not in services}


def chart_changed(base, commit, root=ROOT):
    """Whether the chart differs between the two commits, as infra/flux/release.sh compares it."""
    return subprocess.run(["git", "-C", str(root), "diff", "--quiet", base, commit, "--", CHART]).returncode != 0


class Cluster:
    def __init__(self, args):
        self.cluster = args.cluster
        self.namespace = args.namespace
        self.control_url = args.control_url.rstrip("/")

    def command(self, *argv, check=True, stdin=None):
        result = subprocess.run(argv, capture_output=True, text=True, input=stdin)
        if check and result.returncode != 0:
            raise ScenarioError(f"{' '.join(argv)}: exit {result.returncode}: {result.stderr.strip()}")
        return result

    def kubectl(self, *argv, check=True):
        return self.command("kubectl", "--namespace", self.namespace, *argv, check=check)

    def require_k3d(self):
        context = self.command("kubectl", "config", "current-context").stdout.strip()
        if context != f"k3d-{self.cluster}":
            raise ScenarioError(f"kubectl points at {context}, not k3d-{self.cluster}: the scenario kills pods")

    def values(self):
        """The chart's values as the last release of the chain got them: the chart's own, the cluster's, the pointer's."""
        output = self.command("helm", "get", "values", CONTROL, "--namespace", self.namespace, "--all", "-o", "json")
        return json.loads(output.stdout)

    def releases(self):
        return json.loads(self.kubectl("get", HELM_RELEASES, "-o", "json").stdout)["items"]

    def pods(self):
        return json.loads(self.kubectl("get", "pods", "-o", "json").stdout)["items"]

    def psql(self, sql):
        return self.kubectl("exec", POSTGRES_POD, "--", "psql", "-v", "ON_ERROR_STOP=1", "-tAq", "-U", POSTGRES_ADMIN,
                            "-d", POSTGRES_DATABASE, "-c", sql).stdout.strip()

    def money(self):
        """(the balances' total, the opening total)"""
        total, opening = self.psql(MONEY_SQL).split("|")
        return int(total), int(opening)

    def raw(self, path):
        result = subprocess.run(["kubectl", "get", "--raw", path], capture_output=True)
        if result.returncode != 0:
            raise OSError(result.stderr.decode(errors="replace").strip())
        return result.stdout

    def query(self, promql, at):
        """One number from the bank's Prometheus, read as the release check reads it; release_check.NotChecked without
        one."""
        proxy = f"/api/v1/namespaces/{self.namespace}/services/{PROMETHEUS_SERVICE}/proxy"
        return release_check.query_value(self.raw, proxy, promql, at)

    def get(self, path):
        try:
            with urllib.request.urlopen(self.control_url + path, timeout=HTTP_TIMEOUT_SECONDS) as response:
                return response.status, response.read()
        except urllib.error.HTTPError as error:
            return error.code, error.read()
        except (urllib.error.URLError, OSError) as error:
            return None, str(error).encode()

    def get_json(self, path):
        status, body = self.get(path)
        if status != 200:
            raise ScenarioError(f"GET {path}: {status} {body[:200]!r}")
        return json.loads(body)


class Scenario:
    def __init__(self, args):
        self.args = args
        self.k = Cluster(args)
        self.artifacts = Path(args.artifacts)
        self.checks = []
        self.stages = []
        self.summary = []

    def stage(self, name, action):
        say(f"== {name}")
        started = time.time()
        try:
            action()
        finally:
            took = time.time() - started
            self.stages.append((name, took))
            say(f"== {name}: {took:.0f}s")

    def wait(self, what, reached_it, timeout):
        deadline = time.time() + timeout
        while not reached_it():
            if time.time() > deadline:
                raise ScenarioError(f"{what}: not within {timeout}s")
            time.sleep(POLL_SECONDS)

    def git(self, *argv):
        return subprocess.run(["git", "-C", str(ROOT), *argv], capture_output=True, text=True, check=True).stdout.strip()

    def setup(self):
        self.k.require_k3d()
        values = self.k.values()
        self.period_seconds = int(values["periodMinutes"]) * SECONDS_PER_MINUTE
        self.verdict_deadline = duration_seconds(values["closing"]["verdictDeadline"])
        self.tps = steady_tps(values["load"])
        self.window = check_window(values)
        self.base = self.args.base
        self.commit = self.git("rev-parse", "--verify", f"{self.args.commit}^{{commit}}")
        # As infra/flux/release.sh writes the version into the pointer.
        self.version = self.git("describe", "--tags", "--match", "v[0-9]*", "--long", "--always", self.commit)
        self.mutate = os.environ.get(MUTATE_ENV) == "1"

    def run(self):
        self.artifacts.mkdir(parents=True, exist_ok=True)
        # Any failure, expected or not, still ends in the report and the collected state.
        try:
            self.stage("setup", self.setup)
            if self.mutate:
                say(f"{MUTATE_ENV}=1: the money is expected one cent higher, so this run must fail")
            if self.base:
                # Before the bank is watched for its steady state: a build takes the processor from it.
                self.stage("the commit's images", lambda: self.flux("images", {ONLY_IMAGES_ENV: "1"}))
            self.stage("wait for a steady bank", self.steady)
            self.started = time.time()
            self.money_before = self.k.money()[0]
            say(f"money on the accounts: {self.money_before} cents")
            if self.base:
                self.stage("deploy the commit over its base", self.deploy)
            else:
                self.stage("the installed chain", self.installed)
            self.stage("kill ledger-0 and the gateway", self.crash)
        except Exception as error:
            self.checks.append(Check("scenario", False, f"{type(error).__name__}: {error}"))
        self.ended = time.time()
        if hasattr(self, "money_before"):
            try:
                self.stage("wait for the verdicts", self.verdicts)
            except Exception as error:
                self.checks.append(Check("verdicts", False, f"{type(error).__name__}: {error}"))
        self.final_checks()
        return self.report()

    def steady_now(self):
        at = time.time()
        try:
            broken = {name: round(self.k.query(template.format(r=self.window), at))
                      for name, template, _ in release_check.GUARANTEES}
            accepted = self.k.query(ACCEPTED_QUERY.format(r=self.window), at)
        except release_check.NotChecked as error:
            return False, f"Prometheus: {error}"
        return steady_state(broken, accepted, self.tps, self.window)

    def steady(self):
        """Returns once a release check started now would not count what the bank's start left behind."""
        deadline = time.time() + STEADY_TIMEOUT_SECONDS
        told = None
        while True:
            ok, detail = self.steady_now()
            if ok:
                say(f"steady: {detail}")
                return
            if detail != told:
                say(f"not steady yet: {detail}")
                told = detail
            if time.time() > deadline:
                raise ScenarioError(f"the bank is not steady within {STEADY_TIMEOUT_SECONDS}s: {detail}")
            time.sleep(POLL_SECONDS)

    def flux(self, name, env):
        with open(self.artifacts / f"flux-{name}.log", "w", encoding="utf-8") as log:
            process = subprocess.Popen([str(FLUX), self.commit], stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                       text=True, env=dict(os.environ, CLUSTER=self.args.cluster,
                                                           NAMESPACE=self.args.namespace, **env))
            for line in process.stdout:
                print(f"  {line}", end="", flush=True)
                log.write(line)
            code = process.wait()
        if code != 0:
            raise ScenarioError(f"flux.sh ({name}): exit {code}")

    def chain(self, since):
        """Waits until every release of the chain is ready on the commit; returns the releases."""
        deadline = time.time() + CHAIN_TIMEOUT_SECONDS
        told = None
        while True:
            releases = self.k.releases()
            done = reached(releases, self.commit)
            if len(done) == len(CHAIN):
                return releases
            failure = chain_failure(releases, since)
            if failure:
                raise ScenarioError(f"the chain stopped at {failure}")
            if done != told:
                say(f"on the commit: {', '.join(done) or 'no release yet'}")
                told = done
            if time.time() > deadline:
                raise ScenarioError(f"the chain did not reach {self.commit} within {CHAIN_TIMEOUT_SECONDS}s: "
                                    f"ready on it {done or 'none'}")
            time.sleep(POLL_SECONDS)

    def deploy(self):
        tags_before = tags_of(self.k.releases())
        before = self.settled_pods()
        since = math.floor(time.time())
        # The images are in the node since the stage before, so the registry is not asked again under the watch.
        self.flux("pointer", {IMAGES_SOURCE_ENV: IMAGES_ANY})
        releases = self.chain(since)
        updated = updated_services(tags_before, tags_of(releases))
        chart_differs = chart_changed(self.base, self.commit)
        say(f"from {self.base}: chart {'changed' if chart_differs else 'unchanged'}, new images of "
            f"{', '.join(sorted(updated)) or 'no service'}")
        self.checks.append(order_check(releases))
        self.checks += release_check_checks(releases, expected_checked(updated, chart_differs))
        replaced = updated | {CONTROL}
        after = self.settled_pods()
        self.checks.append(replaced_check(before, after, replaced))
        if chart_differs:
            say("the chart changed and may replace any pod: the other pods are not compared")
        else:
            self.kept(before, after, replaced, "deploy left every pod of the services it did not change as it was")

    def installed(self):
        releases = self.k.releases()
        done = reached(releases, self.commit)
        if len(done) != len(CHAIN):
            raise ScenarioError(f"the cluster does not run {self.commit}: ready on it {done or 'none'}")
        self.checks.append(order_check(releases))

    def crash(self):
        pods = live(self.k.pods())
        victims = {pod["metadata"]["name"]: pod["metadata"]["uid"] for pod in pods
                   if pod["metadata"]["name"] == CRASHED_LEDGER_POD
                   or pod["metadata"].get("labels", {}).get(NAME_LABEL) == GATEWAY}
        if CRASHED_LEDGER_POD not in victims or len(victims) < 2:
            raise ScenarioError(f"no {CRASHED_LEDGER_POD} or no gateway pod to kill: {sorted(victims)}")
        for name in victims:
            log = self.k.kubectl("logs", name, "--all-containers", "--timestamps", check=False)
            (self.artifacts / f"killed-{name}.log").write_text(log.stdout + log.stderr, encoding="utf-8")
        say(f"killing {', '.join(sorted(victims))}")
        self.k.kubectl("delete", "pod", "--force", "--grace-period=0", "--wait=false", *victims)
        killed = set(victims.values())
        started = time.time()
        self.wait("ledger-0 and the gateway back", lambda: back(self.k.pods(), killed), READY_TIMEOUT_SECONDS)
        self.checks.append(Check("killed ledger-0 and the gateway came back", True, f"ready in {time.time() - started:.0f}s"))

    def settled_pods(self):
        def none_terminating():
            pods = self.k.pods()
            return len(live(pods)) == len(pods)
        self.wait("the replaced pods gone", none_terminating, TERMINATING_TIMEOUT_SECONDS)
        return self.k.pods()

    def kept(self, before, after, replaced, what):
        """Every pod outside the replaced services as it was before the deploy, at its end and a while later."""
        self.checks.append(uid_check(pods_except(before, replaced), pods_except(after, replaced),
                                     f"{what} (at its end)"))
        time.sleep(LATE_SNAPSHOT_SECONDS)
        late = self.k.pods()
        self.checks.append(uid_check(pods_except(before, replaced), pods_except(late, replaced),
                                     f"{what} ({LATE_SNAPSHOT_SECONDS}s later)"))

    def verdicts(self):
        waited = waited_starts(self.started, self.ended, self.period_seconds)
        deadline = verdicts_due(waited, self.period_seconds, self.verdict_deadline)
        say(f"periods from {clock(waited[0])} to {clock(waited[-1])} and the one after: the last verdict is due by "
            f"{clock(deadline)} UTC")
        waiting = None
        while True:
            try:
                periods = self.k.get_json(f"/api/bank/periods?limit={PERIODS_LIMIT}")["periods"]
            except ScenarioError as error:
                say(f"control: {error}")
                periods = []
            pending = pending_starts(periods, waited)
            if not pending:
                break
            if pending != waiting:
                say(f"waiting for the verdicts of {', '.join(clock(start) for start in pending)}")
                waiting = pending
            if time.time() > deadline:
                raise ScenarioError(f"no verdict by {clock(deadline)} UTC for "
                                    f"{', '.join(clock(start) for start in pending)}")
            time.sleep(VERDICT_POLL_SECONDS)
        fetched = []
        for start in waited:
            iso = datetime.fromtimestamp(start, timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
            fetched.append(self.k.get_json(f"/api/bank/periods/{iso}"))
        self.details = fetched[:-1]
        for details, following in zip(fetched, fetched[1:]):
            self.checks += period_checks(details, following)
            self.checks.append(load_check(details, self.tps, self.started, self.ended))
        self.checks.append(periods_count_check(self.details))
        self.summary.append(totals_line(self.details, fetched[-1]))

    def final_checks(self):
        try:
            self.checks.append(money_check(self.money_before, *self.k.money(), self.mutate))
        except Exception as error:
            self.checks.append(Check("money on the accounts unchanged", False, f"not read: {error}"))
        status, _ = self.k.get("/")
        self.checks.append(Check("page /", status == 200, f"status {status}"))
        status, body = self.k.get("/api/site")
        self.checks.append(site_check(status, body, getattr(self, "version", None)))

    def report(self):
        print()
        for name, took in self.stages:
            print(f"stage {name}: {took:.0f}s")
        for line in self.summary:
            print(line)
        for check in self.checks:
            print(check.line())
        failed = [check for check in self.checks if not check.ok]
        print(f"e2e: {len(self.checks)} checks, {len(failed)} failed")
        if failed:
            try:
                collect(self.k, self.artifacts / "cluster")
            except Exception as error:
                print(f"collecting the cluster's state failed: {error}")
            print(f"the cluster's state: {self.artifacts}")
        return 1 if failed else 0


def collect(cluster, directory):
    directory.mkdir(parents=True, exist_ok=True)

    def save(name, *argv):
        result = cluster.command(*argv, check=False)
        (directory / name).write_text(result.stdout + result.stderr, encoding="utf-8")

    def logs(prefix, k):
        names = cluster.command(*k, "get", "pods", "-o", "jsonpath={.items[*].metadata.name}", check=False).stdout.split()
        for name in names:
            save(f"{prefix}{name}.log", *k, "logs", name, "--all-containers", "--timestamps")
            save(f"{prefix}{name}-previous.log", *k, "logs", name, "--all-containers", "--timestamps", "--previous")

    k = ("kubectl", "--namespace", cluster.namespace)
    flux = ("kubectl", "--namespace", FLUX_NAMESPACE)
    save("get-all.txt", *k, "get", "all", "-o", "wide")
    save("describe-pods.txt", *k, "describe", "pods")
    save("events.txt", *k, "get", "events", "--sort-by=.lastTimestamp")
    save("helmreleases.yaml", *k, "get", HELM_RELEASES, "-o", "yaml")
    save("helm-values.yaml", "helm", "get", "values", CONTROL, "--namespace", cluster.namespace, "--all")
    save("flux-sources.yaml", "kubectl", "get", "gitrepositories.source.toolkit.fluxcd.io,"
         "helmcharts.source.toolkit.fluxcd.io,kustomizations.kustomize.toolkit.fluxcd.io", "--all-namespaces", "-o", "yaml")
    save("flux-events.txt", *flux, "get", "events", "--sort-by=.lastTimestamp")
    logs("logs-", k)
    logs("flux-logs-", flux)
    status, body = cluster.get(f"/api/bank/periods?limit={PERIODS_LIMIT}")
    (directory / "periods.json").write_bytes(body if status == 200 else f"status {status}: ".encode() + body)
    if status == 200:
        for period in json.loads(body).get("periods", []):
            _, details = cluster.get(f"/api/bank/periods/{period['start']}")
            (directory / f"period-{period['start'].replace(':', '')}.json").write_bytes(details)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("command", choices=("run", "steady", "collect", "mode"))
    parser.add_argument("--base", metavar="COMMIT",
                        help="the commit the bank runs (infra/e2e/install-base.sh); without it --commit is the one installed")
    parser.add_argument("--commit", default="HEAD", help="the commit tested")
    parser.add_argument("--event", help="mode: the GitHub event")
    parser.add_argument("--changed", type=int, help="mode: the exit code of infra/ci/changed.sh <parent> <commit>")
    parser.add_argument("--base-flux", choices=("yes", "no"), help="mode: whether the parent has infra/flux/release.sh")
    parser.add_argument("--cluster", default="ledger-lab")
    parser.add_argument("--namespace", default="ledger-lab")
    parser.add_argument("--control-url", default="http://localhost:8083")
    parser.add_argument("--artifacts", default=os.path.join(tempfile.gettempdir(), "ledger-lab-e2e"))
    args = parser.parse_args(argv)
    if args.command == "mode":
        if args.event is None or args.changed is None or args.base_flux is None:
            parser.error("mode needs --event, --changed and --base-flux")
        try:
            print(choose_mode(args.event, args.changed, args.base_flux == "yes"))
        except ValueError as error:
            print(f"e2e: {error}", file=sys.stderr)
            return 1
        return 0
    try:
        if args.command == "collect":
            collect(Cluster(args), Path(args.artifacts))
            return 0
        if args.command == "steady":
            scenario = Scenario(args)
            scenario.setup()
            scenario.steady()
            return 0
        return Scenario(args).run()
    except ScenarioError as error:
        print(f"e2e: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())

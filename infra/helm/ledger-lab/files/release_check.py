#!/usr/bin/env python3
"""The release check after a service's upgrade: did the guarantee hold while the new version rolled out and settled
under load. Asks Prometheus only; prints one line and exits 0 (kept, or not checked because of chaos) or 1 (broken, or
not checked)."""

import json
import math
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

EXIT_KEPT = 0
EXIT_FAILED = 1

DEFAULT_SETTLE_SECONDS = 120
# As control's control.outside.chaos-aftermath: slow transfers follow a crash of the ledger for this long.
DEFAULT_CHAOS_AFTERMATH_SECONDS = 60
PROMETHEUS_TIMEOUT_SECONDS = 10
QUERY_PATH = "/api/v1/query"

# The gateway's latency bucket at its 3 s threshold (gateway.latency.slo-thresholds), as Micrometer spells it.
SLOW_BUCKET = "3.0"
ALL_BUCKET = "+Inf"

# Each must stay at exactly 0 in the window. Every series here is registered when its service starts, so it exists at
# 0 before anything happens; an empty answer therefore means the metric is not scraped, never that the count is zero.
GUARANTEES = (
    (">3s", 'sum(increase(gateway_transfer_latency_seconds_bucket{{le="' + ALL_BUCKET + '"}}[{r}s]))'
            ' - sum(increase(gateway_transfer_latency_seconds_bucket{{le="' + SLOW_BUCKET + '"}}[{r}s]))', 0),
    ("503", 'sum(increase(gateway_transfers_total{{outcome="refused"}}[{r}s]))', 0),
    ("pool timeouts", 'sum(increase(hikaricp_connections_timeout_total{{job="gateway"}}[{r}s]))', 0),
)
# Control's count of chaos presses (ChaosService.METRIC), registered at its start, so the same empty-answer rule applies.
CHAOS_SERIES = 'control_chaos_events_total{job="control"}'

LABEL_CHAOS = "not checked (chaos)"
LABEL_NOT_CHECKED = "not checked"


def chaos_query(seconds):
    return f"sum(increase({CHAOS_SERIES}[{seconds}s]))"


class NotChecked(Exception):
    pass


def http_fetch(url):
    with urllib.request.urlopen(url, timeout=PROMETHEUS_TIMEOUT_SECONDS) as response:
        return response.read()


def query_value(fetch, prometheus_url, promql, at):
    """The single number a sum() query returns, or NotChecked naming why there is none."""
    params = urllib.parse.urlencode({"query": promql, "time": f"{at:.3f}"})
    try:
        raw = fetch(f"{prometheus_url.rstrip('/')}{QUERY_PATH}?{params}")
    except urllib.error.HTTPError as e:
        e.close()
        raise NotChecked(f"Prometheus answered HTTP {e.code}") from e
    except (urllib.error.URLError, OSError) as e:
        raise NotChecked(f"Prometheus unreachable: {e}") from e
    try:
        body = json.loads(raw)
    except ValueError as e:
        raise NotChecked("Prometheus answer is not JSON") from e
    if not isinstance(body, dict) or body.get("status") != "success":
        error = body.get("error", "no status") if isinstance(body, dict) else "no status"
        raise NotChecked(f"Prometheus status not success: {error}")
    result = (body.get("data") or {}).get("result")
    if not isinstance(result, list):
        raise NotChecked(f"malformed answer for {promql}")
    if len(result) != 1:
        raise NotChecked(f"{len(result)} series for {promql}")
    try:
        value = float(result[0]["value"][1])
    except (KeyError, IndexError, TypeError, ValueError) as e:
        raise NotChecked(f"malformed answer for {promql}") from e
    if math.isnan(value) or math.isinf(value):
        raise NotChecked(f"no number for {promql}")
    return value


def whole_seconds(env, name, default=None):
    text = env.get(name)
    if text is None or text == "":
        if default is None:
            raise NotChecked(f"{name} is not set")
        return default
    try:
        seconds = int(text)
    except ValueError as e:
        raise NotChecked(f"{name} is not a whole number: {text}") from e
    if seconds < 0:
        raise NotChecked(f"{name} is negative: {text}")
    return seconds


def check(env, fetch=http_fetch, sleep=time.sleep, clock=time.time):
    """Returns (line, exit code)."""
    service = env.get("SERVICE") or "service"
    try:
        prometheus_url = env.get("PROMETHEUS_URL")
        if not prometheus_url:
            raise NotChecked("PROMETHEUS_URL is not set")
        rollout = whole_seconds(env, "ROLLOUT_SECONDS")
        settle = whole_seconds(env, "SETTLE_SECONDS", DEFAULT_SETTLE_SECONDS)
        aftermath = whole_seconds(env, "CHAOS_AFTERMATH_SECONDS", DEFAULT_CHAOS_AFTERMATH_SECONDS)
        window = max(1, rollout + settle)
        # A press shortly before the window still slows transfers inside it.
        chaos_window = window + aftermath
        sleep(settle)
        at = clock()
        if query_value(fetch, prometheus_url, chaos_query(chaos_window), at) > 0:
            return f"{service}: {LABEL_CHAOS}: chaos was started from the page in the last {chaos_window} s", EXIT_KEPT
        broken = []
        for name, template, allowed in GUARANTEES:
            count = round(query_value(fetch, prometheus_url, template.format(r=window), at))
            if count > allowed:
                broken.append(f"{name} {count}")
    except NotChecked as e:
        return f"{service}: {LABEL_NOT_CHECKED}: {e}", EXIT_FAILED
    if broken:
        return f"{service}: guarantee broken in the last {window} s: {', '.join(broken)}", EXIT_FAILED
    return f"{service}: guarantee kept in the last {window} s", EXIT_KEPT


def main():
    line, code = check(os.environ)
    print(line, flush=True)
    return code


if __name__ == "__main__":
    sys.exit(main())

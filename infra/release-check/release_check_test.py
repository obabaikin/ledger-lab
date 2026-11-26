import http.server
import json
import os
import re
import socket
import sys
import threading
import unittest
import urllib.parse

# The script lives in the chart: Flux builds the chart from its own directory only.
CHART_FILES = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "helm", "ledger-lab", "files")
sys.path.insert(0, CHART_FILES)
# A __pycache__ beside the script would be packed into the chart.
sys.dont_write_bytecode = True

import release_check  # noqa: E402

ROLLOUT = 47
SETTLE = 120
AFTERMATH = 60
CHAOS_FRAGMENT = "control_chaos_events_total"
NOW = 1_800_000_000.0
SCRIPT = os.path.join(CHART_FILES, "release_check.py")


def vector(value):
    return {"status": "success", "data": {"resultType": "vector", "result": [{"metric": {}, "value": [NOW, str(value)]}]}}


class Prometheus:
    """Canned answers keyed by a fragment of the query; records every query sent."""

    def __init__(self, inf=500, within=500, refused=0, pool_timeouts=0, chaos=0, answers=None):
        self.answers = answers or {
            'le="+Inf"': None, "outcome=\"refused\"": vector(refused),
            "hikaricp_connections_timeout_total": vector(pool_timeouts), "control_chaos_events_total": vector(chaos),
        }
        self.slow = vector(inf - within)
        self.sent = []

    def __call__(self, url):
        query = urllib.parse.parse_qs(urllib.parse.urlsplit(url).query)["query"][0]
        self.sent.append(query)
        for fragment, answer in self.answers.items():
            if fragment in query:
                return json.dumps(answer if answer is not None else self.slow).encode()
        raise AssertionError(f"unexpected query {query}")


def env(**overrides):
    values = {"PROMETHEUS_URL": "http://prometheus:9090", "SERVICE": "gateway", "ROLLOUT_SECONDS": str(ROLLOUT),
              "SETTLE_SECONDS": str(SETTLE), "CHAOS_AFTERMATH_SECONDS": str(AFTERMATH)}
    values.update(overrides)
    return values


def run(prometheus, **overrides):
    slept = []
    line, code = release_check.check(env(**overrides), fetch=prometheus, sleep=slept.append, clock=lambda: NOW)
    return line, code, slept


class GuaranteeTest(unittest.TestCase):
    def test_zeros_keep_the_guarantee(self):
        line, code, _ = run(Prometheus())
        self.assertEqual(code, release_check.EXIT_KEPT)
        self.assertIn("guarantee kept", line)

    def test_one_transfer_slower_than_3s_breaks_it_and_is_named(self):
        line, code, _ = run(Prometheus(inf=500, within=499))
        self.assertEqual(code, release_check.EXIT_FAILED)
        self.assertIn(">3s 1", line)

    def test_one_refused_transfer_breaks_it_and_is_named(self):
        line, code, _ = run(Prometheus(refused=1))
        self.assertEqual(code, release_check.EXIT_FAILED)
        self.assertIn("503 1", line)

    def test_one_pool_timeout_breaks_it_and_is_named(self):
        line, code, _ = run(Prometheus(pool_timeouts=1))
        self.assertEqual(code, release_check.EXIT_FAILED)
        self.assertIn("pool timeouts 1", line)

    def test_every_broken_guarantee_is_on_the_one_line(self):
        line, code, _ = run(Prometheus(within=497, refused=2, pool_timeouts=4))
        self.assertEqual(code, release_check.EXIT_FAILED)
        self.assertEqual(line.count("\n"), 0)
        self.assertIn(">3s 3, 503 2, pool timeouts 4", line)


class ChaosTest(unittest.TestCase):
    def test_chaos_in_the_window_is_not_checked_and_does_not_fail(self):
        line, code, _ = run(Prometheus(refused=5, chaos=1))
        self.assertEqual(code, release_check.EXIT_KEPT)
        self.assertIn("not checked (chaos)", line)

    def test_no_chaos_answer_is_not_read_as_no_chaos(self):
        empty = {"status": "success", "data": {"resultType": "vector", "result": []}}
        prometheus = Prometheus()
        prometheus.answers["control_chaos_events_total"] = empty
        line, code, _ = run(prometheus)
        self.assertEqual(code, release_check.EXIT_FAILED)
        self.assertIn("not checked", line)


class NotCheckedTest(unittest.TestCase):
    def assert_not_checked(self, prometheus, **overrides):
        line, code, _ = run(prometheus, **overrides)
        self.assertEqual(code, release_check.EXIT_FAILED)
        self.assertIn("gateway: not checked: ", line)
        return line

    def test_an_unreachable_prometheus_is_not_checked(self):
        with socket.socket() as probe:
            probe.bind(("127.0.0.1", 0))
            port = probe.getsockname()[1]
        line, code = release_check.check(env(PROMETHEUS_URL=f"http://127.0.0.1:{port}"), sleep=lambda _: None)
        self.assertEqual(code, release_check.EXIT_FAILED)
        self.assertIn("not checked: Prometheus unreachable", line)

    def test_an_http_error_is_not_checked(self):
        class Failing(http.server.BaseHTTPRequestHandler):
            def do_GET(self):
                self.send_error(503)

            def log_message(self, *args):
                pass

        server = http.server.HTTPServer(("127.0.0.1", 0), Failing)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        try:
            line, code = release_check.check(env(PROMETHEUS_URL=f"http://127.0.0.1:{server.server_port}"),
                                             sleep=lambda _: None)
        finally:
            server.shutdown()
            server.server_close()
        self.assertEqual(code, release_check.EXIT_FAILED)
        self.assertIn("not checked: Prometheus answered HTTP 503", line)

    def test_an_error_status_is_not_checked(self):
        error = {"status": "error", "errorType": "bad_data", "error": "parse error"}
        line = self.assert_not_checked(Prometheus(answers={"": error}))
        self.assertIn("parse error", line)

    def test_an_absent_series_is_not_read_as_zero(self):
        empty = {"status": "success", "data": {"resultType": "vector", "result": []}}
        for fragment in ('le="+Inf"', 'outcome="refused"', "hikaricp_connections_timeout_total"):
            with self.subTest(fragment=fragment):
                prometheus = Prometheus()
                prometheus.answers[fragment] = empty
                self.assert_not_checked(prometheus)

    def test_a_malformed_answer_is_not_checked(self):
        for answer in ({"status": "success"}, {"status": "success", "data": {"result": [{"value": []}]}},
                       {"status": "success", "data": {"result": [{"value": [NOW, "NaN"]}]}}):
            with self.subTest(answer=answer):
                self.assert_not_checked(Prometheus(answers={"": answer}))

    def test_an_answer_that_is_not_json_is_not_checked(self):
        self.assert_not_checked(lambda url: b"<html>")

    def test_a_missing_rollout_length_is_not_checked(self):
        line = self.assert_not_checked(Prometheus(), ROLLOUT_SECONDS="")
        self.assertIn("ROLLOUT_SECONDS", line)


class WindowTest(unittest.TestCase):
    def test_every_query_covers_the_rollout_and_the_settle_time(self):
        prometheus = Prometheus()
        run(prometheus)
        guarantees = [query for query in prometheus.sent if CHAOS_FRAGMENT not in query]
        self.assertEqual(len(guarantees), 3)
        for query in guarantees:
            self.assertEqual(set(re.findall(r"\[(\d+)s\]", query)), {str(ROLLOUT + SETTLE)}, query)

    def test_the_chaos_query_also_covers_the_aftermath_of_a_press_before_the_window(self):
        prometheus = Prometheus()
        run(prometheus)
        chaos = [query for query in prometheus.sent if CHAOS_FRAGMENT in query]
        self.assertEqual(len(chaos), 1)
        self.assertEqual(re.findall(r"\[(\d+)s\]", chaos[0]), [str(ROLLOUT + SETTLE + AFTERMATH)])

    def test_without_the_chaos_aftermath_the_release_is_not_checked_as_the_chart_alone_names_it(self):
        prometheus = Prometheus()
        values = env()
        del values["CHAOS_AFTERMATH_SECONDS"]
        line, code = release_check.check(values, fetch=prometheus, sleep=lambda _: None, clock=lambda: NOW)
        self.assertEqual((line, code), ("gateway: not checked: CHAOS_AFTERMATH_SECONDS is not set", 1))
        self.assertEqual(prometheus.sent, [])

    def test_the_window_follows_the_rollout_length_not_a_fixed_one(self):
        prometheus = Prometheus()
        run(prometheus, ROLLOUT_SECONDS="300", SETTLE_SECONDS="60")
        self.assertTrue(all("[360s]" in query for query in prometheus.sent if CHAOS_FRAGMENT not in query),
                        prometheus.sent)

    def test_it_waits_the_settle_time_before_asking(self):
        _, _, slept = run(Prometheus())
        self.assertEqual(slept, [SETTLE])

    def test_settle_defaults_to_two_minutes(self):
        prometheus = Prometheus()
        slept = []
        values = env()
        del values["SETTLE_SECONDS"]
        release_check.check(values, fetch=prometheus, sleep=slept.append, clock=lambda: NOW)
        self.assertEqual(slept, [120])
        self.assertTrue(all(f"[{ROLLOUT + 120}s]" in query for query in prometheus.sent if CHAOS_FRAGMENT not in query))

    def test_the_queries_are_the_gateways_guarantee_series(self):
        prometheus = Prometheus()
        run(prometheus)
        sent = "\n".join(prometheus.sent)
        self.assertIn('gateway_transfer_latency_seconds_bucket{le="3.0"}', sent)
        self.assertIn('gateway_transfer_latency_seconds_bucket{le="+Inf"}', sent)
        self.assertIn('gateway_transfers_total{outcome="refused"}', sent)
        self.assertIn('hikaricp_connections_timeout_total{job="gateway"}', sent)


class BoundaryTest(unittest.TestCase):
    def test_the_script_runs_no_commands(self):
        with open(SCRIPT) as source:
            text = source.read()
        for forbidden in ("subprocess", "os.system", "os.popen", "os.exec", "kubectl", "helm"):
            self.assertNotIn(forbidden, text)


if __name__ == "__main__":
    unittest.main()

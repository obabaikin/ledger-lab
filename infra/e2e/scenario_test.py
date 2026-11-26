#!/usr/bin/env python3
"""Unit tests of scenario.py: python3 -m unittest discover -s infra/e2e -p '*_test.py'"""

import argparse
import contextlib
import io
import json
import os
import subprocess
import tempfile
import time
import unittest
from datetime import datetime, timezone
from pathlib import Path

import scenario


def at(hour, minute, second=0):
    return datetime(2026, 11, 25, hour, minute, second, tzinfo=timezone.utc).timestamp()


def iso(hour, minute):
    return f"2026-11-25T{hour:02d}:{minute:02d}:00Z"


def details(accepted=1000, journal=1000, rows=1000, unknown=0, resolved=0, breaks=None, state="RECONCILED",
            verdict="BALANCED", notes=(), report=True, start=iso(4, 10), end=iso(4, 15)):
    period = {"start": start, "end": end, "state": state, "verdict": verdict, "failure": None}
    if not report:
        return {"period": period, "report": None}
    counts = {"acknowledgedNotInStatement": 0, "failedButInStatement": 0, "notInJournal": 0, "payloadMismatch": 0,
              "statusMismatch": 0}
    counts.update(breaks or {})
    return {"period": period, "report": {
        "verdict": verdict, "mismatches": [], "gateway": {"accepted": accepted},
        "client": {"acceptedByJournal": journal, "statement": {"rows": rows, "unknownToClient": unknown},
                   "breaks": {name: {"count": count} for name, count in counts.items()},
                   "unknownResolved": {"accepted": resolved, "notAccepted": 13}},
        "notes": list(notes)}}


def next_period(resolved=0):
    return details(resolved=resolved)


def pod(name, uid, service=None, terminating=False, ready=True, restarts=0):
    metadata = {"name": name, "uid": uid, "labels": {scenario.NAME_LABEL: service} if service else {}}
    if terminating:
        metadata["deletionTimestamp"] = "2026-11-25T04:10:00Z"
    return {"metadata": metadata, "status": {"conditions": [{"type": "Ready", "status": "True" if ready else "False"}],
                                             "containerStatuses": [{"name": "main", "restartCount": restarts}]}}


START = at(4, 12)


class DurationTest(unittest.TestCase):
    def test_one_number_and_one_unit(self):
        self.assertEqual(scenario.duration_seconds("330s"), 330)
        self.assertEqual(scenario.duration_seconds("40m"), 2400)
        self.assertEqual(scenario.duration_seconds("1h"), 3600)

    def test_plain_seconds(self):
        self.assertEqual(scenario.duration_seconds("90"), 90)

    def test_two_units_are_refused_as_spring_refuses_them(self):
        with self.assertRaises(ValueError):
            scenario.duration_seconds("5m30s")


class CrossedPeriodsTest(unittest.TestCase):
    def test_every_period_sharing_time_with_the_scenario(self):
        self.assertEqual(scenario.crossed_starts(at(4, 12, 30), at(4, 31), 300),
                         [at(4, 10), at(4, 15), at(4, 20), at(4, 25), at(4, 30)])

    def test_a_scenario_ending_on_a_boundary_leaves_the_next_period_out(self):
        self.assertEqual(scenario.crossed_starts(at(4, 10), at(4, 20), 300), [at(4, 10), at(4, 15)])

    def test_a_fraction_of_a_second_into_a_period_counts(self):
        self.assertEqual(scenario.crossed_starts(at(4, 10), at(4, 15) + 0.5, 300), [at(4, 10), at(4, 15)])

    def test_a_scenario_inside_one_period(self):
        self.assertEqual(scenario.crossed_starts(at(4, 11), at(4, 12), 300), [at(4, 10)])

    def test_a_period_is_pending_until_it_is_final(self):
        periods = [{"start": iso(4, 10), "state": "RECONCILED"}, {"start": iso(4, 15), "state": "CLOSING"},
                   {"start": iso(4, 20), "state": "FAILED"}]
        starts = [at(4, 10), at(4, 15), at(4, 20), at(4, 25)]
        self.assertEqual(scenario.pending_starts(periods, starts), [at(4, 15), at(4, 25)])


class PeriodChecksTest(unittest.TestCase):
    def check(self, current, following=None, index=1):
        return scenario.period_checks(current, following or next_period())[index]

    def test_a_balanced_period_passes_all_four(self):
        checks = scenario.period_checks(details(), next_period())
        self.assertTrue(all(check.ok for check in checks), [check.line() for check in checks])
        self.assertEqual(len(checks), 4)
        self.assertEqual(checks[0].name, "period 04:10-04:15 verdict")
        self.assertIn("gateway accepted 1000, clients' journal 1000 + unknown to them 0", checks[1].detail)

    def test_a_mismatch_fails_the_verdict(self):
        self.assertFalse(self.check(details(verdict="MISMATCH"), index=0).ok)

    def test_a_failed_period_fails_the_verdict(self):
        self.assertFalse(self.check(details(state="FAILED", verdict=None), index=0).ok)

    def test_a_clients_journal_short_of_the_gateway_fails(self):
        self.assertFalse(self.check(details(journal=999)).ok)
        self.assertFalse(self.check(details(rows=999)).ok)

    def test_transfers_unknown_to_the_clients_count_with_their_journal(self):
        current = details(accepted=88265, journal=88263, rows=88265, unknown=2)
        totals = self.check(current, next_period(resolved=2))
        self.assertTrue(totals.ok, totals.line())
        self.assertIn("+ unknown to them 2", totals.detail)

    def test_unknown_transfers_beyond_the_gateway_fail(self):
        self.assertFalse(self.check(details(accepted=1000, journal=999, rows=1000, unknown=2)).ok)

    def test_a_break_fails_and_is_named(self):
        check = self.check(details(breaks={"notInJournal": 1}))
        self.assertFalse(check.ok)
        self.assertIn("notInJournal", check.detail)

    def test_breaks_not_reported_fail(self):
        current = details()
        del current["report"]["client"]["breaks"]
        self.assertFalse(self.check(current).ok)

    def test_unknown_all_resolved_as_accepted_by_the_next_period_passes(self):
        current = details(accepted=88265, journal=88263, rows=88265, unknown=2)
        self.assertTrue(self.check(current, next_period(resolved=2), index=2).ok)

    def test_unknown_resolved_short_or_over_fails(self):
        current = details(accepted=88265, journal=88263, rows=88265, unknown=2)
        self.assertFalse(self.check(current, next_period(resolved=1), index=2).ok)
        self.assertFalse(self.check(current, next_period(resolved=3), index=2).ok)

    def test_nothing_unknown_and_nothing_resolved_passes(self):
        self.assertTrue(self.check(details(), next_period(resolved=0), index=2).ok)

    def test_reporting_behind_the_books_or_missing_fails(self):
        self.assertFalse(self.check(details(notes=["PROJECTION_BEHIND_BOOKS"]), index=3).ok)
        self.assertFalse(self.check(details(notes=["REPORTING_UNAVAILABLE"]), index=3).ok)

    def test_a_note_about_something_else_is_not_a_discrepancy(self):
        self.assertTrue(self.check(details(notes=["ACCOUNTS_NOT_COMPARED"]), index=3).ok)

    def test_a_period_without_a_report_fails_all_four(self):
        checks = scenario.period_checks(details(state="FAILED", report=False), next_period())
        self.assertFalse(any(check.ok for check in checks))

    def test_the_totals_over_the_scenario_count_what_the_next_periods_resolved(self):
        first = details(accepted=1000, journal=998, rows=1000, unknown=2, resolved=5)
        second = details(accepted=500, journal=500, rows=500, resolved=2)
        line = scenario.totals_line([first, second], next_period(resolved=0))
        self.assertIn("gateway accepted 1500, clients' journal 1498 + resolved as accepted 2 in 2 periods", line)

    def test_no_period_checked_fails(self):
        self.assertFalse(scenario.periods_count_check([]).ok)
        self.assertTrue(scenario.periods_count_check([details()]).ok)


class WaitedPeriodsTest(unittest.TestCase):
    def test_the_period_after_the_last_crossed_one_is_waited_for(self):
        self.assertEqual(scenario.waited_starts(at(4, 12), at(4, 21), 300), [at(4, 10), at(4, 15), at(4, 20), at(4, 25)])

    def test_the_deadline_is_the_verdict_deadline_after_the_end_of_the_period_after(self):
        waited = scenario.waited_starts(at(4, 12), at(4, 21), 300)
        self.assertEqual(scenario.verdicts_due(waited, 300, 330), at(4, 30) + 330 + scenario.VERDICT_MARGIN_SECONDS)


class MoneyTest(unittest.TestCase):
    def test_the_same_money_as_opened_passes(self):
        self.assertTrue(scenario.money_check(20000000000, 20000000000, 20000000000, False).ok)

    def test_a_cent_less_fails(self):
        self.assertFalse(scenario.money_check(20000000000, 19999999999, 20000000000, False).ok)

    def test_unchanged_money_that_differs_from_the_opening_total_fails(self):
        check = scenario.money_check(19999999999, 19999999999, 20000000000, False)
        self.assertFalse(check.ok)
        self.assertIn("opening total 20000000000", check.detail)

    def test_the_mutation_expects_a_cent_more_so_the_same_money_fails(self):
        check = scenario.money_check(20000000000, 20000000000, 20000000000, True)
        self.assertFalse(check.ok)
        self.assertIn("expected 20000000001", check.detail)


class SiteTest(unittest.TestCase):
    def test_the_deployed_version_passes(self):
        self.assertTrue(scenario.site_check(200, b'{"version": "v1.2.1-24-gabc"}', "v1.2.1-24-gabc").ok)

    def test_another_version_fails(self):
        check = scenario.site_check(200, b'{"version": "v1.2.1-23-gdef"}', "v1.2.1-24-gabc")
        self.assertFalse(check.ok)
        self.assertIn("version v1.2.1-23-gdef, expected v1.2.1-24-gabc", check.detail)

    def test_not_json_or_not_200_fails_without_raising(self):
        self.assertFalse(scenario.site_check(200, b"<html>", "v1").ok)
        self.assertFalse(scenario.site_check(200, b"[]", "v1").ok)
        self.assertFalse(scenario.site_check(503, b'{"version": "v1"}', "v1").ok)
        self.assertFalse(scenario.site_check(None, b"refused", None).ok)


class UidTest(unittest.TestCase):
    def pods(self, control_uid="c1", gateway_uid="g1", gateway_restarts=0):
        return [pod("ledger-0", "l0"), pod("ledger-1", "l1"), pod("gateway-x", gateway_uid, "gateway", restarts=gateway_restarts),
                pod("control-x", control_uid, "control"), pod("create-topics-x", "j1")]

    def check(self, before, after):
        return scenario.uid_check(scenario.pods_except(before, {"control"}), scenario.pods_except(after, {"control"}), "now")

    def test_a_new_control_and_the_rest_kept_passes(self):
        self.assertTrue(self.check(self.pods(), self.pods(control_uid="c2")).ok)
        self.assertNotIn("control-x", scenario.pods_except(self.pods(), {"control"}))

    def test_a_replaced_pod_fails_and_is_named(self):
        check = self.check(self.pods(), self.pods(gateway_uid="g2"))
        self.assertFalse(check.ok)
        self.assertIn("gateway-x", check.detail)

    def test_a_restarted_container_fails(self):
        check = self.check(self.pods(), self.pods(gateway_restarts=1))
        self.assertFalse(check.ok)
        self.assertIn("gateway-x", check.detail)

    def test_a_new_pod_fails(self):
        self.assertFalse(self.check(self.pods(), self.pods() + [pod("gateway-y", "g3", "gateway")]).ok)

    def test_a_terminating_pod_is_not_counted(self):
        pods = self.pods() + [pod("gateway-old", "g0", "gateway", terminating=True)]
        self.assertNotIn("gateway-old", scenario.pods_except(pods, {"control"}))

    def test_no_pods_at_all_fails(self):
        self.assertFalse(scenario.uid_check({}, {}, "now").ok)

    def test_ready_reads_the_condition(self):
        self.assertTrue(scenario.ready(pod("a", "1")))
        self.assertFalse(scenario.ready(pod("a", "1", ready=False)))
        self.assertFalse(scenario.ready({"metadata": {}, "status": {}}))


class BackTest(unittest.TestCase):
    def test_new_ready_pods_of_both_are_back(self):
        self.assertTrue(scenario.back([pod("ledger-0", "l2"), pod("gateway-y", "g2", "gateway")], {"l0", "g1"}))

    def test_a_killed_pod_still_listed_is_not_back(self):
        self.assertFalse(scenario.back([pod("ledger-0", "l0"), pod("gateway-y", "g2", "gateway")], {"l0", "g1"}))
        self.assertFalse(scenario.back([pod("ledger-0", "l2"), pod("gateway-x", "g1", "gateway")], {"l0", "g1"}))

    def test_a_pod_not_ready_or_missing_is_not_back(self):
        self.assertFalse(scenario.back([pod("ledger-0", "l2", ready=False), pod("gateway-y", "g2", "gateway")], {"l0"}))
        self.assertFalse(scenario.back([pod("gateway-y", "g2", "gateway")], {"l0"}))
        self.assertFalse(scenario.back([pod("ledger-0", "l2")], {"l0"}))


class LoadTest(unittest.TestCase):
    def test_the_steady_speed_is_the_base_with_the_profile_off(self):
        self.assertEqual(scenario.steady_tps({"baseTps": 300, "profile": {"enabled": False}}), 300)
        self.assertEqual(scenario.steady_tps({"baseTps": 300}), 300)

    def test_with_the_profile_on_the_lowest_the_profile_goes(self):
        load = {"baseTps": 400, "profile": {"enabled": True, "nightTps": 200, "dayMinTps": 300}}
        self.assertEqual(scenario.steady_tps(load), 200)

    def test_a_period_wholly_inside_needs_the_share_of_its_whole_length(self):
        least = int(scenario.MIN_LOAD_SHARE * 300 * 300)
        self.assertTrue(scenario.load_check(details(accepted=least), 300, at(4, 0), at(5, 0)).ok)
        check = scenario.load_check(details(accepted=least - 1), 300, at(4, 0), at(5, 0))
        self.assertFalse(check.ok)
        self.assertIn(f"at least {least}", check.detail)

    def test_a_period_partly_inside_needs_the_share_of_its_time_inside(self):
        least = int(scenario.MIN_LOAD_SHARE * 300 * 60)
        self.assertTrue(scenario.load_check(details(accepted=least), 300, at(4, 14), at(5, 0)).ok)
        self.assertFalse(scenario.load_check(details(accepted=least - 1), 300, at(4, 14), at(5, 0)).ok)

    def test_a_bank_without_traffic_fails_however_short_the_time_inside(self):
        self.assertFalse(scenario.load_check(details(accepted=0, journal=0, rows=0), 300, at(4, 0), at(5, 0)).ok)
        self.assertFalse(scenario.load_check(details(accepted=0, journal=0, rows=0), 300, at(4, 15), at(5, 0)).ok)

    def test_no_report_fails(self):
        self.assertFalse(scenario.load_check(details(report=False), 300, at(4, 0), at(5, 0)).ok)


class ModeTest(unittest.TestCase):
    def test_a_parent_flux_can_deploy_is_a_transition(self):
        for event in ("push", "pull_request", "schedule", "workflow_dispatch"):
            self.assertEqual(scenario.choose_mode(event, scenario.CHANGED_SOMETHING, True), scenario.MODE_TRANSITION)

    def test_a_push_with_nothing_to_deploy_is_skipped(self):
        self.assertEqual(scenario.choose_mode("push", scenario.CHANGED_NOTHING, True), scenario.MODE_SKIP)
        self.assertEqual(scenario.choose_mode("push", scenario.CHANGED_NOTHING, False), scenario.MODE_SKIP)

    def test_elsewhere_nothing_to_deploy_is_still_a_transition_for_control(self):
        for event in ("schedule", "workflow_dispatch", "pull_request"):
            self.assertEqual(scenario.choose_mode(event, scenario.CHANGED_NOTHING, True), scenario.MODE_TRANSITION)

    def test_over_a_parent_from_before_flux_the_commit_itself_is_installed(self):
        self.assertEqual(scenario.choose_mode("pull_request", scenario.CHANGED_SOMETHING, False), scenario.MODE_INSTALL)
        self.assertEqual(scenario.choose_mode("schedule", scenario.CHANGED_NOTHING, False), scenario.MODE_INSTALL)

    def test_a_failed_changed_sh_is_an_error(self):
        for code in (1, 2, 128):
            with self.assertRaises(ValueError):
                scenario.choose_mode("push", code, True)

    def test_the_command_prints_the_mode(self):
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            code = scenario.main(["mode", "--event", "push", "--changed", "0", "--base-flux", "yes"])
        self.assertEqual((code, output.getvalue().strip()), (0, scenario.MODE_TRANSITION))
        with contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(scenario.main(["mode", "--event", "push", "--changed", "2", "--base-flux", "yes"]), 1)


class ChartChangedTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        (self.root / scenario.CHART).mkdir(parents=True)
        (self.root / scenario.CHART / "values.yaml").write_text("a: 1\n")
        (self.root / "README").write_text("readme\n")
        self.base = self.commit("base")

    def tearDown(self):
        self.temp.cleanup()

    def git(self, *argv):
        return subprocess.run(("git", "-C", str(self.root)) + argv, check=True, capture_output=True, text=True).stdout

    def commit(self, message):
        self.git("init", "-q")
        self.git("add", "-A")
        self.git("-c", "user.name=t", "-c", "user.email=t@example.invalid", "commit", "--allow-empty",
                 "-qm", message)
        return self.git("rev-parse", "HEAD").strip()

    def test_a_file_outside_the_chart_is_not_the_chart(self):
        (self.root / "README").write_text("changed\n")
        self.assertFalse(scenario.chart_changed(self.base, self.commit("readme"), self.root))

    def test_a_changed_chart_file(self):
        (self.root / scenario.CHART / "values.yaml").write_text("a: 2\n")
        self.assertTrue(scenario.chart_changed(self.base, self.commit("chart"), self.root))

    def test_the_working_tree_is_not_the_commit(self):
        head = self.commit("same")
        (self.root / scenario.CHART / "values.yaml").write_text("a: 3\n")
        self.assertFalse(scenario.chart_changed(self.base, head, self.root))


class SteadyTest(unittest.TestCase):
    """A check window of 240 s at 300 TPS: steady from 90 % of 72 000 accepted with every guarantee at 0."""

    KEPT = {">3s": 0, "503": 0, "pool timeouts": 0}

    def test_the_window_is_the_slowest_rollout_and_the_settling(self):
        values = {"releaseCheck": {"settleSeconds": 120},
                  "services": {service: {"rolloutSeconds": 90} for service in scenario.SERVICES}}
        values["services"]["gateway"]["rolloutSeconds"] = 150
        self.assertEqual(scenario.check_window(values), 270)

    def test_a_bank_at_its_speed_that_kept_the_guarantee_is_steady(self):
        ok, detail = scenario.steady_state(self.KEPT, 64800, 300, 240)
        self.assertTrue(ok, detail)

    def test_a_transfer_slower_than_3_s_in_the_window_is_not_steady(self):
        for name in self.KEPT:
            ok, detail = scenario.steady_state(dict(self.KEPT, **{name: 1}), 72000, 300, 240)
            self.assertFalse(ok)
            self.assertIn(name, detail)

    def test_a_bank_still_gathering_speed_is_not_steady(self):
        ok, detail = scenario.steady_state(self.KEPT, 64799, 300, 240)
        self.assertFalse(ok)
        self.assertIn("at least 64800", detail)


COMMIT = "0123456789abcdef0123456789abcdef01234567"
OTHER_COMMIT = "fedcba9876543210fedcba9876543210fedcba98"


def stamp(seconds):
    return datetime.fromtimestamp(seconds, timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def release(name, commit=COMMIT, ready="True", deployed=0, ready_at=0, tag=None, checked=False, hooks=None,
            conditions=()):
    values = {"component": name}
    if tag:
        values["services"] = {name: {"tag": tag}}
    if checked:
        values["releaseCheck"] = {"enabled": True}
    snapshot = {"chartVersion": f"0.1.0+{commit[:12]}.1", "lastDeployed": stamp(deployed)}
    if hooks is not None:
        snapshot["testHooks"] = {f"release-check-{name}": {"phase": phase} for phase in hooks}
    return {"metadata": {"name": name}, "spec": {"values": values},
            "status": {"history": [snapshot],
                       "conditions": [{"type": "Ready", "status": ready, "lastTransitionTime": stamp(ready_at)},
                                      *conditions]}}


def chain(step=60, **overrides):
    """The six releases deployed one after another, each ready `step` seconds after its deploy."""
    releases = []
    for index, name in enumerate(scenario.CHAIN):
        fields = dict(deployed=START + index * step, ready_at=START + (index + 1) * step)
        fields.update(overrides.get(name, {}))
        releases.append(release(name, **fields))
    return releases


class ChainTest(unittest.TestCase):
    def test_a_release_ready_on_the_commit_s_chart_has_reached_it(self):
        self.assertTrue(scenario.on_commit(release("gateway"), COMMIT))

    def test_a_release_ready_on_another_commit_has_not(self):
        self.assertFalse(scenario.on_commit(release("gateway", commit=OTHER_COMMIT), COMMIT))

    def test_a_release_on_the_commit_whose_test_still_runs_has_not(self):
        self.assertFalse(scenario.on_commit(release("gateway", ready="Unknown"), COMMIT))
        self.assertFalse(scenario.on_commit(release("gateway", ready="False"), COMMIT))

    def test_a_release_never_installed_has_not(self):
        self.assertFalse(scenario.on_commit({"metadata": {"name": "gateway"}}, COMMIT))

    def test_the_chain_has_reached_the_commit_when_all_six_have(self):
        self.assertEqual(scenario.reached(chain(), COMMIT), list(scenario.CHAIN))
        self.assertEqual(scenario.reached(chain(control={"ready": "Unknown"}), COMMIT), list(scenario.CHAIN[:-1]))
        self.assertEqual(scenario.reached(chain()[:3], COMMIT), list(scenario.CHAIN[:3]))

    def stopped(self, kind, status, moment):
        return {"type": kind, "status": status, "reason": "RollbackSucceeded", "message": "rolled back",
                "lastTransitionTime": stamp(moment)}

    def test_a_release_rolled_back_since_the_pointer_moved_stops_the_chain(self):
        releases = chain(gateway={"ready": "False", "conditions": [self.stopped("Remediated", "True", START + 5)]})
        failure = scenario.chain_failure(releases, START)
        self.assertIn("gateway: Remediated", failure)
        self.assertIn("rolled back", failure)

    def test_a_stalled_release_and_a_failed_test_stop_the_chain(self):
        for kind, status in (("Stalled", "True"), ("TestSuccess", "False")):
            releases = chain(ledger={"conditions": [self.stopped(kind, status, START)]})
            self.assertIn(f"ledger: {kind}", scenario.chain_failure(releases, START))

    def test_a_rollback_of_an_earlier_deploy_does_not(self):
        releases = chain(gateway={"conditions": [self.stopped("Remediated", "True", START - 1)]})
        self.assertIsNone(scenario.chain_failure(releases, START))

    def test_a_passed_test_and_a_running_chain_do_not(self):
        releases = chain(gateway={"conditions": [self.stopped("TestSuccess", "True", START + 5),
                                                 self.stopped("Stalled", "False", START + 5)]})
        self.assertIsNone(scenario.chain_failure(releases, START))


class OrderTest(unittest.TestCase):
    """The table's row «К4 по одному»: Ready along the chain, no two services replaced at the same time."""

    def test_each_release_deployed_after_the_one_before_was_ready_passes(self):
        check = scenario.order_check(chain())
        self.assertTrue(check.ok, check.detail)

    def test_a_release_deployed_the_second_the_one_before_became_ready_passes(self):
        self.assertTrue(scenario.order_check(chain(step=0)).ok)

    def test_a_gateway_deployed_while_the_ledger_was_not_ready_fails_and_is_named(self):
        check = scenario.order_check(chain(gateway={"deployed": START + 1}))
        self.assertFalse(check.ok)
        self.assertIn("gateway deployed", check.detail)
        self.assertIn("before ledger was ready", check.detail)

    def test_a_release_missing_from_the_chain_fails(self):
        check = scenario.order_check([each for each in chain() if each["metadata"]["name"] != "ledger"])
        self.assertFalse(check.ok)
        self.assertIn("ledger", check.detail)

    def test_a_release_without_history_fails(self):
        releases = chain()
        releases[3]["status"]["history"] = []
        self.assertFalse(scenario.order_check(releases).ok)


class ReleaseCheckTest(unittest.TestCase):
    """«release-check прошёл»: given to the services release.sh must give it to, and each one's test hook succeeded."""

    def names(self, checks):
        return [check.name for check in checks if not check.ok]

    def test_the_tags_are_read_from_each_service_s_release(self):
        tags = scenario.tags_of(chain(gateway={"tag": "g1"}, ledger={"tag": "l1"}))
        self.assertEqual((tags["gateway"], tags["ledger"], tags["control"]), ("g1", "l1", None))

    def test_the_services_whose_tag_differs_are_the_updated_ones(self):
        before = {"ledger": "l1", "gateway": "g1", "generator": "n1", "control": "c1", "reporting": "r1"}
        self.assertEqual(scenario.updated_services(before, dict(before, gateway="g2", ledger="l2")), {"gateway", "ledger"})
        self.assertEqual(scenario.updated_services(before, before), set())

    def test_a_changed_chart_gives_the_check_to_every_service(self):
        self.assertEqual(scenario.expected_checked({"gateway"}, True), set(scenario.SERVICES))
        self.assertEqual(scenario.expected_checked({"gateway"}, False), {"gateway"})
        self.assertEqual(scenario.expected_checked(set(), False), set())

    def test_the_check_given_to_the_changed_service_and_passed_passes_both(self):
        checks = scenario.release_check_checks(chain(gateway={"checked": True, "hooks": ["Succeeded"]}), {"gateway"})
        self.assertEqual(self.names(checks), [])
        self.assertIn("gateway", checks[1].detail)

    def test_a_changed_service_without_the_check_fails(self):
        checks = scenario.release_check_checks(chain(), {"gateway"})
        self.assertEqual(self.names(checks), ["release check given to the services that changed"])

    def test_the_check_given_to_a_service_that_did_not_change_fails(self):
        releases = chain(gateway={"checked": True, "hooks": ["Succeeded"]},
                         reporting={"checked": True, "hooks": ["Succeeded"]})
        self.assertEqual(self.names(scenario.release_check_checks(releases, {"gateway"})),
                         ["release check given to the services that changed"])

    def test_a_failed_hook_fails(self):
        checks = scenario.release_check_checks(chain(gateway={"checked": True, "hooks": ["Failed"]}), {"gateway"})
        self.assertEqual(self.names(checks), ["release check passed"])
        self.assertIn("Failed", checks[1].detail)

    def test_a_check_given_whose_test_never_ran_fails(self):
        for hooks in (None, []):
            checks = scenario.release_check_checks(chain(gateway={"checked": True, "hooks": hooks}), {"gateway"})
            self.assertEqual(self.names(checks), ["release check passed"])

    def test_nothing_changed_and_nothing_given_passes(self):
        self.assertEqual(self.names(scenario.release_check_checks(chain(), set())), [])


class ReplacedTest(unittest.TestCase):
    def pods(self, control="c1", gateway=("g1",)):
        return ([pod("control-x", control, "control"), pod("ledger-0", "l0", "ledger")]
                + [pod(f"gateway-{uid}", uid, "gateway") for uid in gateway])

    def test_new_pods_of_every_changed_service_pass(self):
        check = scenario.replaced_check(self.pods(), self.pods(control="c2", gateway=("g2",)), {"control", "gateway"})
        self.assertTrue(check.ok, check.detail)

    def test_a_changed_service_that_kept_a_pod_fails_and_is_named(self):
        check = scenario.replaced_check(self.pods(), self.pods(control="c2", gateway=("g1", "g2")), {"control", "gateway"})
        self.assertFalse(check.ok)
        self.assertIn("gateway", check.detail)
        self.assertNotIn("control", check.detail)

    def test_a_changed_service_without_pods_fails(self):
        self.assertFalse(scenario.replaced_check(self.pods(), self.pods(gateway=()), {"gateway"}).ok)

    def test_control_not_replaced_fails(self):
        self.assertFalse(scenario.replaced_check(self.pods(), self.pods(), {"control"}).ok)


ACCEPTED = 90000
TPS = 300
CHAIN_STEP_SECONDS = 40
BASE_TAGS = {"ledger": "l1", "gateway": "g1", "generator": "n1", "control": "c1", "reporting": "r1"}
BASE_COMMIT = OTHER_COMMIT
OTHER_PODS = (("kafka-0", "kafka"), ("postgres-0", "postgres"))


class Result:
    def __init__(self, returncode=0, stdout="", stderr=""):
        self.returncode, self.stdout, self.stderr = returncode, stdout, stderr


class FakeTime:
    """The scenario's clock: sleeping moves it on and lets a test change the cluster meanwhile."""

    def __init__(self, now):
        self.now = now
        self.on_sleep = []

    def time(self):
        return self.now

    def sleep(self, seconds):
        self.now += seconds
        for hook in self.on_sleep:
            hook(seconds)

    def gmtime(self, seconds=None):
        return time.gmtime(self.now if seconds is None else seconds)

    def strftime(self, pattern, moment):
        return time.strftime(pattern, moment)


class FakeCluster:
    """The bank as the scenario sees it: its pods, releases, Prometheus, money and control's periods. Moving the
    pointer deploys the six releases one after another, CHAIN_STEP_SECONDS each, as time passes; a release replaces
    the pods of its service when the tag changed, and control's for its version."""

    namespace = "ledger-lab"

    def __init__(self, clock, commit, tags):
        self.clock = clock
        self.tags = dict(tags)
        self.commit = commit
        self.next_uid = 0
        self.pods_now = []
        for service in scenario.SERVICES:
            self.start_pods(service)
        for name, service in OTHER_PODS:
            self.pods_now.append(pod(name, self.uid(), service))
        self.state_now = {name: self.release_of(name, commit, clock.now - 600 + index * CHAIN_STEP_SECONDS,
                                                clock.now - 600 + (index + 1) * CHAIN_STEP_SECONDS, False)
                          for index, name in enumerate(scenario.CHAIN)}
        self.pending = []
        self.accepted = ACCEPTED
        self.state = "RECONCILED"
        self.delete_fails = False
        self.also_replaced = set()
        self.slow_until = 0
        self.fails_at = None
        self.unchecked = set()
        self.early = None
        self.queries = []
        self.events = []

    def uid(self):
        self.next_uid += 1
        return f"u{self.next_uid}"

    def start_pods(self, service):
        names = ("ledger-1", "ledger-0") if service == scenario.LEDGER else (f"{service}-{self.next_uid}",)
        self.pods_now = [p for p in self.pods_now if p["metadata"]["labels"].get(scenario.NAME_LABEL) != service]
        self.pods_now += [pod(name, self.uid(), service) for name in names]

    def release_of(self, name, commit, deployed, ready_at, checked, ready="True", conditions=()):
        return release(name, commit=commit, ready=ready, deployed=deployed, ready_at=ready_at, tag=self.tags.get(name),
                       checked=checked, hooks=["Succeeded"] if checked else None, conditions=conditions)

    def move_pointer(self, commit, tags, chart_differs):
        """What flux.sh and Flux do: the releases get their new values at once, and each its turn in the chain."""
        self.events.append(("pointer", self.clock.now))
        old, self.tags, self.commit = self.tags, dict(tags), commit
        moment = self.clock.now
        for name in scenario.CHAIN:
            changed = name in old and old[name] != tags[name]
            checked = (changed or (chart_differs and name in old)) and name not in self.unchecked
            deployed = moment if name != self.early else self.clock.now
            moment += CHAIN_STEP_SECONDS
            self.state_now[name]["spec"] = self.release_of(name, commit, 0, 0, checked)["spec"]
            self.pending.append((name, deployed, moment, changed or name == scenario.CONTROL, checked))

    def advance(self):
        while self.pending and self.pending[0][2] <= self.clock.now:
            name, deployed, ready_at, replaces, checked = self.pending.pop(0)
            if name == self.fails_at:
                failed = {"type": "Remediated", "status": "True", "reason": "RollbackSucceeded",
                          "message": "release-check failed", "lastTransitionTime": stamp(ready_at)}
                self.state_now[name]["status"]["conditions"] = [
                    {"type": "Ready", "status": "False", "lastTransitionTime": stamp(ready_at)}, failed]
                self.pending = []
                return
            self.state_now[name] = self.release_of(name, self.commit, deployed, ready_at, checked)
            if replaces:
                self.start_pods(name)
            for service in self.also_replaced:
                self.start_pods(service)
            self.also_replaced = set()

    def restart(self, name):
        for each in self.pods_now:
            if each["metadata"]["name"] == name:
                each["status"]["containerStatuses"][0]["restartCount"] += 1

    def require_k3d(self):
        pass

    def values(self):
        return {"periodMinutes": 5, "closing": {"verdictDeadline": "330s"}, "load": {"baseTps": TPS},
                "releaseCheck": {"settleSeconds": 120},
                "services": {service: {"rolloutSeconds": 120} for service in scenario.SERVICES}}

    def releases(self):
        self.advance()
        return json.loads(json.dumps(list(self.state_now.values())))

    def pods(self):
        self.advance()
        return json.loads(json.dumps(self.pods_now))

    def query(self, promql, at):
        if not self.queries:
            self.events.append(("first question to Prometheus", self.clock.now))
        self.queries.append(promql)
        if "accepted" in promql:
            return float(TPS * 240)
        return 7.0 if "le=" in promql and self.clock.now < self.slow_until else 0.0

    def money(self):
        return 20000000000, 20000000000

    def kubectl(self, *argv, check=True):
        if argv[:2] == ("delete", "pod"):
            self.events.append(("crash", self.clock.now))
            if self.delete_fails:
                raise scenario.ScenarioError("kubectl delete pod: exit 1: forbidden")
            victims = [name for name in argv[2:] if not name.startswith("-")]
            for each in [p for p in self.pods_now if p["metadata"]["name"] in victims]:
                self.pods_now.remove(each)
                service = each["metadata"]["labels"][scenario.NAME_LABEL]
                name = each["metadata"]["name"] if service == scenario.LEDGER else f"{service}-{self.next_uid}"
                self.pods_now.append(pod(name, self.uid(), service))
        return Result()

    def command(self, *argv, check=True, stdin=None):
        return Result()

    def get(self, path):
        if path == "/api/site":
            return 200, json.dumps({"version": f"v-{self.commit[:7]}"}).encode()
        if path.startswith("/api/bank/periods?"):
            starts = range(int(START // 300) * 300 - 600, int(self.clock.now) + 900, 300)
            periods = [{"start": stamp(start), "state": self.state} for start in starts]
            return 200, json.dumps({"periods": periods}).encode()
        if path.startswith("/api/bank/periods/"):
            start = scenario.epoch(path.rsplit("/", 1)[1])
            return 200, json.dumps(details(accepted=self.accepted, journal=self.accepted, rows=self.accepted,
                                           start=stamp(start), end=stamp(start + 300))).encode()
        return 200, b"<html>"

    def get_json(self, path):
        status, body = self.get(path)
        return json.loads(body)


class FakeProcess:
    def __init__(self, lines):
        self.stdout = iter(lines)

    def wait(self):
        return 0


class FakeSubprocess:
    """git and flux.sh: ONLY_IMAGES=1 takes a while and moves nothing, the other call moves the pointer."""

    PIPE = subprocess.PIPE
    STDOUT = subprocess.STDOUT
    IMAGES_SECONDS = 90

    def __init__(self, cluster, clock, head):
        self.cluster, self.clock, self.head = cluster, clock, head
        self.chart_differs = False
        self.flux_calls = []

    def run(self, argv, **_):
        if argv[0] == "git" and "rev-parse" in argv:
            return Result(stdout=COMMIT + "\n")
        if argv[0] == "git" and "describe" in argv:
            return Result(stdout=f"v-{argv[-1][:7]}\n")
        if argv[0] == "git" and "diff" in argv:
            return Result(returncode=1 if self.chart_differs else 0)
        raise AssertionError(f"unexpected command {argv}")

    def Popen(self, argv, env, **_):
        if argv != [str(scenario.FLUX), COMMIT]:
            raise AssertionError(f"unexpected command {argv}")
        only_images = env.get(scenario.ONLY_IMAGES_ENV) == "1"
        self.flux_calls.append(("images" if only_images else "pointer", env.get(scenario.IMAGES_SOURCE_ENV)))
        if only_images:
            self.clock.now += self.IMAGES_SECONDS
            self.cluster.events.append(("images", self.clock.now))
            return FakeProcess(["images in the node\n"])
        self.cluster.move_pointer(COMMIT, self.head, self.chart_differs)
        return FakeProcess(["pointer moved\n"])


class ScenarioRunTest(unittest.TestCase):
    """Scenario.run against a fake bank that runs the base commit at 04:12: the gateway's image changes, the images
    take 90 s and the chain 240 s, so the periods 04:10 and 04:15 are checked and 04:20 resolves the last one's
    unknown transfers."""

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.clock = FakeTime(START)
        self.cluster = FakeCluster(self.clock, BASE_COMMIT, BASE_TAGS)
        self.head = dict(BASE_TAGS, gateway="g2")
        self.fake_subprocess = FakeSubprocess(self.cluster, self.clock, self.head)
        self.saved = scenario.time, scenario.subprocess, os.environ.pop(scenario.IMAGES_SOURCE_ENV, None)
        scenario.time, scenario.subprocess = self.clock, self.fake_subprocess

    def tearDown(self):
        scenario.time, scenario.subprocess, source = self.saved
        if source is not None:
            os.environ[scenario.IMAGES_SOURCE_ENV] = source
        self.temp.cleanup()

    def run_scenario(self, base=BASE_COMMIT):
        args = argparse.Namespace(cluster="ledger-lab", namespace="ledger-lab", control_url="http://control",
                                  artifacts=self.temp.name, base=base, commit="HEAD")
        run = scenario.Scenario(args)
        run.k = self.cluster
        with contextlib.redirect_stdout(io.StringIO()):
            code = run.run()
        return code, run.checks

    def failed(self, checks):
        return [check.name for check in checks if not check.ok]

    def detail(self, checks, name):
        return [check.detail for check in checks if check.name == name][0]

    def restart_at_late_snapshot(self, name="kafka-0"):
        def hook(seconds):
            if seconds == scenario.LATE_SNAPSHOT_SECONDS:
                self.cluster.restart(name)
        self.clock.on_sleep.append(hook)

    def test_a_clean_deploy_over_the_base_passes_and_checks_every_period(self):
        code, checks = self.run_scenario()
        self.assertEqual(code, 0, [check.line() for check in checks if not check.ok])
        names = [check.name for check in checks]
        self.assertEqual([name for name in names if name.endswith(" verdict")],
                         ["period 04:10-04:15 verdict", "period 04:15-04:20 verdict"])
        for name in ("each release deployed after the one before it was ready",
                     "release check given to the services that changed", "release check passed",
                     "the deploy replaced the pods of the services it changed", "periods checked",
                     "killed ledger-0 and the gateway came back", "money on the accounts unchanged", "/api/site"):
            self.assertIn(name, names)
        self.assertEqual(len([name for name in names if name.startswith("deploy left every pod")]), 2)
        self.assertIn("['gateway']", self.detail(checks, "release check passed"))
        self.assertIn("['control', 'gateway']", self.detail(checks, "the deploy replaced the pods of the services it changed"))

    def test_the_images_come_before_the_steady_bank_and_the_pointer_takes_them_from_the_node(self):
        self.run_scenario()
        self.assertEqual(self.fake_subprocess.flux_calls, [("images", None), ("pointer", scenario.IMAGES_ANY)])
        self.assertEqual([event for event, _ in self.cluster.events][:3],
                         ["images", "first question to Prometheus", "pointer"])

    def test_the_pointer_moves_only_once_the_bank_has_kept_its_guarantee_for_a_check_s_window(self):
        self.cluster.slow_until = START + 400
        code, checks = self.run_scenario()
        self.assertEqual(code, 0, [check.line() for check in checks if not check.ok])
        moved = [moment for event, moment in self.cluster.events if event == "pointer"]
        self.assertEqual(len(moved), 1)
        self.assertGreaterEqual(moved[0], START + 400)
        self.assertTrue(any("[240s]" in query for query in self.cluster.queries))

    def test_a_bank_that_never_gets_steady_fails_and_the_pointer_stays(self):
        self.cluster.slow_until = START + 10 * scenario.STEADY_TIMEOUT_SECONDS
        code, checks = self.run_scenario()
        self.assertEqual(code, 1)
        self.assertIn("scenario", self.failed(checks))
        self.assertIn("not steady", self.detail(checks, "scenario"))
        self.assertNotIn("pointer", dict(self.cluster.events))

    def test_the_pods_are_killed_after_the_whole_chain_and_its_checks(self):
        self.run_scenario()
        events = dict(self.cluster.events)
        self.assertGreaterEqual(events["crash"], events["pointer"] + len(scenario.CHAIN) * CHAIN_STEP_SECONDS)

    def test_a_release_rolled_back_fails_the_run_at_once_and_names_it(self):
        self.cluster.fails_at = "gateway"
        code, checks = self.run_scenario()
        self.assertEqual(code, 1)
        self.assertIn("scenario", self.failed(checks))
        self.assertIn("the chain stopped at gateway", self.detail(checks, "scenario"))
        self.assertIn("release-check failed", self.detail(checks, "scenario"))
        self.assertNotIn("crash", dict(self.cluster.events))

    def test_a_changed_service_left_without_its_release_check_fails(self):
        self.cluster.unchecked = {"gateway"}
        code, checks = self.run_scenario()
        self.assertEqual(code, 1)
        self.assertEqual(self.failed(checks), ["release check given to the services that changed"])

    def test_a_release_deployed_beside_the_one_before_it_fails(self):
        self.cluster.early = "gateway"
        code, checks = self.run_scenario()
        self.assertEqual(code, 1)
        self.assertEqual(self.failed(checks), ["each release deployed after the one before it was ready"])

    def test_a_changed_chart_gives_every_service_the_check_and_compares_no_other_pod(self):
        self.fake_subprocess.chart_differs = True
        self.cluster.also_replaced = {"kafka"}
        code, checks = self.run_scenario()
        self.assertEqual(code, 0, [check.line() for check in checks if not check.ok])
        self.assertIn(str(sorted(scenario.SERVICES)), self.detail(checks, "release check passed"))
        self.assertEqual([check.name for check in checks if check.name.startswith("deploy left every pod")], [])

    def test_a_pod_the_deploy_should_leave_replaced_fails(self):
        self.cluster.also_replaced = {"kafka"}
        code, checks = self.run_scenario()
        self.assertEqual(code, 1)
        self.assertIn("deploy left every pod of the services it did not change as it was (at its end)",
                      self.failed(checks))

    def test_a_pod_restarted_after_the_deploy_fails_later(self):
        self.restart_at_late_snapshot()
        code, checks = self.run_scenario()
        self.assertEqual(code, 1)
        self.assertEqual(self.failed(checks), [
            "deploy left every pod of the services it did not change as it was "
            f"({scenario.LATE_SNAPSHOT_SECONDS}s later)"])

    def test_the_commit_installed_without_a_base_passes_with_its_chain_s_order(self):
        self.cluster = FakeCluster(self.clock, COMMIT, self.head)
        code, checks = self.run_scenario(base=None)
        self.assertEqual(code, 0, [check.line() for check in checks if not check.ok])
        self.assertIn("each release deployed after the one before it was ready", [check.name for check in checks])
        self.assertEqual(self.fake_subprocess.flux_calls, [])

    def test_without_a_base_a_cluster_on_another_commit_fails(self):
        code, checks = self.run_scenario(base=None)
        self.assertEqual(code, 1)
        self.assertIn("does not run", self.detail(checks, "scenario"))

    def test_a_failed_stage_fails_the_run(self):
        self.cluster.delete_fails = True
        code, checks = self.run_scenario()
        self.assertEqual(code, 1)
        self.assertEqual(self.failed(checks), ["scenario"])
        self.assertIn("forbidden", self.detail(checks, "scenario"))

    def test_the_page_on_another_version_than_the_commit_s_fails(self):
        self.cluster = FakeCluster(self.clock, BASE_COMMIT, self.head)
        code, checks = self.run_scenario(base=None)
        self.assertIn("/api/site", self.failed(checks))

    def test_a_bank_without_traffic_fails(self):
        self.cluster.accepted = 0
        code, checks = self.run_scenario()
        self.assertEqual(code, 1)
        self.assertEqual(self.failed(checks), ["period 04:10-04:15 under load", "period 04:15-04:20 under load"])

    def test_verdicts_never_final_fail_after_the_deadline(self):
        self.cluster.state = "CLOSING"
        code, checks = self.run_scenario()
        self.assertEqual(code, 1)
        self.assertEqual(self.failed(checks), ["verdicts"])

    def test_the_mutation_of_the_sum_fails_exactly_one_check(self):
        os.environ[scenario.MUTATE_ENV] = "1"
        try:
            code, checks = self.run_scenario()
        finally:
            del os.environ[scenario.MUTATE_ENV]
        self.assertEqual(code, 1)
        self.assertEqual(self.failed(checks), ["money on the accounts unchanged"])


if __name__ == "__main__":
    unittest.main()

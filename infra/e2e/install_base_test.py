#!/usr/bin/env python3
"""Tests of install-base.sh in a temporary repository whose cluster.sh, flux.sh, scenario.py and kubectl only write
down how they were called: python3 -m unittest discover -s infra/e2e -p '*_test.py'"""

import base64
import os
import shutil
import subprocess
import tempfile
import time
import unittest
from pathlib import Path

INSTALL_BASE = Path(__file__).resolve().parent / "install-base.sh"
VALUES = "periodMinutes: 5\n"
RECORDER = """#!/bin/sh
echo "{name} $* WAIT=${{WAIT:-}} FLUX_INSTALL_TIMEOUT=${{FLUX_INSTALL_TIMEOUT:-}} CHAIN=${{CHAIN_TIMEOUT_SECONDS:-}}" >>"$CALLS"
if [ -f "$CALLS.{name}-hangs" ]; then sleep {hang}; echo "{name} returned" >>"$CALLS"; fi
[ ! -f "$CALLS.{name}-fails" ]
"""
HANG_SECONDS = 20
KUBECTL = """#!/bin/sh
case "$*" in
*"get helmrelease/control"*) [ -f "$CALLS.running" ] ;;
*"get secret"*) [ -f "$CALLS.secret" ] && cat "$CALLS.secret" ;;
*"create secret"*) echo "secret $*" ;;
*"create namespace"*) echo "namespace $*" ;;
*"apply -f -"*) echo "applied $(cat)" >>"$CALLS" ;;
esac
"""


class InstallBaseTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name) / "repo"
        self.calls_file = Path(self.temp.name) / "calls"
        for path, text in (("infra/flux/release.sh", "release\n"), ("infra/e2e/values.yaml", VALUES)):
            (self.root / path).parent.mkdir(parents=True, exist_ok=True)
            (self.root / path).write_text(text)
        self.git("init", "-q")
        self.base = self.commit("base")
        shutil.copy(INSTALL_BASE, self.root / "infra" / "e2e" / "install-base.sh")
        bin_dir = Path(self.temp.name) / "bin"
        for path, text in ((self.root / "infra" / "k3d" / "cluster.sh", self.recorder("cluster")),
                           (self.root / "infra" / "k3d" / "flux.sh", self.recorder("flux")),
                           (self.root / "infra" / "e2e" / "scenario.py", self.recorder("scenario")),
                           (bin_dir / "kubectl", KUBECTL)):
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text)
            path.chmod(0o755)
        self.env = dict(os.environ, CALLS=str(self.calls_file), PATH=f"{bin_dir}:{os.environ['PATH']}")
        self.env.pop("FLUX_INSTALL_TIMEOUT", None)
        self.env.pop("CHAIN_TIMEOUT_SECONDS", None)
        self.env.pop("E2E_DEADLINE", None)

    @staticmethod
    def recorder(name):
        return RECORDER.format(name=name, hang=HANG_SECONDS)

    def tearDown(self):
        self.temp.cleanup()

    def git(self, *argv):
        return subprocess.run(("git", "-C", str(self.root)) + argv, check=True, capture_output=True, text=True).stdout

    def commit(self, message):
        self.git("add", "-A")
        self.git("-c", "user.name=t", "-c", "user.email=t@example.invalid", "commit", "-qm", message)
        return self.git("rev-parse", "HEAD").strip()

    def install(self, *argv):
        return subprocess.run([str(self.root / "infra" / "e2e" / "install-base.sh"), *argv], capture_output=True,
                              text=True, env=self.env)

    def calls(self, name=""):
        lines = self.calls_file.read_text().splitlines() if self.calls_file.exists() else []
        return [line for line in lines if line.startswith(name)]

    def running(self, values=VALUES):
        Path(f"{self.calls_file}.running").touch()
        Path(f"{self.calls_file}.secret").write_text(base64.b64encode(values.encode()).decode())

    def test_a_new_cluster_gets_the_end_to_end_values_as_the_releases_secret_before_flux_deploys_the_commit(self):
        result = self.install("HEAD")
        self.assertEqual(result.returncode, 0, result.stderr)
        order = [line.split()[0] for line in self.calls()]
        self.assertEqual(order, ["cluster", "applied", "applied", "flux"])
        secret = self.calls("applied secret")[0]
        self.assertIn("ledger-lab-values", secret)
        self.assertIn(f"--from-file=values.yaml={self.root}/infra/e2e/values.yaml", secret)
        self.assertEqual(self.calls("scenario"), [])

    def test_flux_gets_the_commit_waits_for_the_chain_within_a_limit_and_gives_a_new_node_time_for_its_images(self):
        self.install("HEAD")
        self.assertEqual(self.calls("flux"), [f"flux {self.base} WAIT=1 FLUX_INSTALL_TIMEOUT=15m CHAIN=1800"])

    def test_a_timeout_set_by_the_caller_is_kept(self):
        self.env["FLUX_INSTALL_TIMEOUT"] = "7m"
        self.install("HEAD")
        self.assertIn("FLUX_INSTALL_TIMEOUT=7m", self.calls("flux")[0])

    def test_on_a_running_bank_the_pointer_moves_only_after_the_bank_is_steady(self):
        self.running()
        result = self.install("HEAD")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual([line.split()[0] for line in self.calls()], ["cluster", "scenario", "flux"])
        self.assertTrue(self.calls("scenario")[0].startswith(f"scenario steady --commit {self.base} "))

    def test_a_bank_that_does_not_get_steady_is_not_deployed_to(self):
        self.running()
        Path(f"{self.calls_file}.scenario-fails").touch()
        self.assertNotEqual(self.install("HEAD").returncode, 0)
        self.assertEqual(self.calls("flux"), [])

    def test_a_running_bank_with_other_values_is_refused(self):
        self.running("periodMinutes: 60\n")
        result = self.install("HEAD")
        self.assertEqual(result.returncode, 1)
        self.assertIn("other values", result.stderr)
        self.assertEqual(self.calls("flux") + self.calls("scenario") + self.calls("applied"), [])

    def test_an_install_still_waiting_when_the_run_s_time_ends_is_stopped_then_and_says_so(self):
        Path(f"{self.calls_file}.flux-hangs").touch()
        self.env["E2E_DEADLINE"] = str(int(time.time()) + 2)
        started = time.time()
        result = self.install("HEAD")
        self.assertLess(time.time() - started, HANG_SECONDS / 2)
        self.assertEqual(result.returncode, 124)
        self.assertIn(f"did not reach {self.base} before the run's time ran out (E2E_DEADLINE)", result.stderr)
        self.assertEqual(self.calls("flux"), [f"flux {self.base} WAIT=1 FLUX_INSTALL_TIMEOUT=15m CHAIN=1800"])

    def test_with_the_run_s_time_already_over_the_pointer_is_not_moved(self):
        self.env["E2E_DEADLINE"] = str(int(time.time()) - 1)
        result = self.install("HEAD")
        self.assertEqual(result.returncode, 1)
        self.assertIn("ran out before the pointer was moved", result.stderr)
        self.assertEqual(self.calls("flux"), [])

    def test_within_the_run_s_time_the_install_ends_as_flux_sh_ends(self):
        self.env["E2E_DEADLINE"] = str(int(time.time()) + 600)
        self.assertEqual(self.install("HEAD").returncode, 0)
        self.assertEqual(self.calls("flux"), [f"flux {self.base} WAIT=1 FLUX_INSTALL_TIMEOUT=15m CHAIN=1800"])
        Path(f"{self.calls_file}.flux-fails").touch()
        self.assertEqual(self.install("HEAD").returncode, 1)

    def test_a_commit_from_before_flux_is_refused(self):
        self.git("rm", "-q", "infra/flux/release.sh")
        old = self.commit("old")
        result = self.install(old)
        self.assertEqual(result.returncode, 2)
        self.assertIn("too old", result.stderr)
        self.assertEqual(self.calls(), [])

    def test_no_such_commit_is_refused(self):
        self.assertEqual(self.install("nothing").returncode, 2)
        self.assertEqual(self.install().returncode, 2)
        self.assertEqual(self.calls(), [])


if __name__ == "__main__":
    unittest.main()

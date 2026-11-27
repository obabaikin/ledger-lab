#!/usr/bin/env python3
"""Tests of infra/k3d/images.sh with docker, k3d and the tree's gradlew as stubs on PATH:
python3 -m unittest discover -s infra/e2e -p '*_test.py'"""

import os
import subprocess
import tempfile
import unittest
from pathlib import Path

IMAGES = Path(__file__).resolve().parents[2] / "infra" / "k3d" / "images.sh"
MODULE_TAG = Path(__file__).resolve().parents[2] / "infra" / "ci" / "module-tag.sh"
MODULES = ("ledger-service", "gateway", "generator", "control", "reporting")
REGISTRY = "ghcr.io/owner/repo"

DOCKER = """#!/bin/sh
echo "docker $*" >>"$STUB/calls"
case "$1 $2" in
  "exec "*) [ -f "$STUB/node-has" ] ;;
  "image inspect") [ -f "$STUB/local-has" ] ;;
  "pull "*) if [ -f "$STUB/pull-fails" ]; then echo "manifest unknown" >&2; exit 1; fi; echo sha256:1 ;;
  *) exit 0 ;;
esac
"""
K3D = """#!/bin/sh
echo "k3d $*" >>"$STUB/calls"
"""
TREE_RULE = """#!/bin/sh
echo tree-rule
"""
GRADLEW = """#!/bin/sh
echo "gradlew $*" >>"$STUB/calls"
"""


class ImagesTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        base = Path(self.temp.name)
        self.stub, self.tree = base / "stub", base / "tree"
        self.stub.mkdir()
        for name, text in (("docker", DOCKER), ("k3d", K3D)):
            (self.stub / name).write_text(text)
            (self.stub / name).chmod(0o755)
        for module in MODULES:
            (self.tree / module).mkdir(parents=True)
            (self.tree / module / "build.gradle.kts").write_text(f"// {module}\n")
        (self.tree / "gradlew").write_text(GRADLEW)
        (self.tree / "gradlew").chmod(0o755)
        git = ("git", "-C", str(self.tree))
        subprocess.run(git + ("init", "-q"), check=True)
        subprocess.run(git + ("add", "-A"), check=True)
        subprocess.run(git + ("-c", "user.name=t", "-c", "user.email=t@example.invalid", "commit", "-qm", "tree"),
                       check=True)

    def tearDown(self):
        self.temp.cleanup()

    def run_images(self, *flags, **env):
        for flag in flags:
            (self.stub / flag).touch()
        environment = dict(os.environ, PATH=f"{self.stub}:{os.environ['PATH']}", STUB=str(self.stub), **env)
        for name in ("IMAGE_REPOSITORY", "IMAGES_SOURCE"):
            if name not in env:
                environment.pop(name, None)
        result = subprocess.run([str(IMAGES), str(self.tree)], capture_output=True, text=True, env=environment)
        calls = (self.stub / "calls").read_text().splitlines() if (self.stub / "calls").exists() else []
        return result, calls

    def test_images_the_node_has_are_kept(self):
        result, calls = self.run_images("node-has")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual([line.split("=")[0] for line in result.stdout.split()],
                         ["ledger", "gateway", "generator", "control", "reporting"])
        self.assertFalse([call for call in calls if call.startswith(("k3d", "gradlew", "docker pull"))])

    def test_images_the_local_docker_has_are_imported(self):
        result, calls = self.run_images("local-has")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(len([call for call in calls if call.startswith("k3d image import")]), 1)
        self.assertFalse([call for call in calls if call.startswith("gradlew")])

    def test_a_failed_pull_is_built_from_the_tree_by_default(self):
        result, calls = self.run_images("pull-fails", IMAGE_REPOSITORY=REGISTRY)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(len([call for call in calls if call.startswith("gradlew")]), 1)
        self.assertEqual(len([call for call in calls if call.startswith("docker build")]), len(MODULES))

    def test_from_the_registry_a_failed_pull_is_an_error_and_nothing_is_built(self):
        result, calls = self.run_images("pull-fails", IMAGE_REPOSITORY=REGISTRY, IMAGES_SOURCE="registry")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("manifest unknown", result.stderr)
        self.assertFalse([call for call in calls if call.startswith(("gradlew", "docker build", "k3d"))])

    def test_from_the_registry_every_image_is_pulled_whatever_is_there(self):
        result, calls = self.run_images("node-has", "local-has", IMAGE_REPOSITORY=REGISTRY, IMAGES_SOURCE="registry")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(len([call for call in calls if call.startswith(f"docker pull -q {REGISTRY}/")]), len(MODULES))
        self.assertEqual(len([call for call in calls if call.startswith("k3d image import")]), 1)
        self.assertFalse([call for call in calls if call.startswith("gradlew")])

    def test_from_the_registry_without_one_is_an_error(self):
        result, _ = self.run_images(IMAGES_SOURCE="registry")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("IMAGE_REPOSITORY (not set)", result.stderr)

    def test_the_tags_follow_the_trees_own_rule(self):
        rule = self.tree / "infra" / "ci" / "module-tag.sh"
        rule.parent.mkdir(parents=True)
        rule.write_text(TREE_RULE)
        rule.chmod(0o755)
        result, _ = self.run_images("node-has")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual({line.split("=")[1] for line in result.stdout.split()}, {"tree-rule"})

    def test_a_tree_without_the_rule_takes_this_ones(self):
        result, _ = self.run_images("node-has")
        self.assertEqual(result.returncode, 0, result.stderr)
        tags = [line.split("=")[1] for line in result.stdout.split()]
        expected = subprocess.run([str(MODULE_TAG), "gateway", "--worktree"], cwd=self.tree, capture_output=True,
                                  text=True, check=True).stdout.strip()
        self.assertEqual(tags[1], expected)

    def test_an_unknown_source_is_refused(self):
        result, calls = self.run_images(IMAGES_SOURCE="ghcr")
        self.assertEqual(result.returncode, 2)
        self.assertEqual(calls, [])


if __name__ == "__main__":
    unittest.main()

"""Unit tests for incremental nightly planning, compatibility propagation, blame isolation, and baselines."""

import json
import tempfile
import unittest
from pathlib import Path

import downstream
import nightly


def graph(**dependencies):
    return {name: {"dependencies": deps} for name, deps in dependencies.items()}


class NightlyTests(unittest.TestCase):
    def test_plan_skips_when_nothing_changed_since_last_seen(self):
        projects = graph(lib=[], mid=["lib"], app=["mid"])
        shas = {"lib": "sha-l1", "mid": "sha-m1", "app": "sha-a1"}
        baseline = {
            "schema_version": 1,
            "projects": {
                k: {"last_seen_sha": v, "last_green_sha": v} for k, v in shas.items()
            },
        }
        plan = nightly.plan_nightly(projects, shas, baseline)
        self.assertEqual(plan["mode"], "skip")
        self.assertFalse(plan["should_run"])
        self.assertEqual(plan["affected"], [])

    def test_plan_selects_only_changed_upstreams_and_transitive_consumers(self):
        projects = graph(lib=[], unrelated=[], mid=["lib"], app=["mid"], leaf=["unrelated"])
        shas = {
            "lib": "sha-l2",
            "unrelated": "sha-u1",
            "mid": "sha-m1",
            "app": "sha-a1",
            "leaf": "sha-leaf1",
        }
        baseline = {
            "schema_version": 1,
            "projects": {
                "lib": {"last_seen_sha": "sha-l1", "last_green_sha": "sha-l1"},
                "unrelated": {"last_seen_sha": "sha-u1", "last_green_sha": "sha-u1"},
                "mid": {"last_seen_sha": "sha-m1", "last_green_sha": "sha-m1"},
                "app": {"last_seen_sha": "sha-a1", "last_green_sha": "sha-a1"},
                "leaf": {"last_seen_sha": "sha-leaf1", "last_green_sha": "sha-leaf1"},
            },
        }
        fetched = []

        def fake_commits(repo, base, head):
            fetched.append((repo, base, head))
            return {
                "base_sha": base,
                "head_sha": head,
                "ahead_by": 1,
                "commits": [{"sha": head[:7], "author": "carl", "message": "api tweak", "date": ""}],
            }

        plan = nightly.plan_nightly(projects, shas, baseline, commit_fetcher=fake_commits)
        self.assertEqual(plan["mode"], "run")
        self.assertTrue(plan["should_run"])
        self.assertEqual(plan["changed"], ["lib"])
        self.assertEqual(plan["affected"], ["app", "lib", "mid"])
        self.assertEqual(fetched, [("instanto-io/lib", "sha-l1", "sha-l2")])
        self.assertEqual([item["name"] for item in plan["matrix_include"]], ["app", "lib", "mid"])

    def test_execute_nightly_groups_handles_cycles_and_blocks_dependants(self):
        projects = graph(root=[], cyc1=["root", "cyc2"], cyc2=["cyc1"], leaf=["cyc2"], other=["root"])
        groups = downstream.build_groups(projects, set(projects))
        calls = []

        def build(name, phase):
            calls.append((name, phase))
            return not (name == "cyc2" and phase == "verify-cycle")

        results = nightly.execute_nightly_groups(projects, groups, build)
        self.assertEqual(results["root"], "passed")
        self.assertEqual(results["other"], "passed")
        self.assertEqual(results["cyc2"], "failed")
        self.assertEqual(results["cyc1"], "blocked")
        self.assertEqual(results["leaf"], "blocked")
        self.assertNotIn(("leaf", "verify"), calls)

    def test_isolate_blame_pinpoints_single_and_multi_upstream_culprit(self):
        projects = graph(u1=[], u2=[], app=["u1", "u2"])
        head_shas = {"u1": "u1-new", "u2": "u2-new", "app": "app-1"}
        baseline_projects = {
            "u1": {"last_green_sha": "u1-old"},
            "u2": {"last_green_sha": "u2-old"},
            "app": {"last_green_sha": "app-1"},
        }
        results = {"u1": "passed", "u2": "passed", "app": "failed"}
        probes = []

        def fake_probe(failed, suspect, all_suspects):
            probes.append((failed, suspect, all_suspects))
            # Flipping u2 alone breaks app; flipping u1 alone passes.
            return suspect != "u2"

        blame = nightly.isolate_blame(projects, results, head_shas, baseline_projects, fake_probe)
        self.assertEqual(blame["app"]["attribution"], "isolated")
        self.assertEqual(blame["app"]["culprits"], ["u2"])
        self.assertEqual(len(probes), 2)

        # Single suspect skips probe completely
        results_single = {"u2": "passed", "app": "failed"}
        baseline_single = {
            "u1": {"last_green_sha": "u1-old"},
            "u2": {"last_green_sha": "u2-old"},
            "app": {"last_green_sha": "app-1"},
        }
        shas_single = {"u1": "u1-old", "u2": "u2-new", "app": "app-1"}
        blame_single = nightly.isolate_blame(
            projects, results_single, shas_single, baseline_single, lambda *_: self.fail("no probe needed")
        )
        self.assertEqual(blame_single["app"]["attribution"], "single-change")
        self.assertEqual(blame_single["app"]["culprits"], ["u2"])

    def test_update_baseline_preserves_green_sha_on_downstream_failure_and_advances_on_pass(self):
        projects = graph(lib=[], mid=["lib"], app=["mid"])
        baseline = {
            "schema_version": 1,
            "projects": {
                "lib": {"last_seen_sha": "l1", "last_green_sha": "l1"},
                "mid": {"last_seen_sha": "m1", "last_green_sha": "m1"},
                "app": {"last_seen_sha": "a1", "last_green_sha": "a1"},
            },
        }
        plan_data = {
            "mode": "run",
            "head_shas": {"lib": "l2", "mid": "m1", "app": "a1"},
        }
        # Compatibility failed at app -> lib must keep last_green_sha="l1"!
        updated_fail = nightly.update_baseline(
            projects,
            baseline,
            plan_data,
            compat_results={"lib": "passed", "mid": "passed", "app": "failed"},
            timestamp="2026-11-10T01:30:00Z",
        )
        self.assertEqual(updated_fail["projects"]["lib"]["last_seen_sha"], "l2")
        self.assertEqual(updated_fail["projects"]["lib"]["last_green_sha"], "l1")
        self.assertEqual(updated_fail["projects"]["app"]["last_green_sha"], "a1")

        # Full nightly passed -> lib advances last_green_sha and last_security_sha to l2!
        updated_pass = nightly.update_baseline(
            projects,
            baseline,
            plan_data,
            compat_results={"lib": "passed", "mid": "passed", "app": "passed"},
            stage3_results={"lib": "passed", "mid": "passed", "app": "passed"},
            timestamp="2026-11-10T02:00:00Z",
        )
        self.assertEqual(updated_pass["projects"]["lib"]["last_green_sha"], "l2")
        self.assertEqual(updated_pass["projects"]["lib"]["last_security_sha"], "l2")

    def test_m2_overlay_roundtrip_strips_remote_markers_and_rejects_traversal(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            m2 = root / "m2"
            snap = m2 / "io" / "instanto" / "demo" / "1.0-SNAPSHOT"
            snap.mkdir(parents=True)
            (snap / "demo-1.0-SNAPSHOT.jar").write_bytes(b"jar")
            (snap / "_remote.repositories").write_text("remote")
            (snap / "demo.lastUpdated").write_text("ts")
            (m2 / "org" / "other").mkdir(parents=True)
            (m2 / "org" / "other" / "ignored.jar").write_bytes(b"ignored")

            archive = root / "overlay.tar.gz"
            count = nightly.export_m2_overlay(m2, archive)
            self.assertEqual(count, 1)

            dest = root / "dest-m2"
            restored = nightly.apply_m2_overlay(archive, dest)
            self.assertEqual(restored, 1)
            self.assertTrue((dest / "io/instanto/demo/1.0-SNAPSHOT/demo-1.0-SNAPSHOT.jar").is_file())
            self.assertFalse((dest / "org/other/ignored.jar").exists())

            bad_archive = root / "bad.tar.gz"
            import io
            import tarfile
            with tarfile.open(bad_archive, "w:gz") as tf:
                info = tarfile.TarInfo("../escape.jar")
                info.size = 4
                tf.addfile(info, io.BytesIO(b"evil"))
            with self.assertRaises(ValueError):
                nightly.apply_m2_overlay(bad_archive, dest)

    def test_run_verify_project_expands_placeholders_runs_post_verify_and_strips_markers(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            checkout = root / "repo"
            sub = checkout / "sub"
            sub.mkdir(parents=True)
            mvnw = checkout / "mvnw"
            mvnw.write_text("#!/bin/sh\n", encoding="utf-8")
            mvnw.chmod(0o755)
            repo = root / "m2"
            repo.mkdir()
            settings = root / "settings.xml"
            log_dir = root / "logs"

            cfg = {
                "env": {"CUSTOM_FLAG": "from-repo", "CACHE_DIR": "{runner_temp}/cache"},
                "arguments": ["-Droot={checkout}"],
                "verify_goals": ["clean", "install"],
                "full_checks": [["-f", "sub/pom.xml", "verify"]],
                "post_verify_commands": [
                    {"cmd": ["{mvn}", "--settings", "{settings}", "-v"], "env": {"STEP": "one"}},
                    {"cmd": ["bash", "-c", "pwd > cwd.txt"], "cwd": "sub"},
                    {"cmd": ["bash", "-c", "false"], "if_sonar": True},
                    {"cmd": ["bash", "-c", "false"], "allow_failure": True},
                ],
            }

            maven_calls = []
            cmd_calls = []

            def fake_maven(source, args, r, s, log, env=None):
                maven_calls.append((args, dict(env or {})))
                marker_dir = Path(r) / "io" / "instanto" / "1.0"
                marker_dir.mkdir(parents=True, exist_ok=True)
                (marker_dir / "_remote.repositories").write_text("remote", encoding="utf-8")
                return True

            def fake_cmd(cmd, cwd, log, env=None):
                cmd_calls.append((cmd, Path(cwd).resolve(), dict(env or {})))
                return "false" not in cmd

            import os
            from unittest.mock import patch

            with patch.dict(os.environ, {"SONAR_TOKEN": ""}, clear=False), patch.object(
                downstream, "run_maven", side_effect=fake_maven
            ), patch.object(nightly, "run_command_logged", side_effect=fake_cmd):
                ok = nightly.run_verify_project(
                    cfg, "demo", checkout, repo, settings, log_dir, runner_temp=str(root / "rt")
                )

            self.assertTrue(ok)
            self.assertEqual(len(maven_calls), 2)
            self.assertIn(f"-Droot={checkout.resolve()}", maven_calls[0][0])
            self.assertEqual(maven_calls[0][1]["CUSTOM_FLAG"], "from-repo")
            self.assertEqual(maven_calls[0][1]["CACHE_DIR"], str(root / "rt" / "cache"))
            self.assertFalse(list(repo.rglob("_remote.repositories")))
            # if_sonar skipped because SONAR_TOKEN is empty -> 3 commands ran instead of 4
            self.assertEqual(len(cmd_calls), 3)
            self.assertEqual(cmd_calls[0][0], [str(mvnw.resolve()), "--settings", str(settings.resolve()), "-v"])
            self.assertEqual(cmd_calls[0][2]["MAVEN_SETTINGS"], str(settings.resolve()))
            self.assertEqual(cmd_calls[1][1], sub.resolve())

    def test_update_baseline_partial_stage3_failure_holds_upstream_green_but_advances_seen(self):
        projects = graph(lib=[], app=["lib"])
        baseline = {
            "schema_version": 1,
            "projects": {
                "lib": {"last_seen_sha": "l1", "last_green_sha": "l1", "last_security_sha": "l1"},
                "app": {"last_seen_sha": "a1", "last_green_sha": "a1", "last_security_sha": "a1"},
            },
        }
        plan_data = {"mode": "run", "head_shas": {"lib": "l2", "app": "a1"}}
        updated = nightly.update_baseline(
            projects,
            baseline,
            plan_data,
            compat_results={"lib": "passed", "app": "passed"},
            stage3_results={"lib": "passed", "app": "failed"},
            timestamp="2026-11-10T02:30:00Z",
        )
        self.assertEqual(updated["projects"]["lib"]["last_seen_sha"], "l2")
        self.assertEqual(updated["projects"]["lib"]["last_green_sha"], "l1")
        self.assertEqual(updated["projects"]["lib"]["last_security_sha"], "l2")
        self.assertEqual(updated["projects"]["app"]["last_result"], "failed")


if __name__ == "__main__":
    unittest.main()

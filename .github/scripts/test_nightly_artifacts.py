import hashlib
import io
import os
from pathlib import Path
import tarfile
import tempfile
import unittest
from unittest.mock import Mock, patch

from nightly_artifacts import RunPackages


class NightlyArtifactsTest(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.root = Path(tmp.name)
        env = patch.dict(os.environ, {
            "GITHUB_REPOSITORY": "example/project", "GITHUB_RUN_ID": "101",
            "GITHUB_RUN_ATTEMPT": "3", "FORGEJO_PACKAGE_USER": "test",
            "FORGEJO_PACKAGE_TOKEN": "test", "GITHUB_STEP_SUMMARY": str(self.root / "summary"),
        })
        env.start()
        self.addCleanup(env.stop)
        self.registry = RunPackages()

    def test_round_trip_and_repeated_publication(self):
        blobs = {}

        def transfer(method, path, *, upload=None, download=None, missing_ok=False):
            if method == "PUT":
                content = upload.read()
                self.assertEqual(blobs.setdefault(path, content), content)
            elif download:
                download.write(blobs[path])
            else:
                return [{"name": key.split("/")[-1], "sha256": hashlib.sha256(data).hexdigest()}
                        for key, data in blobs.items()]

        self.registry.transfer = transfer
        source = self.root / "input"
        source.mkdir()
        (source / "plan.json").write_text("{}")
        (source / ".private").write_text("exclude")
        overlay = self.root / "overlay"
        overlay.write_bytes(b"already packed")
        archive = self.root / "transfer"
        for name, path in [("plan-artifacts", source), ("m2-overlay", overlay)]:
            self.registry.publish(name, path, archive)
            self.registry.publish(name, path, archive)
            entry = self.registry.latest_files()[name]
            self.registry.restore(name, *entry, self.root / "out", archive)
        self.assertEqual((self.root / "out" / "m2-overlay.tar.gz").read_bytes(), overlay.read_bytes())
        self.assertEqual((self.root / "out" / "plan.json").read_text(), "{}")
        self.assertFalse((self.root / "out" / ".private").exists())

    def test_rerun_inherits_only_current_predecessors(self):
        rows = [["nightly-metrics-A"], ["compat-reports", "m2-overlay"],
                ["plan-artifacts", "compat-reports", "nightly-metrics-B", "m2-overlay"]]
        self.registry.files = Mock(side_effect=[
            [{"name": name + ".tar.gz"} for name in names] for names in rows])
        versions = {k: v[0] for k, v in self.registry.latest_files().items()}
        self.assertEqual(versions, {"nightly-metrics-A": "101-3", "compat-reports": "101-2",
                                    "m2-overlay": "101-2", "plan-artifacts": "101-1"})

    def test_corrupt_or_unsafe_report_is_rejected(self):
        buffer = io.BytesIO()
        with tarfile.open(fileobj=buffer, mode="w:gz") as bundle:
            bundle.addfile(tarfile.TarInfo("../escaped"))
        data = buffer.getvalue()
        self.registry.transfer = lambda method, path, download: download.write(data)
        entry = {"name": "plan-artifacts.tar.gz", "sha256": "invalid"}
        for exception, checksum in [(RuntimeError, "invalid"), (ValueError, hashlib.sha256(data).hexdigest())]:
            entry["sha256"] = checksum
            with self.assertRaises(exception):
                self.registry.restore("plan-artifacts", "101-3", entry, self.root / "out", self.root / "temp")
        self.assertFalse((self.root / "escaped").exists())

    def test_expiry_only_targets_known_files_in_earlier_nightlies(self):
        from datetime import datetime, timedelta, timezone

        def package(run, age, name=None):
            return {"name": name or self.registry.package, "version": run, "type": "generic",
                    "created_at": (datetime.now(timezone.utc) - timedelta(days=age)).isoformat()}

        rows = [package("100-2", 2), package("90-1", 8), package("101-1", 30),
                package("80-1", 30, "another-package"), package("99-1", 0)]
        self.registry.transfer = Mock(side_effect=[rows, [], None, None, None])
        self.registry.files = lambda version: [{"name": n + ".tar.gz"}
            for n in ("m2-overlay", "compat-reports", "unrelated")]
        self.registry.expire()
        deleted = [call.args[1].split("/")[-2:] for call in self.registry.transfer.call_args_list
                   if call.args[0] == "DELETE"]
        self.assertCountEqual(deleted, [["100-2", "m2-overlay.tar.gz"], ["90-1", "m2-overlay.tar.gz"],
                                        ["90-1", "compat-reports.tar.gz"]])

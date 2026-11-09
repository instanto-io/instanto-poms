"""Verify cache isolation and rate-limit fallback without external downloads."""
import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("maven_cache", Path(__file__).parents[1] / "actions/maven-cache/cache.py")
cache = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cache)


class MavenCacheTests(unittest.TestCase):
    def test_only_remote_central_releases_survive_between_jobs(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source, saved, next_job = root / "job", root / "saved", root / "next"
            fixtures = {
                "org/example/release/1.0": "release.jar>central=\nrelease.pom>central=\n",
                "org/example/mirror/1.0": "release.jar>instanto-central=\n",
                "org/example/installed/1.0": "release.jar>central=\nrelease.pom>=\n",
                "org/example/snapshot/1.0-SNAPSHOT": "release.jar>central=\n",
                "io/instanto/release/1.0": "release.jar>central=\n",
                "org/example/private/1.0": "release.jar>private=\n",
            }
            for path, tracking in fixtures.items():
                target = source / path
                target.mkdir(parents=True)
                (target / "_remote.repositories").write_text(tracking)
                (target / "release.jar").write_text("download")
                (target / "release.pom").write_text("pom")
                (target / "release.jar.sha1").write_text("checksum")
                (target / "release.jar.lastUpdated").write_text("failed download")
            self.assertEqual(3, cache.save(source, saved))
            self.assertEqual(7, cache.restore(saved, next_job))
            self.assertEqual("download", (next_job / "org/example/release/1.0/release.jar").read_text())
            self.assertEqual("release.jar>central=\n", (next_job / "org/example/mirror/1.0/_remote.repositories").read_text())
            # Legacy runner caches that still store >instanto-central= must heal on restore.
            (saved / "org/example/mirror/1.0/_remote.repositories").write_text("release.jar>instanto-central=\n")
            healed = root / "healed"
            cache.restore(saved, healed)
            self.assertEqual("release.jar>central=\n", (healed / "org/example/mirror/1.0/_remote.repositories").read_text())
            self.assertFalse(list(next_job.rglob("*.lastUpdated")))
            for excluded in ("installed", "snapshot", "private"):
                self.assertFalse((next_job / "org/example" / excluded).exists())
            self.assertFalse((next_job / "io").exists())
            # A later local install must not replace the previously cached download.
            marker = source / "org/example/release/1.0/_remote.repositories"
            marker.write_text("release.jar>=\n")
            (marker.parent / "release.jar").write_text("local build")
            cache.save(source, saved)
            self.assertEqual("download", (saved / "org/example/release/1.0/release.jar").read_text())

    def test_explicit_repository_and_environment_expansion(self):
        with patch.dict("os.environ", {"RUNNER_TEMP": "/tmp/task fixture"}):
            self.assertEqual(Path("/tmp/task fixture/m2").resolve(), cache.local_repository('-DskipTests "-Dmaven.repo.local=$RUNNER_TEMP/m2"'))

    def test_rate_limit_uses_fallback_without_redirecting_private_registries(self):
        with patch.object(cache, "reachable", side_effect=[False, True]):
            self.assertEqual(cache.FALLBACK, cache.central_mirror(""))
        with patch.object(cache, "reachable", return_value=True):
            self.assertEqual("", cache.central_mirror(""))
        with patch.object(cache, "reachable", return_value=False):
            with self.assertRaises(RuntimeError):
                cache.central_mirror("")
        self.assertEqual("https://cache.example/maven", cache.central_mirror("https://cache.example/maven/"))
        for invalid in ["http://cache.example", "https://user:secret@cache.example"]:
            with self.assertRaises(ValueError):
                cache.central_mirror(invalid)


if __name__ == "__main__":
    unittest.main()

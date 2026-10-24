"""Exercise the registry protocol and archive boundaries without live credentials."""

import base64
from contextlib import contextmanager
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import io
import os
from pathlib import Path
import tarfile
import tempfile
import threading
import unittest
from unittest.mock import patch
import urllib.error

import publish_report


@contextmanager
def registry(status=201, corrupt=False):
    calls = []
    payloads = {}

    class Handler(BaseHTTPRequestHandler):
        def do_PUT(self):
            calls.append(("PUT", self.path, self.headers.get("Authorization")))
            payloads[self.path] = self.rfile.read(int(self.headers["Content-Length"]))
            self.send_response(status)
            if status == 302:
                self.send_header("Location", "/redirected")
            self.end_headers()

        def do_GET(self):
            calls.append(("GET", self.path, self.headers.get("Authorization")))
            self.send_response(200)
            self.end_headers()
            self.wfile.write(b"corrupt" if corrupt else payloads[self.path])

        def log_message(self, *args):
            pass

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    thread = threading.Thread(target=server.serve_forever)
    thread.start()
    try:
        yield f"http://127.0.0.1:{server.server_port}/api/packages/instanto-io/generic", calls, payloads
    finally:
        server.shutdown()
        server.server_close()
        thread.join()


class PublishReportTest(unittest.TestCase):
    def test_archive_is_uploaded_downloaded_and_linked_with_run_identity(self):
        with tempfile.TemporaryDirectory() as directory, registry() as (url, calls, payloads):
            root = Path(directory).resolve()
            reports = root / "reports"
            reports.mkdir()
            (reports / "results.json").write_text('{"ports": "passed"}')
            (reports / "ports.log").write_text("BUILD SUCCESS\n")
            (root / "settings.xml").write_text("must not be archived")
            summary = root / "summary.md"
            env = {"FORGEJO_PACKAGE_USER": "fixture", "FORGEJO_PACKAGE_TOKEN": "dummy-token",
                   "GITHUB_REPOSITORY": "instanto-io/sarto-scene", "GITHUB_RUN_ID": "123",
                   "GITHUB_RUN_ATTEMPT": "2", "GITHUB_STEP_SUMMARY": str(summary)}
            with patch.dict(os.environ, env), patch.object(publish_report, "REGISTRY", url), \
                 patch("sys.argv", ["publish_report.py", "--name", "downstream-compatibility",
                                    "--path", str(reports)]):
                publish_report.main()
            self.assertEqual([c[0] for c in calls], ["PUT", "GET"])
            self.assertIn("/ci-instanto-io-sarto-scene-downstream-compatibility/123-2/", calls[0][1])
            expected = "Basic " + base64.b64encode(b"fixture:dummy-token").decode()
            self.assertTrue(all(c[2] == expected for c in calls))
            with tarfile.open(fileobj=io.BytesIO(next(iter(payloads.values())))) as archive:
                self.assertEqual(sorted(archive.getnames()), ["ports.log", "results.json"])
            self.assertIn("Download reports", summary.read_text())
            self.assertIn("SHA-256", summary.read_text())
            self.assertNotIn("dummy-token", summary.read_text())

    def test_publication_errors_fail_and_do_not_follow_redirects(self):
        for status in (403, 409, 500, 302):
            with self.subTest(status=status), tempfile.TemporaryDirectory() as directory, \
                 registry(status=status) as (url, calls, _):
                archive = Path(directory) / "report.tar.gz"
                archive.write_bytes(b"report")
                with self.assertRaises(urllib.error.HTTPError):
                    publish_report.publish(archive, url + "/file", "fixture", "dummy")
                self.assertEqual(len(calls), 1)

    def test_download_must_match_uploaded_bytes(self):
        with tempfile.TemporaryDirectory() as directory, registry(corrupt=True) as (url, _, _):
            archive = Path(directory) / "report.tar.gz"
            archive.write_bytes(b"report")
            with self.assertRaisesRegex(RuntimeError, "checksum"):
                publish_report.publish(archive, url + "/file", "fixture", "dummy")

    def test_missing_credentials_fail_before_network_access(self):
        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory).resolve() / "report.log"
            report.write_text("test")
            with patch.dict(os.environ, {}, clear=True), \
                 patch("sys.argv", ["publish_report.py", "--name", "test", "--path", str(report)]):
                with self.assertRaisesRegex(ValueError, "package-write token"):
                    publish_report.main()

    def test_links_and_duplicate_names_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            (root / "a").mkdir()
            (root / "b").mkdir()
            for folder in ("a", "b"):
                (root / folder / "report.log").write_text("test")
            with self.assertRaisesRegex(ValueError, "Duplicate"):
                publish_report.collect_files([str(root / "*" / "*.log")])
            (root / "a" / "secret.log").symlink_to(root / "b" / "report.log")
            with self.assertRaisesRegex(ValueError, "symbolic"):
                publish_report.collect_files([str(root / "a")])


if __name__ == "__main__":
    unittest.main()

"""Transfer the private nightly run's reports and Maven overlay between runner jobs."""

import argparse
import base64
from datetime import datetime, timedelta, timezone
import gzip
import hashlib
import http.client
import json
import os
from pathlib import Path
import re
import shutil
import tarfile
import tempfile


HOST = "packages.instanto.io"
OWNER = "instanto-io"
ARTIFACT = re.compile(r"plan-artifacts|compat-reports|m2-overlay|nightly-metrics-[A-Za-z0-9._-]+")
VERSION = re.compile(r"[0-9]+-[0-9]+")
SUFFIX = ".tar.gz"


class RunPackages:
    def __init__(self):
        self.run = str(int(os.environ["GITHUB_RUN_ID"]))
        self.attempt = int(os.environ["GITHUB_RUN_ATTEMPT"])
        repo = os.environ["GITHUB_REPOSITORY"].replace("/", "-")
        if not re.fullmatch(r"[A-Za-z0-9_.-]+", repo):
            raise ValueError("Invalid repository name")
        self.package = "ci-" + repo + "-nightly"
        auth = os.environ["FORGEJO_PACKAGE_USER"] + ":" + os.environ["FORGEJO_PACKAGE_TOKEN"]
        self.headers = {"Authorization": "Basic " + base64.b64encode(auth.encode()).decode()}

    def transfer(self, method, path, *, upload=None, download=None, missing_ok=False):
        connection = http.client.HTTPSConnection(HOST, timeout=180)
        try:
            headers = dict(self.headers)
            if upload is not None:
                headers["Content-Length"] = str(os.fstat(upload.fileno()).st_size)
                headers["Content-Type"] = "application/octet-stream"
            connection.request(method, path, body=upload, headers=headers)
            response = connection.getresponse()
            if missing_ok and response.status == 404:
                return []
            if method == "DELETE" and response.status in (204, 404):
                return
            if method == "PUT" and response.status in (201, 409):
                # A lost response can leave the same immutable file already published.
                # The caller checks the digest in either case.
                return
            if method != "GET" or response.status != 200:
                raise RuntimeError(f"{method} {path} returned HTTP {response.status}")
            if download is not None:
                shutil.copyfileobj(response, download)
            else:
                return json.load(response)
        finally:
            connection.close()

    def files(self, version):
        path = f"/api/v1/packages/{OWNER}/generic/{self.package}/{version}/files"
        return self.transfer("GET", path, missing_ok=True)

    def file_path(self, version, filename):
        return f"/api/packages/{OWNER}/generic/{self.package}/{version}/{filename}"

    def latest_files(self):
        # Failed-job reruns inherit the latest successful predecessors. Stop at
        # a rebuilt plan, and discard metrics and overlays from before a rebuild.
        latest = {}
        compat_seen = False
        for attempt in range(self.attempt, 0, -1):
            version = f"{self.run}-{attempt}"
            names = set()
            for row in self.files(version):
                name = row["name"].removesuffix(SUFFIX)
                if row["name"] != name + SUFFIX or not ARTIFACT.fullmatch(name):
                    continue
                names.add(name)
                if name not in latest and (not compat_seen or name == "plan-artifacts"):
                    latest[name] = version, row
            if "plan-artifacts" in names:
                break
            compat_seen |= bool(names & {"compat-reports", "m2-overlay"})
        return latest

    def expire(self):
        # Collect candidates before deleting, so pagination stays stable.
        now = datetime.now(timezone.utc)
        expired = []
        page = 1
        while True:
            rows = self.transfer("GET", f"/api/v1/packages/{OWNER}?type=generic&q={self.package}&limit=50&page={page}")
            if not rows:
                break
            for row in rows:
                version = row["version"]
                if (row["name"] != self.package or row["type"] != "generic"
                        or not VERSION.fullmatch(version) or version.startswith(self.run + "-")):
                    continue
                age = now - datetime.fromisoformat(row["created_at"].replace("Z", "+00:00"))
                if age >= timedelta(days=1):
                    expired.append((version, age >= timedelta(days=7)))
            page += 1
        for version, reports_expired in expired:
            for row in self.files(version):
                filename = row["name"]
                name = filename.removesuffix(SUFFIX)
                if filename == name + SUFFIX and ARTIFACT.fullmatch(name) and (reports_expired or name == "m2-overlay"):
                    self.transfer("DELETE", self.file_path(version, filename))

    def publish(self, name, source, archive):
        if name == "m2-overlay":
            archive = source
        else:
            with archive.open("wb") as target, gzip.GzipFile(filename="", fileobj=target, mode="wb", mtime=0) as gz:
                with tarfile.open(fileobj=gz, mode="w|") as bundle:
                    for file in sorted(source.rglob("*")):
                        relative = file.relative_to(source)
                        if any(part.startswith(".") for part in relative.parts):
                            continue
                        if not file.is_symlink() and file.is_file():
                            bundle.add(file, arcname=str(relative), recursive=False)
        version = f"{self.run}-{self.attempt}"
        filename = name + SUFFIX
        path = self.file_path(version, filename)
        with archive.open("rb") as src:
            self.transfer("PUT", path, upload=src)
        expected = digest(archive)
        if not any(row["name"] == filename and row["sha256"] == expected for row in self.files(version)):
            raise RuntimeError(f"Registry checksum mismatch for {filename}")
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a") as summary:
            print(f"\n[{name}](https://{HOST}{path}) · SHA-256 `{expected}`", file=summary)

    def restore(self, name, version, entry, destination, archive):
        with archive.open("wb") as target:
            self.transfer("GET", self.file_path(version, entry["name"]), download=target)
        if digest(archive) != entry["sha256"]:
            raise RuntimeError(f"Registry checksum mismatch for {entry['name']}")
        destination.mkdir(parents=True, exist_ok=True)
        if name == "m2-overlay":
            shutil.move(str(archive), destination / "m2-overlay.tar.gz")
            return
        with tarfile.open(archive, "r:gz") as bundle:
            for member in bundle:
                target = (destination / member.name).resolve()
                if not member.isfile() or destination.resolve() not in target.parents:
                    raise ValueError(f"Unexpected report archive member: {member.name}")
                target.parent.mkdir(parents=True, exist_ok=True)
                with bundle.extractfile(member) as src, target.open("wb") as dst:
                    shutil.copyfileobj(src, dst)


def digest(path):
    value = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            value.update(chunk)
    return value.hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("operation", choices=["put", "get", "collect", "expire"])
    parser.add_argument("path", type=Path, nargs="?")
    parser.add_argument("--name")
    parser.add_argument("--optional", action="store_true")
    args = parser.parse_args()
    if args.operation != "expire" and args.path is None:
        parser.error("Missing artifact path")
    if args.operation in ("get", "put") and not ARTIFACT.fullmatch(args.name or ""):
        parser.error("Invalid nightly artifact name")
    packages = RunPackages()
    if args.operation == "expire":
        packages.expire()
        return
    with tempfile.TemporaryDirectory() as directory:
        archive = Path(directory) / "transfer.tar.gz"
        if args.operation == "put":
            if args.optional and not args.path.exists():
                return
            if args.name != "m2-overlay" and not args.path.is_dir():
                raise ValueError(f"Missing report directory: {args.path}")
            packages.publish(args.name, args.path, archive)
            return
        files = packages.latest_files()
        if args.operation == "get":
            if args.name not in files:
                raise ValueError(f"Missing nightly artifact: {args.name}")
            packages.restore(args.name, *files[args.name], args.path, archive)
        else:
            for name, entry in sorted(files.items()):
                if name != "m2-overlay":
                    packages.restore(name, *entry, args.path / name, archive)


if __name__ == "__main__":
    main()

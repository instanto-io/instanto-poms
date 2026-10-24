#!/usr/bin/env python3
"""Publish CI diagnostics to Forgejo's generic registry, independent of Actions quota."""

import argparse
import base64
import glob
import hashlib
import os
from pathlib import Path
import re
import tarfile
import tempfile
import urllib.error
import urllib.request


REGISTRY = "https://packages.instanto.io/api/packages/instanto-io/generic"


class NoRedirect(urllib.request.HTTPRedirectHandler):
    """Never forward the package credentials to a redirect destination."""

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def component(value):
    if not re.fullmatch(r"[A-Za-z0-9_.+-]+", value):
        raise ValueError("Invalid package name or run identifier")
    return value


def collect_files(patterns):
    files = {}
    for pattern in patterns:
        for match in sorted(glob.glob(pattern)):
            path = Path(match)
            candidates = path.rglob("*") if path.is_dir() else [path]
            for candidate in candidates:
                # Reports must not pull credentials or other files through symlinks.
                if candidate.is_symlink() or any(p.is_symlink() for p in candidate.parents):
                    raise ValueError("Report paths must not contain symbolic links")
                if candidate.is_file():
                    name = candidate.relative_to(path).as_posix() if path.is_dir() else candidate.name
                    if name in files and files[name] != candidate:
                        raise ValueError("Duplicate archive path: " + name)
                    files[name] = candidate
    return files


def publish(archive, url, username, token):
    credentials = base64.b64encode(f"{username}:{token}".encode()).decode()
    headers = {"Authorization": "Basic " + credentials}
    opener = urllib.request.build_opener(NoRedirect())
    with archive.open("rb") as stream:
        request = urllib.request.Request(url, data=stream, method="PUT", headers={
            **headers, "Content-Type": "application/gzip",
            "Content-Length": str(archive.stat().st_size),
        })
        with opener.open(request, timeout=120) as response:
            if response.status != 201:
                raise RuntimeError(f"Unexpected upload status: {response.status}")
    # Verify the stored bytes, not just acceptance of the upload request.
    expected = hashlib.sha256(archive.read_bytes()).hexdigest()
    actual = hashlib.sha256()
    with opener.open(urllib.request.Request(url, headers=headers), timeout=120) as response:
        for chunk in iter(lambda: response.read(1024 * 1024), b""):
            actual.update(chunk)
    if actual.hexdigest() != expected:
        raise RuntimeError("Published report checksum does not match the local archive")
    return expected


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--name", required=True)
    parser.add_argument("--path", action="append", required=True, help="Directory or file glob; repeatable")
    args = parser.parse_args()
    files = collect_files(args.path)
    if not files:
        print("No report files were produced; nothing to publish.")
        return
    username = os.environ.get("FORGEJO_PACKAGE_USER", "")
    token = os.environ.get("FORGEJO_PACKAGE_TOKEN", "")
    if not username or not token:
        raise ValueError("Report publication requires FORGEJO_PACKAGE_USER and a package-write token")
    repository = component(os.environ["GITHUB_REPOSITORY"].replace("/", "-"))
    name = component(args.name)
    package = f"ci-{repository}-{name}"
    version = component(os.environ["GITHUB_RUN_ID"] + "-" + os.environ["GITHUB_RUN_ATTEMPT"])
    url = f"{REGISTRY}/{package}/{version}/{name}.tar.gz"
    with tempfile.TemporaryDirectory(prefix="ci-report-") as directory:
        archive = Path(directory) / f"{name}.tar.gz"
        with tarfile.open(archive, "w:gz") as bundle:
            for filename, source in sorted(files.items()):
                bundle.add(source, arcname=filename, recursive=False)
        digest = publish(archive, url, username, token)
    print(f"Published {len(files)} report files: {url}\nSHA-256: {digest}")
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a") as summary:
            summary.write(f"\n### {name}\n\n[Download reports]({url}) ({len(files)} files).\n\n"
                          f"SHA-256: `{digest}`\n")


if __name__ == "__main__":
    try:
        main()
    except urllib.error.HTTPError as error:
        # Do not log response bodies or request headers, which may contain credentials.
        raise SystemExit(f"Report registry request failed (HTTP {error.code}); check package-write access and registry availability.")
    except (OSError, ValueError, RuntimeError) as error:
        raise SystemExit(str(error))

"""Keep public downloads outside the job repository wiped by the runner hook."""
import argparse
import os
from pathlib import Path
import shlex
import shutil
import urllib.error
import urllib.parse
import urllib.request

CENTRAL = "https://repo.maven.apache.org/maven2"
FALLBACK = "https://maven-central.storage-download.googleapis.com/maven2"
PROBE = "/org/apache/ant/ant/1.10.15/ant-1.10.15.pom"
PUBLIC_REPOSITORIES = {"central", "instanto-central"}


def local_repository(arguments):
    repository = str(Path.home() / ".m2/repository")
    for argument in shlex.split(arguments):
        if argument.startswith("-Dmaven.repo.local="):
            repository = argument.split("=", 1)[1]
    return Path(os.path.expandvars(repository)).expanduser().resolve()


def copy_file(source, target):
    if source.is_symlink():
        return
    target.parent.mkdir(parents=True, exist_ok=True)
    temporary = target.with_name(target.name + ".cache-tmp")
    shutil.copyfile(source, temporary)
    temporary.replace(target)


def restore(cache, repository):
    count = 0
    for source in cache.rglob("*"):
        if source.is_file() and not source.is_symlink():
            copy_file(source, repository / source.relative_to(cache))
            count += 1
    return count


def save(repository, cache):
    count = 0
    for marker in repository.rglob("_remote.repositories"):
        relative = marker.parent.relative_to(repository)
        if relative.parts[:2] == ("io", "instanto") or any("SNAPSHOT" in p for p in relative.parts):
            continue
        entries = []
        for line in marker.read_text().splitlines():
            if line.startswith("#") or ">" not in line or "=" not in line:
                continue
            name, origin = line.split("=", 1)[0].rsplit(">", 1)
            # A blank origin means mvn install wrote into this version directory.
            if not origin:
                entries = []
                break
            if origin in PUBLIC_REPOSITORIES and Path(name).name == name:
                entries.append((name, line))
        if not entries:
            continue
        tracked = []
        for name, line in entries:
            source = marker.parent / name
            if not source.is_file() or source.is_symlink():
                continue
            copy_file(source, cache / relative / name)
            for suffix in (".sha1", ".sha256", ".sha512", ".md5", ".asc"):
                checksum = source.with_name(source.name + suffix)
                if checksum.is_file():
                    copy_file(checksum, cache / relative / checksum.name)
            tracked.append(line)
            count += 1
        if tracked:
            target = cache / relative / marker.name
            target.write_text("\n".join(tracked) + "\n")
    return count


def reachable(base):
    try:
        request = urllib.request.Request(base.rstrip("/") + PROBE, method="HEAD")
        with urllib.request.urlopen(request, timeout=8) as response:
            return response.status == 200
    except (urllib.error.URLError, TimeoutError, OSError):
        return False


def central_mirror(configured):
    if configured:
        parsed = urllib.parse.urlparse(configured)
        if parsed.scheme != "https" or not parsed.netloc or parsed.username or parsed.password:
            raise ValueError("MAVEN_CENTRAL_MIRROR_URL must be an HTTPS URL without credentials")
        return configured.rstrip("/")
    if reachable(CENTRAL):
        return ""
    if reachable(FALLBACK):
        print("Central unavailable or rate-limited; using Google's public Central mirror")
        return FALLBACK
    raise RuntimeError("Neither Maven Central nor its fallback mirror is reachable")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("mode", choices=["prepare", "save"])
    parser.add_argument("--maven-args", default="")
    args = parser.parse_args()
    root = Path(os.environ.get("MAVEN_DOWNLOAD_CACHE", str(Path.home() / ".local/share/instanto-maven-cache/v1")))
    cache = root / "central"
    repository = local_repository(args.maven_args)
    cache.mkdir(parents=True, exist_ok=True)
    repository.mkdir(parents=True, exist_ok=True)
    if args.mode == "prepare":
        restored = restore(cache, repository)
        wrapper = root / "wrapper-home"
        wrapper.mkdir(parents=True, exist_ok=True)
        mirror = central_mirror(os.environ.get("MAVEN_CENTRAL_MIRROR_URL", ""))
        with open(os.environ["GITHUB_ENV"], "a") as env:
            env.write(f"MAVEN_DOWNLOAD_CACHE={root}\nMAVEN_USER_HOME={wrapper}\n")
            if mirror:
                env.write(f"MAVEN_CENTRAL_MIRROR_URL={mirror}\nMVNW_REPOURL={mirror}\n")
        print(f"Runner download cache: restored {restored} files into {repository}")
        print(f"Maven wrapper cache: {wrapper}")
    else:
        print(f"Runner download cache: saved {save(repository, cache)} Central release artifacts")


if __name__ == "__main__":
    main()

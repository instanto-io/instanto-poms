#!/usr/bin/env python3
"""Build registered downstream repositories against one pinned upstream checkout.

No dispatches or deployments: every build installs only into a fresh job-local Maven
repository. The result belongs to the originating run. Repository cycles are bounded
by SCCs: install each member, then verify the entire group against those new artifacts.
"""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import urllib.request
import xml.etree.ElementTree as ET


def load_catalog(path):
    data = json.loads(Path(path).read_text())
    if data.get("schema_version") != 1 or data.get("organisation") != "instanto-io":
        raise ValueError("Unsupported downstream catalog")
    projects = data["projects"]
    for name, config in projects.items():
        if not re.fullmatch(r"[a-z0-9][a-z0-9.-]*", name):
            raise ValueError("Invalid project name")
        if set(config["dependencies"]) - projects.keys():
            raise ValueError(f"Unknown dependency of {name}")
        if name in config["dependencies"]:
            raise ValueError(f"Self dependency of {name}")
        repository = config.get("repository", "instanto-io/" + name)
        if not re.fullmatch(r"(?:instanto-io|cstainton)/[a-z0-9][a-z0-9.-]*", repository):
            raise ValueError(f"Invalid repository for {name}")
        for check in [config.get("arguments", []), *config.get("checks", [])]:
            if not isinstance(check, list) or not all(isinstance(arg, str) for arg in check):
                raise ValueError(f"Checks for {name} must be argument arrays")
    return projects


def affected_projects(projects, upstream):
    if upstream not in projects:
        return set()
    affected = {upstream}
    while True:
        expanded = affected | {name for name, value in projects.items()
                               if affected.intersection(value["dependencies"])}
        if expanded == affected:
            return affected
        affected = expanded


def build_groups(projects, affected):
    """Tarjan SCCs in dependency-first order; diamonds are visited exactly once."""
    indices, low, stack, active, groups = {}, {}, [], set(), []

    def visit(name):
        indices[name] = low[name] = len(indices)
        stack.append(name)
        active.add(name)
        for dep in sorted(set(projects[name]["dependencies"]) & affected):
            if dep not in indices:
                visit(dep)
                low[name] = min(low[name], low[dep])
            elif dep in active:
                low[name] = min(low[name], indices[dep])
        if low[name] == indices[name]:
            group = []
            while True:
                member = stack.pop()
                active.remove(member)
                group.append(member)
                if member == name:
                    break
            groups.append(sorted(group))

    for name in sorted(affected):
        if name not in indices:
            visit(name)
    return groups


def api(path):
    token = os.environ.get("MAVEN_TOKEN", "")
    headers = {"Accept": "application/vnd.github+json", "User-Agent": "instanto-downstream-ci"}
    if token:
        headers["Authorization"] = "Bearer " + token
    request = urllib.request.Request("https://api.github.com/" + path, headers=headers)
    with urllib.request.urlopen(request, timeout=60) as response:
        return json.load(response)


def checkout(repo, destination):
    info = api("repos/" + repo)
    sha = api("repos/" + repo + "/commits/" + info["default_branch"])["sha"]
    # Keep credentials out of URLs, arguments, subprocess output and persisted git config.
    import base64
    env = os.environ.copy()
    token = env.get("MAVEN_TOKEN", "")
    if token:
        env.update(GIT_CONFIG_COUNT="1", GIT_CONFIG_KEY_0="http.https://github.com/.extraheader",
                   GIT_CONFIG_VALUE_0="AUTHORIZATION: basic " + base64.b64encode(
                       ("x-access-token:" + token).encode()).decode())
    subprocess.run(["git", "init", "-q", str(destination)], check=True, env=env)
    subprocess.run(["git", "remote", "add", "origin", "https://github.com/" + repo + ".git"],
                   cwd=destination, check=True, env=env)
    subprocess.run(["git", "fetch", "--quiet", "--depth=1", "origin", sha],
                   cwd=destination, check=True, env=env)
    subprocess.run(["git", "checkout", "--quiet", "--detach", "FETCH_HEAD"],
                   cwd=destination, check=True, env=env)
    return sha


def write_settings(path, sources):
    """Use environment references, so credentials never enter reports or XML literals."""
    ids = {"github"}
    for source in sources:
        for pom in source.rglob("pom.xml"):
            if "target" not in pom.parts:
                ids.update(re.findall(r"<id>(github[A-Za-z0-9._-]*)</id>", pom.read_text()))
    root = ET.Element("settings")
    servers = ET.SubElement(root, "servers")
    credentials = [(name, "MAVEN_ACTOR", "MAVEN_TOKEN") for name in sorted(ids)]
    credentials += [("forgejo-instanto", "FORGEJO_PACKAGE_USER", "FORGEJO_PACKAGE_TOKEN"),
                    ("artifacts", "ARTIFACTS_USERNAME", "ARTIFACTS_TOKEN")]
    for name, username, password in credentials:
        server = ET.SubElement(servers, "server")
        for key, value in [("id", name), ("username", "${env." + username + "}"),
                           ("password", "${env." + password + "}")]:
            ET.SubElement(server, key).text = value
    profile = ET.SubElement(ET.SubElement(root, "profiles"), "profile")
    ET.SubElement(profile, "id").text = "instanto-snapshots"
    for plural, singular in [("repositories", "repository"), ("pluginRepositories", "pluginRepository")]:
        repo = ET.SubElement(ET.SubElement(profile, plural), singular)
        ET.SubElement(repo, "id").text = "forgejo-instanto"
        ET.SubElement(repo, "url").text = "https://packages.instanto.io/api/packages/instanto-io/maven"
        for kind, enabled in [("releases", "false"), ("snapshots", "true")]:
            ET.SubElement(ET.SubElement(repo, kind), "enabled").text = enabled
    ET.SubElement(ET.SubElement(root, "activeProfiles"), "activeProfile").text = "instanto-snapshots"
    ET.ElementTree(root).write(path, encoding="unicode")
    path.chmod(0o600)


def run_maven(source, arguments, repository, settings, log):
    wrapper = source / "mvnw"
    executable = str(wrapper) if wrapper.is_file() and os.access(wrapper, os.X_OK) else "mvn"
    command = [executable, "--batch-mode", "--no-transfer-progress", "--errors", "--fail-at-end",
               "--settings", str(settings), "-Dmaven.repo.local=" + str(repository), *arguments]
    # Maven executes in each repository, preserving its multiModuleProjectDirectory contract.
    with log.open("w") as output:
        process = subprocess.Popen(command, cwd=source, stdout=subprocess.PIPE,
                                   stderr=subprocess.STDOUT, text=True)
        for line in process.stdout:
            output.write(line)
            print(line, end="", flush=True)
        return process.wait() == 0


def execute_groups(projects, groups, upstream, build, seed_ok):
    """Failed SCCs block dependants; independent branches continue and remain visible."""
    results = {upstream: "passed" if seed_ok else "failed"}
    for group in groups:
        dependencies = {dep for name in group for dep in projects[name]["dependencies"]
                        if dep not in group and dep in results}
        if any(results[dep] != "passed" for dep in dependencies) or (upstream in group and not seed_ok):
            for name in group:
                results.setdefault(name, "blocked")
            continue
        if len(group) == 1:
            name = group[0]
            if name != upstream:
                results[name] = "passed" if build(name, "verify") else "failed"
            continue
        # Cycles can occur at repository level even when Maven modules are acyclic.
        # Stop this group on a failed install; never pretend a stale snapshot is a new build.
        ready = True
        for name in group:
            if name == upstream:
                continue
            if not build(name, "install-cycle"):
                results[name] = "failed"
                ready = False
        if ready:
            for name in group:
                results[name] = "passed" if build(name, "verify-cycle") else "failed"
        else:
            for name in group:
                results.setdefault(name, "blocked")
        # A partial cycle is not a validated dependency for any later group.
        if any(results.get(name) != "passed" for name in group):
            for name in group:
                if results.get(name) == "passed":
                    results[name] = "blocked"
    return results


def report(output, upstream, revisions, groups, results):
    result = {"upstream": upstream, "revisions": revisions, "groups": groups, "results": results}
    (output / "results.json").write_text(json.dumps(result, indent=2) + "\n")
    lines = ["## Downstream compatibility", "", "Upstream: `" + upstream + "`", "",
             "| Project | Revision | Result |", "| --- | --- | --- |"]
    for name in sorted(results):
        sha = revisions.get(name, "unavailable")
        lines.append(f"| {name} | `{sha}` | {results[name]} |")
    lines += ["", "All builds use a job-local Maven repository; no downstream artifacts are published.",
              "Repository cycles are installed once and verified again as a group. A failed group blocks its dependants."]
    summary = "\n".join(lines) + "\n"
    # Preserve the revision/result table in the job log even if report storage fails.
    print(summary, flush=True)
    (output / "summary.md").write_text(summary)
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a") as stream:
            stream.write(summary)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--catalog", type=Path, required=True)
    parser.add_argument("--upstream", required=True)
    parser.add_argument("--source", type=Path, default=Path.cwd())
    parser.add_argument("--workspace", type=Path)
    parser.add_argument("--settings", type=Path)
    parser.add_argument("--plan-only", action="store_true")
    parser.add_argument("--verify-upstream", action="store_true",
                        help="Run upstream tests too, for manual runs without a preceding verification")
    args = parser.parse_args()
    projects = load_catalog(args.catalog)
    affected = affected_projects(projects, args.upstream)
    groups = build_groups(projects, affected)
    print(json.dumps({"upstream": args.upstream, "groups": groups}, indent=2), flush=True)
    if args.plan_only:
        return 0
    if not affected or (affected == {args.upstream} and not args.verify_upstream):
        print("No registered downstream projects.")
        return 0
    if args.workspace is None:
        parser.error("--workspace is required for builds")
    workspace = args.workspace.resolve()
    workspace.mkdir(parents=True, exist_ok=False)
    output = workspace / "reports"
    output.mkdir()
    repository = workspace / "m2"
    repository.mkdir()
    sources = {args.upstream: args.source.resolve()}
    revisions = {args.upstream: subprocess.check_output(
        ["git", "rev-parse", "HEAD"], cwd=args.source, text=True).strip()}
    checkout_failed = set()
    for name in sorted(affected - {args.upstream}):
        sources[name] = workspace / name
        try:
            revisions[name] = checkout(projects[name].get("repository", "instanto-io/" + name), sources[name])
        except (OSError, subprocess.SubprocessError) as error:
            print(f"::error::Cannot check out {name}: {type(error).__name__}")
            checkout_failed.add(name)

    settings = args.settings.resolve() if args.settings else workspace / "settings.xml"
    if args.settings is None:
        write_settings(settings, sources.values())

    def build(name, phase):
        if name in checkout_failed:
            return False
        print(f"::group::{name} {phase} at {revisions[name]}", flush=True)
        # The seed alone refreshes remote snapshots. Subsequent builds must use locally
        # installed upstream artifacts rather than racing another upstream publication.
        arguments = ["--update-snapshots" if phase == "seed" else "--no-snapshot-updates",
                     *projects[name].get("arguments", []), "clean", "install"]
        skip_tests = phase == "install-cycle" or (phase == "seed" and not args.verify_upstream)
        if skip_tests:
            arguments.append("-DskipTests")
        try:
            ok = run_maven(sources[name], arguments, repository, settings,
                           output / f"{name}-{phase}.log")
        except OSError as error:
            print(f"::error::Cannot build {name}: {type(error).__name__}")
            ok = False
        if ok and not skip_tests:
            for index, check in enumerate(projects[name].get("checks", [])):
                ok = run_maven(sources[name], ["--no-snapshot-updates", *check], repository,
                               settings, output / f"{name}-{phase}-check-{index}.log")
                if not ok:
                    break
        print("::endgroup::", flush=True)
        return ok

    results = execute_groups(projects, groups, args.upstream, build, build(args.upstream, "seed"))
    report(output, args.upstream, revisions, groups, results)
    return 0 if all(value == "passed" for value in results.values()) else 1


if __name__ == "__main__":
    sys.exit(main())

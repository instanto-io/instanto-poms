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
        for check in [config.get("arguments", []), *config.get("checks", []), *config.get("full_checks", [])]:
            if not isinstance(check, list) or not all(isinstance(arg, str) for arg in check):
                raise ValueError(f"Checks for {name} must be argument arrays")
        if "verify_goals" in config:
            goals = config["verify_goals"]
            if not isinstance(goals, list) or not all(isinstance(arg, str) for arg in goals) or not goals:
                raise ValueError(f"verify_goals for {name} must be a non-empty string array")
        if "runner_labels" in config:
            labels = config["runner_labels"]
            if not isinstance(labels, list) or not all(isinstance(arg, str) for arg in labels) or not labels:
                raise ValueError(f"runner_labels for {name} must be a non-empty string array")
        if "install_playwright" in config and not isinstance(config["install_playwright"], bool):
            raise ValueError(f"install_playwright for {name} must be a boolean")
        if "sonar_args" in config:
            sargs = config["sonar_args"]
            if not isinstance(sargs, list) or not all(isinstance(arg, str) for arg in sargs):
                raise ValueError(f"sonar_args for {name} must be a string array")
        if "node_version" in config:
            nv = config["node_version"]
            if not isinstance(nv, str) or not nv.strip():
                raise ValueError(f"node_version for {name} must be a non-empty string")
        if "env" in config:
            env_cfg = config["env"]
            if not isinstance(env_cfg, dict) or not all(
                isinstance(k, str) and k and isinstance(v, str) for k, v in env_cfg.items()
            ):
                raise ValueError(f"env for {name} must be a string map")
        if "post_verify_commands" in config:
            cmds = config["post_verify_commands"]
            if not isinstance(cmds, list):
                raise ValueError(f"post_verify_commands for {name} must be an array")
            for step in cmds:
                if not isinstance(step, dict):
                    raise ValueError(f"post_verify_commands entries for {name} must be objects")
                cmd = step.get("cmd")
                if not isinstance(cmd, list) or not cmd or not all(isinstance(arg, str) for arg in cmd):
                    raise ValueError(f"post_verify_commands cmd for {name} must be a non-empty string array")
                if "env" in step:
                    step_env = step["env"]
                    if not isinstance(step_env, dict) or not all(
                        isinstance(k, str) and k and isinstance(v, str) for k, v in step_env.items()
                    ):
                        raise ValueError(f"post_verify_commands env for {name} must be a string map")
                if "cwd" in step and (not isinstance(step["cwd"], str) or not step["cwd"].strip()):
                    raise ValueError(f"post_verify_commands cwd for {name} must be a non-empty string")
                if "if_sonar" in step and not isinstance(step["if_sonar"], bool):
                    raise ValueError(f"post_verify_commands if_sonar for {name} must be a boolean")
                if "allow_failure" in step and not isinstance(step["allow_failure"], bool):
                    raise ValueError(f"post_verify_commands allow_failure for {name} must be a boolean")
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


def checkout(repo, destination, sha=None):
    if not sha:
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


def fast_skip_flags(existing=(), skip_jacoco=True):
    """Return Maven flags to skip heavy analysis plugins during fast/bootstrap passes."""
    existing_keys = {arg.split("=", 1)[0] for arg in existing}
    candidates = [
        "-Dspotbugs.skip=true",
        "-Dmaven.javadoc.skip=true",
        "-Dspotless.skip=true",
    ]
    if skip_jacoco:
        candidates.append("-Djacoco.skip=true")
    return [flag for flag in candidates if flag.split("=", 1)[0] not in existing_keys]


def strip_remote_repositories(repository_root):
    """Clear remote resolver markers so exported local snapshots resolve cleanly across jobs."""
    root = Path(repository_root)
    if not root.exists():
        return
    for pattern in ("_remote.repositories", "*.lastUpdated"):
        for marker in root.rglob(pattern):
            marker.unlink(missing_ok=True)


def isolate_upstream(source, destination):
    """Copy the exact committed source outside the enclosing Actions checkout."""
    revision = subprocess.check_output(
        ["git", "rev-parse", "HEAD"], cwd=source, text=True).strip()
    subprocess.run(["git", "clone", "--quiet", "--no-local", "--no-checkout",
                    str(source.resolve()), str(destination)], check=True)
    subprocess.run(["git", "checkout", "--quiet", "--detach", revision],
                   cwd=destination, check=True)
    return revision


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


def resolve_maven_executable(source):
    wrapper = source / "mvnw"
    return str(wrapper) if wrapper.is_file() and os.access(wrapper, os.X_OK) else "mvn"


def expand_placeholders(values, placeholders):
    expanded = []
    for value in values:
        item = value
        for key, replacement in placeholders.items():
            item = item.replace("{" + key + "}", replacement)
        expanded.append(item)
    return expanded


def expand_env(env_map, placeholders):
    return {
        key: expand_placeholders([value], placeholders)[0]
        for key, value in env_map.items()
    }


def run_maven(source, arguments, repository, settings, log, env=None):
    executable = resolve_maven_executable(source)
    command = [executable, "--batch-mode", "--no-transfer-progress", "--errors", "--fail-at-end",
               "--settings", str(settings), "-Dmaven.repo.local=" + str(repository),
               "-Dmaven.top-level-basedir=" + str(source.resolve()), *arguments]
    merged_env = os.environ.copy()
    if env:
        merged_env.update(env)
    # Pin the workspace root as well as cwd: Quarkus can otherwise discover an enclosing
    # checkout when Actions places the upstream source inside its existing workspace.
    with log.open("w") as output:
        process = subprocess.Popen(command, cwd=source, stdout=subprocess.PIPE,
                                   stderr=subprocess.STDOUT, text=True, env=merged_env)
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
    parser.add_argument("--skip-analysis", action="store_true",
                        help="Skip SpotBugs, Javadoc, Spotless, and JaCoCo during compatibility builds")
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
    sources = {args.upstream: workspace / args.upstream}
    revisions = {args.upstream: isolate_upstream(args.source, sources[args.upstream])}
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
        placeholders = {
            "checkout": str(sources[name].resolve()),
            "settings": str(settings),
            "m2": str(repository),
            "mvn": resolve_maven_executable(sources[name]),
            "runner_temp": os.environ.get("RUNNER_TEMP", str(workspace)),
        }
        repo_env = expand_env(projects[name].get("env", {}), placeholders)
        # The seed alone refreshes remote snapshots. Subsequent builds must use locally
        # installed upstream artifacts rather than racing another upstream publication.
        arguments = ["--update-snapshots" if phase == "seed" else "--no-snapshot-updates",
                     *expand_placeholders(projects[name].get("arguments", []), placeholders),
                     "clean", "install"]
        skip_tests = phase == "install-cycle" or (phase == "seed" and not args.verify_upstream)
        if skip_tests:
            arguments.append("-DskipTests")
        if skip_tests or args.skip_analysis:
            arguments.extend(fast_skip_flags(arguments, skip_jacoco=skip_tests or args.skip_analysis))
        try:
            ok = run_maven(sources[name], arguments, repository, settings,
                           output / f"{name}-{phase}.log", env=repo_env)
            strip_remote_repositories(repository)
        except OSError as error:
            print(f"::error::Cannot build {name}: {type(error).__name__}")
            ok = False
        if ok and not skip_tests:
            for index, check in enumerate(projects[name].get("checks", [])):
                check_args = ["--no-snapshot-updates", *expand_placeholders(check, placeholders)]
                if args.skip_analysis:
                    check_args.extend(fast_skip_flags(check_args, skip_jacoco=True))
                ok = run_maven(sources[name], check_args, repository, settings,
                               output / f"{name}-{phase}-check-{index}.log", env=repo_env)
                strip_remote_repositories(repository)
                if not ok:
                    break
        print("::endgroup::", flush=True)
        return ok

    results = execute_groups(projects, groups, args.upstream, build, build(args.upstream, "seed"))
    report(output, args.upstream, revisions, groups, results)
    return 0 if all(value == "passed" for value in results.values()) else 1


if __name__ == "__main__":
    sys.exit(main())

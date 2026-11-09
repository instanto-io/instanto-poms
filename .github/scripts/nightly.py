#!/usr/bin/env python3
"""Incremental, blame-traceable nightly pipeline coordinator for Instanto projects."""

import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tarfile
import urllib.error

import downstream


EMPTY_BASELINE = {"schema_version": 1, "updated_at": None, "projects": {}}


def utc_now():
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def run_command_logged(command, cwd, log, env=None):
    merged_env = os.environ.copy()
    if env:
        merged_env.update(env)
    with log.open("w", encoding="utf-8") as output:
        process = subprocess.Popen(
            command,
            cwd=cwd,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            env=merged_env,
        )
        for line in process.stdout:
            output.write(line)
            print(line, end="", flush=True)
        return process.wait() == 0


def _placeholders(checkout_dir, repository, settings, runner_temp=None, central_settings=None):
    resolved_checkout = Path(checkout_dir).resolve()
    resolved_repo = Path(repository).resolve()
    resolved_settings = Path(settings).resolve()
    resolved_central = (
        Path(central_settings).resolve()
        if central_settings is not None
        else resolved_settings.with_name("central-settings.xml")
    )
    return {
        "checkout": str(resolved_checkout),
        "settings": str(resolved_settings),
        "central_settings": str(resolved_central),
        "m2": str(resolved_repo),
        "mvn": downstream.resolve_maven_executable(resolved_checkout),
        "runner_temp": runner_temp or os.environ.get("RUNNER_TEMP", str(resolved_repo.parent)),
    }


def load_baseline(path):
    if path is None or not Path(path).is_file():
        return {"schema_version": 1, "updated_at": None, "projects": {}}
    text = Path(path).read_text(encoding="utf-8").strip()
    if not text:
        return {"schema_version": 1, "updated_at": None, "projects": {}}
    data = json.loads(text)
    return {
        "schema_version": 1,
        "updated_at": data.get("updated_at"),
        "projects": dict(data.get("projects", {})),
    }


def fetch_head_shas(projects):
    shas = {}
    for name in sorted(projects):
        repo = projects[name].get("repository", "instanto-io/" + name)
        info = downstream.api("repos/" + repo)
        branch = info["default_branch"]
        shas[name] = downstream.api(f"repos/{repo}/commits/{branch}")["sha"]
    return shas


def fetch_commit_range(repo, base_sha, head_sha, limit=20):
    if not base_sha or not head_sha or base_sha == head_sha:
        return {"base_sha": base_sha, "head_sha": head_sha, "ahead_by": 0, "commits": []}
    try:
        data = downstream.api(f"repos/{repo}/compare/{base_sha}...{head_sha}")
    except (urllib.error.HTTPError, urllib.error.URLError, OSError):
        return {
            "base_sha": base_sha,
            "head_sha": head_sha,
            "ahead_by": None,
            "commits": [],
            "compare_unavailable": True,
        }
    commits = []
    for item in (data.get("commits") or [])[-limit:]:
        commit = item.get("commit", {})
        author = (commit.get("author") or {}).get("name") or (
            item.get("author") or {}
        ).get("login", "unknown")
        message = (commit.get("message") or "").splitlines()[0][:120]
        commits.append(
            {
                "sha": item.get("sha", "")[:12],
                "full_sha": item.get("sha", ""),
                "author": author,
                "message": message,
                "date": (commit.get("author") or {}).get("date", ""),
            }
        )
    return {
        "base_sha": base_sha,
        "head_sha": head_sha,
        "ahead_by": data.get("total_commits", len(commits)),
        "commits": list(reversed(commits)),
    }


def ancestors_of(projects, node):
    visited = set()
    frontier = list(projects.get(node, {}).get("dependencies", []))
    while frontier:
        cur = frontier.pop()
        if cur in visited or cur not in projects:
            continue
        visited.add(cur)
        frontier.extend(projects[cur]["dependencies"])
    return visited


def transitive_affected(projects, roots):
    affected = set()
    for root in roots:
        affected |= downstream.affected_projects(projects, root)
    return affected


def plan_nightly(
    projects,
    head_shas,
    baseline,
    forced_upstreams=None,
    force_all=False,
    bootstrap_only=False,
    commit_fetcher=None,
):
    base_projects = baseline.get("projects", {})
    dirty_since_seen = sorted(
        name
        for name in projects
        if name not in base_projects
        or base_projects[name].get("last_seen_sha") != head_shas[name]
    )
    unhealthy_vs_green = {
        name
        for name in projects
        if name not in base_projects
        or base_projects[name].get("last_green_sha") != head_shas[name]
    }

    if bootstrap_only:
        return {
            "mode": "bootstrap",
            "should_run": False,
            "changed": [],
            "unhealthy_vs_green": sorted(unhealthy_vs_green),
            "affected": [],
            "groups": [],
            "matrix_include": [],
            "head_shas": head_shas,
            "commit_ranges": {},
        }

    forced = set(forced_upstreams or [])
    unknown_forced = forced - projects.keys()
    if unknown_forced:
        raise ValueError("Unknown upstream project(s): " + ", ".join(sorted(unknown_forced)))

    if forced:
        changed = sorted(forced)
    elif force_all:
        changed = sorted(projects)
    else:
        changed = dirty_since_seen

    if not changed:
        return {
            "mode": "skip",
            "should_run": False,
            "changed": [],
            "unhealthy_vs_green": sorted(unhealthy_vs_green),
            "affected": [],
            "groups": [],
            "matrix_include": [],
            "head_shas": head_shas,
            "commit_ranges": {},
        }

    affected = transitive_affected(projects, changed)
    groups = downstream.build_groups(projects, affected)

    # Trace commits from last_green_sha whenever possible (not just last_seen_sha),
    # so previously broken unmerged changes remain in blame range until green.
    trace_candidates = sorted(
        set(changed)
        | (
            unhealthy_vs_green
            & ({d for n in affected for d in ancestors_of(projects, n)} | affected)
        )
    )
    commit_ranges = {}
    fetcher = commit_fetcher or fetch_commit_range
    for name in trace_candidates:
        entry = base_projects.get(name, {})
        base_sha = entry.get("last_green_sha") or entry.get("last_seen_sha")
        repo = projects[name].get("repository", "instanto-io/" + name)
        if base_sha and base_sha != head_shas[name]:
            commit_ranges[name] = fetcher(repo, base_sha, head_shas[name])
        else:
            commit_ranges[name] = {
                "base_sha": base_sha,
                "head_sha": head_shas[name],
                "ahead_by": 0 if base_sha else None,
                "commits": [],
            }

    matrix_include = []
    for name in sorted(affected):
        cfg = projects[name]
        matrix_include.append(
            {
                "name": name,
                "repository": cfg.get("repository", "instanto-io/" + name),
                "sha": head_shas[name],
                "runner_labels": cfg.get(
                    "runner_labels", ["self-hosted", "instanto-container"]
                ),
                "install_playwright": bool(cfg.get("install_playwright", False)),
                "node_version": cfg.get("node_version", ""),
                "sonar_args": cfg.get("sonar_args", []),
            }
        )

    return {
        "mode": "run",
        "should_run": True,
        "changed": changed,
        "unhealthy_vs_green": sorted(unhealthy_vs_green),
        "affected": sorted(affected),
        "groups": groups,
        "matrix_include": matrix_include,
        "head_shas": head_shas,
        "commit_ranges": commit_ranges,
    }


def execute_nightly_groups(projects, groups, build):
    """Build dependency-ordered groups cleanly without single-seed special-casing."""
    results = {}
    for group in groups:
        external_deps = {
            dep
            for name in group
            for dep in projects[name]["dependencies"]
            if dep not in group and dep in results
        }
        if any(results[dep] != "passed" for dep in external_deps):
            for name in group:
                results[name] = "blocked"
            continue

        if len(group) == 1:
            name = group[0]
            results[name] = "passed" if build(name, "verify") else "failed"
            continue

        ready = True
        for name in group:
            if not build(name, "install-cycle"):
                results[name] = "failed"
                ready = False
        if ready:
            for name in group:
                results[name] = "passed" if build(name, "verify-cycle") else "failed"
        else:
            for name in group:
                results.setdefault(name, "blocked")
        if any(results.get(name) != "passed" for name in group):
            for name in group:
                if results.get(name) == "passed":
                    results[name] = "blocked"
    return results


def isolate_blame(
    projects,
    results,
    head_shas,
    baseline_projects,
    probe_fn,
    max_candidates=4,
    max_probe_closure=6,
):
    """Attribute failed builds to exact upstream commit changes where possible."""
    dirty_with_green = {
        name
        for name in projects
        if baseline_projects.get(name, {}).get("last_green_sha")
        and baseline_projects[name]["last_green_sha"] != head_shas.get(name)
    }
    changed_without_green = {
        name
        for name in projects
        if name in results
        and (
            not baseline_projects.get(name, {}).get("last_green_sha")
            or baseline_projects[name]["last_green_sha"] != head_shas.get(name)
        )
    }
    blame = {}
    for failed_name, status in sorted(results.items()):
        if status != "failed":
            continue
        lineage = ancestors_of(projects, failed_name) | {failed_name}
        suspects = sorted(lineage & changed_without_green)
        isolatable = sorted(lineage & dirty_with_green)

        if not suspects:
            blame[failed_name] = {
                "attribution": "unchanged-chain",
                "culprits": [],
                "detail": "Neither the failing repo nor any upstream changed since last green baseline.",
            }
            continue
        if len(suspects) == 1:
            blame[failed_name] = {
                "attribution": "single-change",
                "culprits": suspects,
                "detail": f"Only `{suspects[0]}` changed in this dependency chain since last green baseline.",
            }
            continue
        if len(suspects) > max_candidates or len(isolatable) != len(suspects):
            blame[failed_name] = {
                "attribution": "multiple-candidates",
                "culprits": suspects,
                "detail": (
                    f"{len(suspects)} repos changed without full green baselines or exceed isolation cap ({max_candidates})."
                ),
            }
            continue

        # Order suspects leaf-last so upstream culprits are checked before the consumer itself.
        ordered = [s for s in isolatable if s != failed_name] + (
            [failed_name] if failed_name in isolatable else []
        )
        closure_size = len(lineage & set(results))
        if closure_size > max_probe_closure:
            # Skip deep full-closure rebuild probes; isolate direct/changed suspects only if bounded.
            closure = (ancestors_of(projects, failed_name) & set(suspects)) | {failed_name}
            if len(closure) > max_probe_closure:
                blame[failed_name] = {
                    "attribution": "multiple-candidates",
                    "culprits": ordered,
                    "detail": (
                        f"Chain has {len(closure)} changed repos (> cap {max_probe_closure}); "
                        "see commit ranges for each suspect."
                    ),
                }
                continue

        culprits = []
        probe_outcomes = {}
        for suspect in ordered:
            ok = probe_fn(failed_name, suspect, ordered)
            probe_outcomes[suspect] = "passed" if ok else "failed"
            if not ok:
                culprits.append(suspect)

        if culprits:
            blame[failed_name] = {
                "attribution": "isolated",
                "culprits": culprits,
                "probes": probe_outcomes,
                "detail": "Pinning individual suspects against green baseline isolated failure.",
            }
        else:
            blame[failed_name] = {
                "attribution": "interaction-or-transient",
                "culprits": ordered,
                "probes": probe_outcomes,
                "detail": (
                    "No single suspect failed alone against green baseline; "
                    "failure likely requires combined changes across suspects."
                ),
            }
    return blame


def export_m2_overlay(repository_dir, archive_path, prefixes=("io/instanto", "eurocontrol")):
    repo_root = Path(repository_dir).resolve()
    archive_path = Path(archive_path)
    archive_path.parent.mkdir(parents=True, exist_ok=True)
    downstream.strip_remote_repositories(repo_root)
    added = 0
    with tarfile.open(archive_path, "w:gz") as bundle:
        for prefix in prefixes:
            target = repo_root / prefix
            if not target.exists():
                continue
            for item in sorted(target.rglob("*")):
                if item.is_file():
                    bundle.add(item, arcname=item.relative_to(repo_root).as_posix())
                    added += 1
    return added


def apply_m2_overlay(archive_path, repository_dir):
    repo_root = Path(repository_dir).resolve()
    repo_root.mkdir(parents=True, exist_ok=True)
    if not archive_path or not Path(archive_path).is_file():
        return 0
    with tarfile.open(archive_path, "r:gz") as bundle:
        members = bundle.getmembers()
        safe = []
        for member in members:
            if not (member.isreg() or member.isdir()):
                raise ValueError(f"Unsupported non-regular entry in overlay archive: {member.name}")
            dest = (repo_root / member.name).resolve()
            if not (dest == repo_root or str(dest).startswith(str(repo_root) + os.sep)):
                raise ValueError(f"Unsafe path in overlay archive: {member.name}")
            safe.append(member)
        bundle.extractall(repo_root, members=safe)
    downstream.strip_remote_repositories(repo_root)
    return sum(1 for member in safe if member.isreg())


def update_baseline(
    projects,
    baseline,
    plan_data,
    compat_results=None,
    stage3_results=None,
    timestamp=None,
):
    now = timestamp or utc_now()
    old_projects = baseline.get("projects", {})
    head_shas = plan_data.get("head_shas", {})
    compat_results = compat_results or {}
    stage3_results = stage3_results or {}

    updated = {}
    for name in sorted(projects):
        prev = dict(old_projects.get(name, {}))
        head = head_shas.get(name) or prev.get("last_seen_sha")
        prev_green = prev.get("last_green_sha")
        prev_sec = prev.get("last_security_sha")

        if plan_data.get("mode") == "bootstrap":
            updated[name] = {
                "last_seen_sha": head,
                "last_green_sha": head,
                "last_security_sha": prev_sec,
                "last_result": "bootstrapped",
                "last_checked_at": now,
            }
            continue

        if name not in compat_results:
            updated[name] = {
                "last_seen_sha": head,
                "last_green_sha": prev_green,
                "last_security_sha": prev_sec,
                "last_result": prev.get("last_result", "unchanged"),
                "last_checked_at": prev.get("last_checked_at"),
            }
            continue

        # Advance last_green_sha only when this repo, every built downstream dependant,
        # and Stage 3 (when Stage 3 ran for all passing compat repos) succeeded cleanly.
        dependants = downstream.affected_projects(projects, name) & compat_results.keys()
        chain_compat_ok = all(compat_results.get(d) == "passed" for d in dependants)
        sec_status = stage3_results.get(name)
        sec_ok = sec_status == "passed" if sec_status is not None else False
        chain_stage3_ok = bool(stage3_results) and all(
            stage3_results.get(d) == "passed"
            for d in dependants
            if compat_results.get(d) == "passed"
        )

        green_sha = head if (chain_compat_ok and sec_ok and chain_stage3_ok) else prev_green
        sec_sha = head if sec_ok else prev_sec
        updated[name] = {
            "last_seen_sha": head,
            "last_green_sha": green_sha,
            "last_security_sha": sec_sha,
            "last_result": sec_status or compat_results.get(name, "unknown"),
            "last_checked_at": now,
        }

    return {
        "schema_version": 1,
        "updated_at": now,
        "projects": updated,
    }


def render_plan_summary(plan_data):
    mode = plan_data.get("mode")
    lines = ["## Nightly Change Detection Plan", ""]
    if mode == "bootstrap":
        lines.append(
            f"Bootstrapped baseline for {len(plan_data.get('head_shas', {}))} projects without running builds."
        )
        return "\n".join(lines) + "\n"
    if not plan_data.get("should_run"):
        lines.append("No upstream library changes detected since the last checked nightly; downstream compatibility & security skipped.")
        unhealthy = plan_data.get("unhealthy_vs_green") or []
        if unhealthy:
            lines.append("")
            lines.append("Unresolved failures from earlier nightlies (unchanged today): " + ", ".join(f"`{p}`" for p in unhealthy))
        return "\n".join(lines) + "\n"

    lines.append(f"- **Changed / triggered**: {', '.join(f'`{c}`' for c in plan_data['changed'])}")
    lines.append(f"- **Affected projects ({len(plan_data['affected'])})**: {', '.join(f'`{a}`' for a in plan_data['affected'])}")
    commit_ranges = plan_data.get("commit_ranges") or {}
    if commit_ranges:
        lines.extend(["", "### Commit Ranges Since Last Green Nightly", ""])
        for name in sorted(commit_ranges):
            info = commit_ranges[name]
            base = (info.get("base_sha") or "none")[:12]
            head = (info.get("head_sha") or "")[:12]
            lines.append(f"- **`{name}`** (`{base}`..`{head}`)")
            for c in info.get("commits", [])[:5]:
                lines.append(f"  - `{c['sha']}` {c['message']} ({c['author']})")
    lines.append("")
    return "\n".join(lines)


def render_compat_summary(plan_data, results, blame):
    lines = ["## Nightly Downstream Compatibility", ""]
    lines.append("| Project | Revision | Result | Blame / Suspects |")
    lines.append("| --- | --- | --- | --- |")
    revisions = plan_data.get("head_shas", {})
    commit_ranges = plan_data.get("commit_ranges", {})
    for name in sorted(results):
        sha = (revisions.get(name) or "unknown")[:12]
        status = results[name]
        b = blame.get(name)
        note = ""
        if b:
            culprits = ", ".join(f"`{c}`" for c in b.get("culprits", [])) or "none"
            note = f"{b['attribution']}: {culprits} — {b['detail']}"
        lines.append(f"| `{name}` | `{sha}` | {status} | {note} |")

    if blame:
        lines.extend(["", "### Breakage Attribution Details", ""])
        for failed_name, info in sorted(blame.items()):
            lines.append(f"#### `{failed_name}` ({info['attribution']})")
            lines.append(info["detail"])
            for culprit in info.get("culprits", []):
                cr = commit_ranges.get(culprit)
                if cr:
                    base = (cr.get("base_sha") or "initial")[:12]
                    head = (cr.get("head_sha") or "")[:12]
                    lines.append(f"- Suspect `{culprit}` (`{base}`..`{head}`):")
                    for c in cr.get("commits", [])[:10]:
                        lines.append(f"  - `{c['sha']}` {c['message']} ({c['author']})")
            lines.append("")

    lines.append(
        "Compatibility builds run against pinned commits inside an isolated Maven repository; "
        "no snapshot deployments occur during compatibility or blame isolation."
    )
    return "\n".join(lines) + "\n"


def cmd_plan(args):
    projects = downstream.load_catalog(args.catalog)
    baseline = load_baseline(args.baseline)
    head_shas = fetch_head_shas(projects)
    forced = [x.strip() for x in args.upstream.split(",") if x.strip()] if args.upstream else None
    plan_data = plan_nightly(
        projects,
        head_shas,
        baseline,
        forced_upstreams=forced,
        force_all=args.force,
        bootstrap_only=args.bootstrap_only,
    )
    out = args.output_dir
    out.mkdir(parents=True, exist_ok=True)
    (out / "plan.json").write_text(json.dumps(plan_data, indent=2) + "\n", encoding="utf-8")
    summary = render_plan_summary(plan_data)
    (out / "plan-summary.md").write_text(summary, encoding="utf-8")
    if args.bootstrap_only:
        boot = update_baseline(projects, baseline, plan_data)
        (out / "bootstrap-baseline.json").write_text(
            json.dumps(boot, indent=2) + "\n", encoding="utf-8"
        )
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as stream:
            stream.write(summary)
    if os.environ.get("GITHUB_OUTPUT"):
        with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as stream:
            stream.write(f"should_run={'true' if plan_data['should_run'] else 'false'}\n")
            stream.write(f"mode={plan_data['mode']}\n")
            stream.write("matrix_include=" + json.dumps(plan_data["matrix_include"]) + "\n")
    print(summary, flush=True)
    return 0


def cmd_compat(args):
    projects = downstream.load_catalog(args.catalog)
    plan_data = json.loads(args.plan.read_text(encoding="utf-8"))
    baseline = load_baseline(args.baseline)
    if not plan_data.get("should_run"):
        print("Plan indicates no compatibility build needed.")
        return 0

    workspace = args.workspace.resolve()
    workspace.mkdir(parents=True, exist_ok=True)
    output = workspace / "reports"
    output.mkdir(exist_ok=True)
    repository = workspace / "m2"
    repository.mkdir(exist_ok=True)

    affected = set(plan_data["affected"])
    head_shas = plan_data["head_shas"]
    sources = {}
    checkout_failed = set()
    for name in sorted(affected):
        dest = workspace / "src" / name
        dest.parent.mkdir(parents=True, exist_ok=True)
        sources[name] = dest
        repo = projects[name].get("repository", "instanto-io/" + name)
        try:
            downstream.checkout(repo, dest, sha=head_shas[name])
        except (OSError, subprocess.SubprocessError) as error:
            print(f"::error::Cannot check out {name}@{head_shas[name]}: {type(error).__name__}")
            checkout_failed.add(name)

    settings = args.settings.resolve() if args.settings else workspace / "settings.xml"
    if args.settings is None:
        downstream.write_settings(settings, sources.values())
    central_settings = workspace / "central-settings.xml"
    downstream.write_central_settings(central_settings)

    def build(name, phase):
        if name in checkout_failed:
            return False
        print(f"::group::{name} {phase} at {head_shas[name]}", flush=True)
        ph = _placeholders(
            sources[name],
            repository,
            settings,
            runner_temp=os.environ.get("RUNNER_TEMP", str(workspace)),
            central_settings=central_settings,
        )
        repo_env = downstream.expand_env(projects[name].get("env", {}), ph)
        # Fresh m2 fetches unchanged dependencies once from Forgejo; --no-snapshot-updates ensures
        # locally installed changed libraries are never overwritten by remote snapshots.
        arguments = [
            "--no-snapshot-updates",
            *downstream.expand_placeholders(projects[name].get("arguments", []), ph),
            "clean",
            "install",
        ]
        skip_tests = phase == "install-cycle"
        if skip_tests:
            arguments.append("-DskipTests")
        arguments.extend(downstream.fast_skip_flags(arguments, skip_jacoco=True))
        try:
            ok = downstream.run_maven(
                sources[name],
                arguments,
                repository,
                settings,
                output / f"{name}-{phase}.log",
                env=repo_env,
            )
            downstream.strip_remote_repositories(repository)
        except OSError as error:
            print(f"::error::Cannot build {name}: {type(error).__name__}")
            ok = False
        if ok and not skip_tests:
            for index, check in enumerate(projects[name].get("checks", [])):
                check_args = [
                    "--no-snapshot-updates",
                    *downstream.expand_placeholders(check, ph),
                ]
                check_args.extend(downstream.fast_skip_flags(check_args, skip_jacoco=True))
                ok = downstream.run_maven(
                    sources[name],
                    check_args,
                    repository,
                    settings,
                    output / f"{name}-{phase}-check-{index}.log",
                    env=repo_env,
                )
                downstream.strip_remote_repositories(repository)
                if not ok:
                    break
        print("::endgroup::", flush=True)
        return ok

    results = execute_nightly_groups(projects, plan_data["groups"], build)

    base_projects = baseline.get("projects", {})

    def probe_blame(failed_name, suspect, all_suspects):
        probe_dir = workspace / "blame-probes" / f"{failed_name}__with__{suspect}"
        if probe_dir.exists():
            shutil.rmtree(probe_dir)
        probe_repo = probe_dir / "m2"
        probe_repo.mkdir(parents=True)
        probe_logs = probe_dir / "logs"
        probe_logs.mkdir()
        closure = (ancestors_of(projects, failed_name) & set(all_suspects)) | {failed_name}
        probe_groups = downstream.build_groups(projects, closure)
        probe_sources = {}
        for member in sorted(closure):
            sha = (
                head_shas[member]
                if member == suspect
                else base_projects.get(member, {}).get("last_green_sha") or head_shas[member]
            )
            dest = probe_dir / "src" / member
            dest.parent.mkdir(parents=True, exist_ok=True)
            probe_sources[member] = dest
            try:
                downstream.checkout(
                    projects[member].get("repository", "instanto-io/" + member),
                    dest,
                    sha=sha,
                )
            except (OSError, subprocess.SubprocessError):
                return False
        probe_settings = probe_dir / "settings.xml"
        probe_central_settings = probe_dir / "central-settings.xml"
        downstream.write_settings(probe_settings, probe_sources.values())
        downstream.write_central_settings(probe_central_settings)

        def probe_build(member, phase):
            # Suspects above the failing target only need fast compilation+install; only target runs checks.
            is_target = member == failed_name and phase != "install-cycle"
            ph = _placeholders(
                probe_sources[member],
                probe_repo,
                probe_settings,
                runner_temp=os.environ.get("RUNNER_TEMP", str(probe_dir)),
                central_settings=probe_central_settings,
            )
            repo_env = downstream.expand_env(projects[member].get("env", {}), ph)
            cmd_args = [
                "--no-snapshot-updates",
                *downstream.expand_placeholders(projects[member].get("arguments", []), ph),
                "clean",
                "install",
            ]
            if not is_target:
                cmd_args.append("-DskipTests")
            cmd_args.extend(downstream.fast_skip_flags(cmd_args, skip_jacoco=True))
            ok = downstream.run_maven(
                probe_sources[member],
                cmd_args,
                probe_repo,
                probe_settings,
                probe_logs / f"{member}-{phase}.log",
                env=repo_env,
            )
            downstream.strip_remote_repositories(probe_repo)
            if ok and is_target:
                for idx, check in enumerate(projects[member].get("checks", [])):
                    c_args = [
                        "--no-snapshot-updates",
                        *downstream.expand_placeholders(check, ph),
                    ]
                    c_args.extend(downstream.fast_skip_flags(c_args, skip_jacoco=True))
                    ok = downstream.run_maven(
                        probe_sources[member],
                        c_args,
                        probe_repo,
                        probe_settings,
                        probe_logs / f"{member}-check-{idx}.log",
                        env=repo_env,
                    )
                    downstream.strip_remote_repositories(probe_repo)
                    if not ok:
                        break
            return ok

        probe_res = execute_nightly_groups(projects, probe_groups, probe_build)
        return probe_res.get(failed_name) == "passed"

    blame = isolate_blame(
        projects,
        results,
        head_shas,
        base_projects,
        probe_blame,
        max_candidates=args.max_blame_candidates,
    )

    compat_data = {
        "changed": plan_data["changed"],
        "revisions": {k: head_shas[k] for k in affected},
        "groups": plan_data["groups"],
        "results": results,
        "blame": blame,
    }
    (output / "compat-results.json").write_text(
        json.dumps(compat_data, indent=2) + "\n", encoding="utf-8"
    )
    summary = render_compat_summary(plan_data, results, blame)
    (output / "compat-summary.md").write_text(summary, encoding="utf-8")
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as stream:
            stream.write(summary)
    print(summary, flush=True)

    all_passed = all(v == "passed" for v in results.values())
    if all_passed and args.overlay_out:
        count = export_m2_overlay(repository, args.overlay_out)
        print(f"Exported {count} verified snapshot files to {args.overlay_out}")
    return 0 if all_passed else 1


def run_verify_project(
    cfg,
    project_name,
    checkout_dir,
    repository,
    settings,
    log_dir,
    overlay=None,
    runner_temp=None,
):
    checkout_dir = checkout_dir.resolve()
    repository = repository.resolve()
    settings = settings.resolve()
    log_dir = log_dir.resolve()
    log_dir.mkdir(parents=True, exist_ok=True)

    if overlay and overlay.is_file():
        restored = apply_m2_overlay(overlay, repository)
        print(f"Restored {restored} snapshot entries from compatibility overlay")

    central_settings = settings.with_name("central-settings.xml")
    downstream.write_settings(settings, [checkout_dir])
    downstream.write_central_settings(central_settings)
    ph = _placeholders(
        checkout_dir,
        repository,
        settings,
        runner_temp=runner_temp,
        central_settings=central_settings,
    )
    repo_env = downstream.expand_env(cfg.get("env", {}), ph)
    verify_goals = downstream.expand_placeholders(cfg.get("verify_goals", ["clean", "verify"]), ph)
    base_args = [
        "--no-snapshot-updates",
        *downstream.expand_placeholders(cfg.get("arguments", []), ph),
        *verify_goals,
    ]
    # Keep JaCoCo and SpotBugs active on full nightly runs (unless project itself skips Spotless etc.).
    ok = downstream.run_maven(
        checkout_dir,
        base_args,
        repository,
        settings,
        log_dir / f"{project_name}-verify.log",
        env=repo_env,
    )
    downstream.strip_remote_repositories(repository)
    if ok:
        checks = cfg.get("full_checks") or cfg.get("checks", [])
        for idx, check in enumerate(checks):
            check_args = ["--no-snapshot-updates", *downstream.expand_placeholders(check, ph)]
            ok = downstream.run_maven(
                checkout_dir,
                check_args,
                repository,
                settings,
                log_dir / f"{project_name}-fullcheck-{idx}.log",
                env=repo_env,
            )
            downstream.strip_remote_repositories(repository)
            if not ok:
                break
    if ok:
        sonar_present = bool(os.environ.get("SONAR_TOKEN", "").strip())
        for idx, step in enumerate(cfg.get("post_verify_commands", [])):
            if step.get("if_sonar") and not sonar_present:
                continue
            cmd = downstream.expand_placeholders(step["cmd"], ph)
            step_env = {
                **repo_env,
                "MAVEN_SETTINGS": str(settings),
                "MAVEN_REPO_LOCAL": str(repository),
                **downstream.expand_env(step.get("env", {}), ph),
            }
            if "cwd" in step:
                cwd_rel = downstream.expand_placeholders([step["cwd"]], ph)[0]
                cwd = (checkout_dir / cwd_rel).resolve()
            else:
                cwd = checkout_dir
            print(f"::group::{project_name} post-verify {idx}: {' '.join(cmd)}", flush=True)
            try:
                step_ok = run_command_logged(
                    cmd,
                    cwd,
                    log_dir / f"{project_name}-postverify-{idx}.log",
                    env=step_env,
                )
            except OSError as error:
                print(f"::error::Cannot run post-verify step {idx} for {project_name}: {type(error).__name__}")
                step_ok = False
            downstream.strip_remote_repositories(repository)
            print("::endgroup::", flush=True)
            if not step_ok and not step.get("allow_failure", False):
                ok = False
                break
    return ok


def cmd_run_verify(args):
    projects = downstream.load_catalog(args.catalog)
    ok = run_verify_project(
        projects[args.project],
        args.project,
        args.checkout,
        args.repository,
        args.settings,
        args.log_dir,
        overlay=args.overlay,
    )
    return 0 if ok else 1


def cmd_run_sonar(args):
    projects = downstream.load_catalog(args.catalog)
    cfg = projects[args.project]
    checkout_dir = args.checkout.resolve()
    repository = args.repository.resolve()
    settings = args.settings.resolve()
    log_dir = args.log_dir.resolve()
    log_dir.mkdir(parents=True, exist_ok=True)
    downstream.write_settings(settings, [checkout_dir])
    ph = _placeholders(checkout_dir, repository, settings)
    repo_env = downstream.expand_env(cfg.get("env", {}), ph)

    project_key = os.environ.get("SONAR_PROJECT_KEY", "").strip() or args.project
    sonar_args = [
        "--no-snapshot-updates",
        "sonar:sonar",
        f"-Dsonar.projectKey={project_key}",
        f"-Dsonar.projectName={project_key}",
        "-Dsonar.qualitygate.wait=true",
        *downstream.expand_placeholders(cfg.get("sonar_args", []), ph),
    ]
    ok = downstream.run_maven(
        checkout_dir,
        sonar_args,
        repository,
        settings,
        log_dir / f"{args.project}-sonar.log",
        env=repo_env,
    )
    downstream.strip_remote_repositories(repository)
    return 0 if ok else 1


def cmd_update_baseline(args):
    projects = downstream.load_catalog(args.catalog)
    baseline = load_baseline(args.baseline)
    plan_data = json.loads(args.plan.read_text(encoding="utf-8"))
    compat_data = (
        json.loads(args.compat_results.read_text(encoding="utf-8"))
        if args.compat_results and args.compat_results.is_file()
        else {}
    )
    stage3_results = {}
    for raw in args.stage3_status or []:
        if "=" in raw:
            repo, status = raw.split("=", 1)
            stage3_results[repo.strip()] = status.strip()
    new_baseline = update_baseline(
        projects,
        baseline,
        plan_data,
        compat_results=compat_data.get("results"),
        stage3_results=stage3_results,
    )
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(new_baseline, indent=2) + "\n", encoding="utf-8")
    return 0


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="cmd", required=True)

    p_plan = sub.add_parser("plan", help="Detect changes since baseline and plan nightly run")
    p_plan.add_argument("--catalog", type=Path, required=True)
    p_plan.add_argument("--baseline", type=Path)
    p_plan.add_argument("--output-dir", type=Path, required=True)
    p_plan.add_argument("--upstream", default="")
    p_plan.add_argument("--force", action="store_true")
    p_plan.add_argument("--bootstrap-only", action="store_true")

    p_compat = sub.add_parser("compat", help="Run incremental downstream compatibility + blame")
    p_compat.add_argument("--catalog", type=Path, required=True)
    p_compat.add_argument("--plan", type=Path, required=True)
    p_compat.add_argument("--baseline", type=Path)
    p_compat.add_argument("--workspace", type=Path, required=True)
    p_compat.add_argument("--settings", type=Path)
    p_compat.add_argument("--overlay-out", type=Path)
    p_compat.add_argument("--max-blame-candidates", type=int, default=4)

    p_verify = sub.add_parser("run-verify", help="Run full verification suite for one repo")
    p_verify.add_argument("--catalog", type=Path, required=True)
    p_verify.add_argument("--project", required=True)
    p_verify.add_argument("--checkout", type=Path, required=True)
    p_verify.add_argument("--repository", type=Path, required=True)
    p_verify.add_argument("--settings", type=Path, required=True)
    p_verify.add_argument("--log-dir", type=Path, required=True)
    p_verify.add_argument("--overlay", type=Path)

    p_sonar = sub.add_parser("run-sonar", help="Run SonarQube analysis for one repo")
    p_sonar.add_argument("--catalog", type=Path, required=True)
    p_sonar.add_argument("--project", required=True)
    p_sonar.add_argument("--checkout", type=Path, required=True)
    p_sonar.add_argument("--repository", type=Path, required=True)
    p_sonar.add_argument("--settings", type=Path, required=True)
    p_sonar.add_argument("--log-dir", type=Path, required=True)

    p_upd = sub.add_parser("update-baseline", help="Write updated baseline JSON")
    p_upd.add_argument("--catalog", type=Path, required=True)
    p_upd.add_argument("--baseline", type=Path)
    p_upd.add_argument("--plan", type=Path, required=True)
    p_upd.add_argument("--compat-results", type=Path)
    p_upd.add_argument("--stage3-status", action="append", default=[])
    p_upd.add_argument("--out", type=Path, required=True)

    args = parser.parse_args()
    if args.cmd == "plan":
        return cmd_plan(args)
    if args.cmd == "compat":
        return cmd_compat(args)
    if args.cmd == "run-verify":
        return cmd_run_verify(args)
    if args.cmd == "run-sonar":
        return cmd_run_sonar(args)
    return cmd_update_baseline(args)


if __name__ == "__main__":
    sys.exit(main())

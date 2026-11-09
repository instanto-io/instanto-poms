#!/usr/bin/env python3
"""Aggregate SpotBugs, CycloneDX SBOM, Trivy CVEs, test results, and coverage metrics."""

import argparse
import json
import os
from pathlib import Path
import sys
import xml.etree.ElementTree as ET


SEVERITY_ORDER = {"CRITICAL": 0, "HIGH": 1, "MEDIUM": 2, "LOW": 3, "UNKNOWN": 4}
STAGING_TARGET_DIRS = {
    "compatibility-verification",
    "quality-review",
    "pages",
    "coverage-inheritance",
    "terser-inheritance",
}


def _is_primary_target_artifact(root, path):
    try:
        parts = path.relative_to(root).parts
    except ValueError:
        return False
    if any(part in {".build", ".compat-baseline", "node_modules", ".git"} for part in parts):
        return False
    target_indices = [i for i, part in enumerate(parts) if part == "target"]
    if len(target_indices) > 1:
        return False
    if target_indices:
        idx = target_indices[0]
        if idx + 1 < len(parts) and parts[idx + 1] in STAGING_TARGET_DIRS:
            return False
    return True


def _unique_paths(paths):
    seen = set()
    unique = []
    for path in sorted(paths):
        resolved = path.resolve()
        if resolved not in seen:
            seen.add(resolved)
            unique.append(path)
    return unique


def _collect_spotbugs(root):
    counts = {"high": 0, "medium": 0, "low": 0}
    merged = root / "target" / "spotbugs-reports" / "spotbugs.sarif"
    if merged.is_file():
        sarif_files = [merged]
    else:
        sarif_files = _unique_paths(
            p
            for p in root.rglob("*.sarif")
            if "/target/spotbugs/" in p.as_posix() and _is_primary_target_artifact(root, p)
        )
    if sarif_files:
        for path in sarif_files:
            try:
                data = json.loads(path.read_text(encoding="utf-8"))
            except (OSError, json.JSONDecodeError):
                continue
            for run in data.get("runs", []):
                for result in run.get("results", []):
                    level = result.get("level", "warning")
                    if level == "error":
                        counts["high"] += 1
                    elif level == "note":
                        counts["low"] += 1
                    else:
                        counts["medium"] += 1
        return {**counts, "files": len(sarif_files)}

    xml_files = _unique_paths(
        p
        for p in root.rglob("*-spotbugs.xml")
        if "/target/spotbugs/" in p.as_posix() and _is_primary_target_artifact(root, p)
    )
    for path in xml_files:
        try:
            tree = ET.parse(path)
        except (OSError, ET.ParseError):
            continue
        for bug in tree.getroot().findall(".//BugInstance"):
            priority = bug.get("priority", "2")
            if priority == "1":
                counts["high"] += 1
            elif priority == "3":
                counts["low"] += 1
            else:
                counts["medium"] += 1
    return {**counts, "files": len(xml_files)}


def _collect_sbom(root):
    top = root / "target" / "bom.json"
    candidates = (
        [top]
        if top.is_file()
        else _unique_paths(
            p for p in root.rglob("target/bom.json") if _is_primary_target_artifact(root, p)
        )
    )
    components = 0
    found = 0
    seen_purls = set()
    for path in candidates:
        if not path.is_file():
            continue
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            continue
        found += 1
        for comp in data.get("components", []):
            key = comp.get("purl") or (
                comp.get("group"),
                comp.get("name"),
                comp.get("version"),
            )
            if key not in seen_purls:
                seen_purls.add(key)
                components += 1
    return {"bom_files": found, "components": components}


def _collect_trivy(trivy_paths):
    counts = {k: 0 for k in SEVERITY_ORDER}
    findings = {}
    scanned_files = 0
    for raw in trivy_paths:
        path = Path(raw)
        if not path.is_file():
            continue
        scanned_files += 1
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            continue
        for result in data.get("Results", []):
            target = result.get("Target", "")
            for vuln in result.get("Vulnerabilities") or []:
                vuln_id = vuln.get("VulnerabilityID", "")
                pkg = vuln.get("PkgName", "")
                installed = vuln.get("InstalledVersion", "")
                sev = str(vuln.get("Severity", "UNKNOWN")).upper()
                if sev not in SEVERITY_ORDER:
                    sev = "UNKNOWN"
                key = (vuln_id, pkg, installed)
                if key not in findings:
                    findings[key] = {
                        "id": vuln_id,
                        "package": pkg,
                        "installed_version": installed,
                        "fixed_version": vuln.get("FixedVersion", "") or "",
                        "severity": sev,
                        "title": vuln.get("Title", "") or "",
                        "target": target,
                    }
    for item in findings.values():
        counts[item["severity"]] += 1
    sorted_findings = sorted(
        findings.values(),
        key=lambda v: (SEVERITY_ORDER.get(v["severity"], 99), v["id"], v["package"]),
    )
    return {
        "scan_files": scanned_files,
        "counts": counts,
        "total": len(sorted_findings),
        "top_findings": sorted_findings[:25],
    }


def _collect_coverage_group(paths):
    line_cov = line_miss = branch_cov = branch_miss = 0
    reports = 0
    for path in sorted(paths):
        try:
            tree = ET.parse(path)
        except (OSError, ET.ParseError):
            continue
        root = tree.getroot()
        if root.tag == "report":
            reports += 1
            for counter in root.findall("./counter"):
                ctype = counter.get("type")
                cov = int(counter.get("covered", "0"))
                miss = int(counter.get("missed", "0"))
                if ctype == "LINE":
                    line_cov += cov
                    line_miss += miss
                elif ctype == "BRANCH":
                    branch_cov += cov
                    branch_miss += miss
        elif root.tag == "coverage":
            reports += 1
            for line_node in root.findall("./file/lineToCover"):
                if str(line_node.get("covered", "")).lower() == "true":
                    line_cov += 1
                else:
                    line_miss += 1
                branches = int(line_node.get("branchesToCover", "0") or "0")
                covered_branches = int(line_node.get("coveredBranches", "0") or "0")
                if branches > 0:
                    branch_cov += covered_branches
                    branch_miss += max(0, branches - covered_branches)
    line_total = line_cov + line_miss
    branch_total = branch_cov + branch_miss
    return {
        "reports": reports,
        "line_covered": line_cov,
        "line_missed": line_miss,
        "line_pct": round((100.0 * line_cov / line_total), 1) if line_total else None,
        "branch_covered": branch_cov,
        "branch_missed": branch_miss,
        "branch_pct": (
            round((100.0 * branch_cov / branch_total), 1) if branch_total else None
        ),
    }


def _teavm_covered_sources(paths):
    sources = set()
    for path in paths:
        try:
            tree = ET.parse(path)
        except (OSError, ET.ParseError):
            continue
        root = tree.getroot()
        if root.tag != "report":
            continue
        for pkg in root.findall("./package"):
            pkg_name = (pkg.get("name") or "").strip("/")
            for sf in pkg.findall("./sourcefile"):
                sf_name = (sf.get("name") or "").strip("/")
                if not sf_name:
                    continue
                sources.add(f"{pkg_name}/{sf_name}" if pkg_name else sf_name)
    return sources


def _browser_overlaps_teavm(path, teavm_sources):
    try:
        tree = ET.parse(path)
    except (OSError, ET.ParseError):
        return True
    root = tree.getroot()
    if root.tag != "coverage":
        return True
    files = root.findall("./file")
    if not files:
        return True
    if not teavm_sources:
        return False
    for file_node in files:
        fpath = (file_node.get("path") or "").replace("\\", "/").lstrip("./")
        if any(fpath == suffix or fpath.endswith("/" + suffix) for suffix in teavm_sources):
            return True
    return False


def _collect_coverage(root):
    jacoco_paths = _unique_paths(
        p
        for p in root.rglob("*.xml")
        if p.name in ("jacoco.xml", "jacocoTestReport.xml")
        and ("/target/site/jacoco/" in p.as_posix() or "/build/reports/jacoco/" in p.as_posix())
        and "/jacoco-aggregate" not in p.as_posix()
        and _is_primary_target_artifact(root, p)
    )
    teavm_paths = _unique_paths(
        p
        for p in root.rglob("teavm-coverage.xml")
        if "/target/" in p.as_posix() and _is_primary_target_artifact(root, p)
    )
    teavm_sources = _teavm_covered_sources(teavm_paths)
    browser_paths = [
        p
        for p in _unique_paths(
            p
            for p in root.rglob("browser-coverage.xml")
            if "/target/" in p.as_posix() and _is_primary_target_artifact(root, p)
        )
        if not _browser_overlaps_teavm(p, teavm_sources)
    ]
    return {
        "jacoco": _collect_coverage_group(jacoco_paths),
        "teacoco": _collect_coverage_group([*teavm_paths, *browser_paths]),
    }


def _collect_tests(root):
    tests = failures = errors = skipped = suites = 0
    candidate_paths = []
    for folder in ("surefire-reports", "failsafe-reports"):
        candidate_paths.extend(
            p
            for p in root.rglob(f"*{folder}/TEST-*.xml")
            if _is_primary_target_artifact(root, p)
        )
    candidate_paths.extend(
        p
        for p in root.rglob("TEST-*.xml")
        if "/build/test-results/" in p.as_posix() and _is_primary_target_artifact(root, p)
    )
    for path in _unique_paths(candidate_paths):
        try:
            node = ET.parse(path).getroot()
        except (OSError, ET.ParseError):
            continue
        if node.tag not in ("testsuite", "testsuites"):
            continue
        suite_nodes = (
            node.findall("./testsuite") if node.tag == "testsuites" else [node]
        )
        for suite in suite_nodes:
            suites += 1
            tests += int(suite.get("tests", "0"))
            failures += int(suite.get("failures", "0"))
            errors += int(suite.get("errors", "0"))
            skipped += int(suite.get("skipped", "0"))
    return {
        "suites": suites,
        "tests": tests,
        "failures": failures,
        "errors": errors,
        "skipped": skipped,
    }


def collect_repo_report(root, repo_name, revision="", trivy_files=()):
    root = Path(root)
    return {
        "repository": repo_name,
        "revision": revision,
        "tests": _collect_tests(root),
        "coverage": _collect_coverage(root),
        "spotbugs": _collect_spotbugs(root),
        "sbom": _collect_sbom(root),
        "cve": _collect_trivy(trivy_files),
    }


def _fmt_pct(val):
    return f"{val:.1f}%" if val is not None else "n/a"


def render_repo_markdown(report):
    t = report["tests"]
    cov_j = report["coverage"]["jacoco"]
    cov_t = report["coverage"]["teacoco"]
    sb = report["spotbugs"]
    sbom = report["sbom"]
    cve = report["cve"]
    c = cve["counts"]

    lines = [
        f"### Security & Coverage: `{report['repository']}`",
        "",
        f"Revision: `{report.get('revision') or 'local'}`",
        "",
        "| Check | Summary |",
        "| --- | --- |",
        (
            f"| Tests | {t['tests']} run, {t['failures']} failed, "
            f"{t['errors']} errors, {t['skipped']} skipped ({t['suites']} suites) |"
        ),
        (
            f"| JaCoCo coverage | line {_fmt_pct(cov_j['line_pct'])}, "
            f"branch {_fmt_pct(cov_j['branch_pct'])} ({cov_j['reports']} modules) |"
        ),
    ]
    if cov_t["reports"]:
        lines.append(
            f"| TeaCoCo browser coverage | line {_fmt_pct(cov_t['line_pct'])}, "
            f"branch {_fmt_pct(cov_t['branch_pct'])} ({cov_t['reports']} suites) |"
        )
    lines.extend(
        [
            (
                f"| SpotBugs | {sb['high']} high, {sb['medium']} medium, "
                f"{sb['low']} low ({sb['files']} SARIF/XML) |"
            ),
            (
                f"| CycloneDX SBOM | {sbom['components']} unique components "
                f"({sbom['bom_files']} BOMs) |"
            ),
            (
                f"| Dependency CVEs (Trivy) | {c['CRITICAL']} critical, {c['HIGH']} high, "
                f"{c['MEDIUM']} medium, {c['LOW']} low (total {cve['total']}) |"
            ),
        ]
    )
    if cve["top_findings"]:
        lines.extend(
            [
                "",
                "#### Top Dependency Vulnerabilities",
                "",
                "| Severity | CVE | Package | Installed | Fixed |",
                "| --- | --- | --- | --- | --- |",
            ]
        )
        for item in cve["top_findings"][:10]:
            lines.append(
                f"| {item['severity']} | `{item['id']}` | `{item['package']}` | "
                f"`{item['installed_version']}` | `{item['fixed_version'] or 'none'}` |"
            )
    lines.append("")
    return "\n".join(lines)


def render_dashboard(reports, compat_summary=None):
    lines = ["## Nightly Verification, Coverage & Security Dashboard", ""]
    if compat_summary:
        lines.append(compat_summary.strip())
        lines.append("")
    if not reports:
        lines.append("No verification/security reports were generated.")
        return "\n".join(lines) + "\n"

    lines.extend(
        [
            "### Repository Metrics",
            "",
            "| Project | Tests (Fail/Err) | JaCoCo Line | TeaCoCo Line | SpotBugs (H/M/L) | SBOM Deps | CVEs (C/H/M/L) |",
            "| --- | --- | --- | --- | --- | --- | --- |",
        ]
    )
    for rep in sorted(reports, key=lambda r: r["repository"]):
        t = rep["tests"]
        cj = rep["coverage"]["jacoco"]
        ct = rep["coverage"]["teacoco"]
        sb = rep["spotbugs"]
        c = rep["cve"]["counts"]
        sbom = rep["sbom"]
        lines.append(
            "| `{repo}` | {tests} ({fail}/{err}) | {jline} | {tline} | "
            "{sh}/{sm}/{sl} | {deps} | {cc}/{ch}/{cm}/{cl} |".format(
                repo=rep["repository"],
                tests=t["tests"],
                fail=t["failures"],
                err=t["errors"],
                jline=_fmt_pct(cj["line_pct"]),
                tline=_fmt_pct(ct["line_pct"]),
                sh=sb["high"],
                sm=sb["medium"],
                sl=sb["low"],
                deps=sbom["components"],
                cc=c["CRITICAL"],
                ch=c["HIGH"],
                cm=c["MEDIUM"],
                cl=c["LOW"],
            )
        )
    lines.append("")
    return "\n".join(lines)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="cmd", required=True)

    repo_cmd = sub.add_parser("repo", help="Collect reports for a single repository")
    repo_cmd.add_argument("--root", type=Path, default=Path.cwd())
    repo_cmd.add_argument("--repo", required=True)
    repo_cmd.add_argument("--revision", default="")
    repo_cmd.add_argument("--trivy-json", action="append", default=[])
    repo_cmd.add_argument("--json-out", type=Path, required=True)
    repo_cmd.add_argument("--md-out", type=Path, required=True)
    repo_cmd.add_argument("--append-step-summary", action="store_true")

    dash_cmd = sub.add_parser("dashboard", help="Aggregate per-repo reports into a dashboard")
    dash_cmd.add_argument("--input", action="append", default=[], help="Per-repo JSON files")
    dash_cmd.add_argument("--compat-summary", type=Path)
    dash_cmd.add_argument("--md-out", type=Path, required=True)
    dash_cmd.add_argument("--append-step-summary", action="store_true")

    args = parser.parse_args()
    if args.cmd == "repo":
        report = collect_repo_report(
            args.root, args.repo, revision=args.revision, trivy_files=args.trivy_json
        )
        args.json_out.parent.mkdir(parents=True, exist_ok=True)
        args.json_out.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
        md = render_repo_markdown(report)
        args.md_out.parent.mkdir(parents=True, exist_ok=True)
        args.md_out.write_text(md, encoding="utf-8")
        c = report["cve"]["counts"]
        if c["CRITICAL"] or c["HIGH"]:
            print(
                f"::warning title=Dependency CVEs in {args.repo}::"
                f"Found {c['CRITICAL']} CRITICAL and {c['HIGH']} HIGH vulnerabilities."
            )
        if args.append_step_summary and os.environ.get("GITHUB_STEP_SUMMARY"):
            with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as s:
                s.write(md)
        print(md, flush=True)
        return 0

    reports = []
    for item in args.input:
        p = Path(item)
        if p.is_file():
            reports.append(json.loads(p.read_text(encoding="utf-8")))
    compat_text = (
        args.compat_summary.read_text(encoding="utf-8")
        if args.compat_summary and args.compat_summary.is_file()
        else None
    )
    md = render_dashboard(reports, compat_text)
    args.md_out.parent.mkdir(parents=True, exist_ok=True)
    args.md_out.write_text(md, encoding="utf-8")
    if args.append_step_summary and os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as s:
            s.write(md)
    print(md, flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())

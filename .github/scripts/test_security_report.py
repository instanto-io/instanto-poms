"""Unit tests for security, SBOM, Trivy CVE, coverage, and test metric aggregation."""

import json
import tempfile
import unittest
from pathlib import Path

import security_report


class SecurityReportTests(unittest.TestCase):
    def test_collect_repo_report_aggregates_all_signals_and_deduplicates_cves(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            # Surefire + Failsafe XML
            sf = root / "mod-a" / "target" / "surefire-reports"
            sf.mkdir(parents=True)
            (sf / "TEST-SampleTest.xml").write_text(
                '<testsuite tests="10" failures="1" errors="0" skipped="2"/>',
                encoding="utf-8",
            )
            ff = root / "mod-b" / "target" / "failsafe-reports"
            ff.mkdir(parents=True)
            (ff / "TEST-SampleIT.xml").write_text(
                '<testsuites><testsuite tests="5" failures="0" errors="1" skipped="0"/></testsuites>',
                encoding="utf-8",
            )

            # JaCoCo (report-level counter + nested package counter that must not double-count)
            jac = root / "mod-a" / "target" / "site" / "jacoco"
            jac.mkdir(parents=True)
            (jac / "jacoco.xml").write_text(
                '<report name="mod-a">'
                '<package name="p"><counter type="LINE" missed="2" covered="8"/></package>'
                '<counter type="LINE" missed="2" covered="8"/>'
                '<counter type="BRANCH" missed="1" covered="3"/>'
                "</report>",
                encoding="utf-8",
            )

            # TeaCoCo coverage
            (root / "mod-a" / "target" / "teavm-coverage.xml").write_text(
                '<report name="teavm"><counter type="LINE" missed="1" covered="9"/></report>',
                encoding="utf-8",
            )

            # SpotBugs SARIF
            sb_dir = root / "target" / "spotbugs-reports"
            sb_dir.mkdir(parents=True)
            (sb_dir / "spotbugs.sarif").write_text(
                json.dumps(
                    {
                        "runs": [
                            {
                                "results": [
                                    {"level": "error"},
                                    {"level": "warning"},
                                    {"level": "note"},
                                ]
                            }
                        ]
                    }
                ),
                encoding="utf-8",
            )

            # CycloneDX SBOM
            (root / "target" / "bom.json").write_text(
                json.dumps(
                    {
                        "components": [
                            {"purl": "pkg:maven/a/b@1.0"},
                            {"purl": "pkg:maven/c/d@2.0"},
                            {"purl": "pkg:maven/a/b@1.0"},
                        ]
                    }
                ),
                encoding="utf-8",
            )

            # Two Trivy JSON files with one duplicate CVE across SBOM and FS scan
            trivy_sbom = root / "trivy-sbom.json"
            trivy_fs = root / "trivy-fs.json"
            trivy_sbom.write_text(
                json.dumps(
                    {
                        "Results": [
                            {
                                "Target": "bom.json",
                                "Vulnerabilities": [
                                    {
                                        "VulnerabilityID": "CVE-2025-0001",
                                        "PkgName": "foo:bar",
                                        "InstalledVersion": "1.0.0",
                                        "FixedVersion": "1.0.1",
                                        "Severity": "CRITICAL",
                                    }
                                ],
                            }
                        ]
                    }
                ),
                encoding="utf-8",
            )
            trivy_fs.write_text(
                json.dumps(
                    {
                        "Results": [
                            {
                                "Target": "pom.xml",
                                "Vulnerabilities": [
                                    {
                                        "VulnerabilityID": "CVE-2025-0001",
                                        "PkgName": "foo:bar",
                                        "InstalledVersion": "1.0.0",
                                        "FixedVersion": "1.0.1",
                                        "Severity": "CRITICAL",
                                    },
                                    {
                                        "VulnerabilityID": "CVE-2025-0002",
                                        "PkgName": "baz:qux",
                                        "InstalledVersion": "2.0",
                                        "Severity": "HIGH",
                                    },
                                ],
                            }
                        ]
                    }
                ),
                encoding="utf-8",
            )

            rep = security_report.collect_repo_report(
                root, "sample-repo", revision="abc123", trivy_files=[trivy_sbom, trivy_fs]
            )
            self.assertEqual(rep["tests"], {"suites": 2, "tests": 15, "failures": 1, "errors": 1, "skipped": 2})
            self.assertEqual(rep["coverage"]["jacoco"]["line_pct"], 80.0)
            self.assertEqual(rep["coverage"]["jacoco"]["branch_pct"], 75.0)
            self.assertEqual(rep["coverage"]["teacoco"]["line_pct"], 90.0)
            self.assertEqual(rep["spotbugs"], {"high": 1, "medium": 1, "low": 1, "files": 1})
            self.assertEqual(rep["sbom"], {"bom_files": 1, "components": 2})
            self.assertEqual(rep["cve"]["total"], 2)
            self.assertEqual(rep["cve"]["counts"]["CRITICAL"], 1)
            self.assertEqual(rep["cve"]["counts"]["HIGH"], 1)

            md = security_report.render_repo_markdown(rep)
            self.assertIn("CVE-2025-0001", md)
            dash = security_report.render_dashboard([rep], "## Compat\nAll green")
            self.assertIn("sample-repo", dash)
            self.assertIn("80.0%", dash)

    def test_staging_dirs_are_excluded_and_overlapping_browser_coverage_is_not_double_counted(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            # Primary teavm coverage covering Foo.java
            mod = root / "mod" / "target"
            mod.mkdir(parents=True)
            (mod / "teavm-coverage.xml").write_text(
                '<report name="t">'
                '<package name="io/instanto">'
                '<sourcefile name="Foo.java"/>'
                '<counter type="LINE" missed="2" covered="8"/>'
                "</package>"
                '<counter type="LINE" missed="2" covered="8"/>'
                "</report>",
                encoding="utf-8",
            )
            # Overlapping browser-coverage.xml referencing io/instanto/Foo.java -> must be ignored
            top = root / "target"
            top.mkdir(parents=True)
            (top / "browser-coverage.xml").write_text(
                '<coverage version="1"><file path="mod/src/main/java/io/instanto/Foo.java">'
                '<lineToCover lineNumber="1" covered="true"/></file></coverage>',
                encoding="utf-8",
            )
            # Disjoint browser-coverage.xml referencing com/other/Bar.java -> must be counted
            disjoint = root / "other" / "target"
            disjoint.mkdir(parents=True)
            (disjoint / "browser-coverage.xml").write_text(
                '<coverage version="1"><file path="other/src/main/java/com/other/Bar.java">'
                '<lineToCover lineNumber="1" covered="true"/><lineToCover lineNumber="2" covered="false"/></file></coverage>',
                encoding="utf-8",
            )
            # Staging / nested copies that MUST be ignored
            for bad in (
                root / "target" / "compatibility-verification" / "mod" / "target" / "surefire-reports" / "TEST-X.xml",
                root / ".compat-baseline" / "m" / "target" / "surefire-reports" / "TEST-Y.xml",
                root / "target" / "terser-inheritance" / "classic" / "target" / "surefire-reports" / "TEST-Z.xml",
            ):
                bad.parent.mkdir(parents=True, exist_ok=True)
                bad.write_text('<testsuite tests="99" failures="0" errors="0" skipped="0"/>', encoding="utf-8")

            # Gradle JUnit + JaCoCo XML (like teacoco)
            g_test = root / "gradle-plugin" / "build" / "test-results" / "test"
            g_test.mkdir(parents=True)
            (g_test / "TEST-GradleTest.xml").write_text(
                '<testsuite tests="4" failures="0" errors="0" skipped="1"/>', encoding="utf-8"
            )
            g_jac = root / "gradle-plugin" / "build" / "reports" / "jacoco" / "test"
            g_jac.mkdir(parents=True)
            (g_jac / "jacocoTestReport.xml").write_text(
                '<report name="g"><counter type="LINE" missed="1" covered="4"/></report>', encoding="utf-8"
            )

            rep = security_report.collect_repo_report(root, "mixed-repo")
            # 8 + 1 covered, 2 + 1 missed = 9 / 12 = 75.0% (overlapping Foo.java skipped)
            self.assertEqual(rep["coverage"]["teacoco"]["reports"], 2)
            self.assertEqual(rep["coverage"]["teacoco"]["line_covered"], 9)
            self.assertEqual(rep["coverage"]["teacoco"]["line_missed"], 3)
            self.assertEqual(rep["coverage"]["jacoco"]["line_covered"], 4)
            # Only Gradle's 4 tests counted, NOT staging/compat/nested 99s
            self.assertEqual(rep["tests"]["tests"], 4)
            self.assertEqual(rep["tests"]["suites"], 1)


if __name__ == "__main__":
    unittest.main()

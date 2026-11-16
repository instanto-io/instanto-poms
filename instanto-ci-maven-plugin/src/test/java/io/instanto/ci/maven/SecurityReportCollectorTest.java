package io.instanto.ci.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SecurityReportCollectorTest {

  @Test
  void collectsRepoMetricsDeduplicatesCvesAndRendersOrgDashboard(@TempDir Path tempDir)
      throws Exception {
    // 1. Surefire & Failsafe
    Path surefire = tempDir.resolve("mod-a/target/surefire-reports");
    Files.createDirectories(surefire);
    Files.writeString(
        surefire.resolve("TEST-io.instanto.UnitTest.xml"),
        "<testsuite name=\"io.instanto.UnitTest\" tests=\"10\" failures=\"1\" errors=\"0\" skipped=\"2\"/>\n");

    Path failsafe = tempDir.resolve("mod-a/target/failsafe-reports");
    Files.createDirectories(failsafe);
    Files.writeString(
        failsafe.resolve("TEST-io.instanto.IntegrationIT.xml"),
        "<testsuite name=\"io.instanto.IntegrationIT\" tests=\"5\" failures=\"0\" errors=\"0\" skipped=\"0\"/>\n");

    // 2. Coverage: JaCoCo + TeaCoCo + overlapping browser-coverage.xml
    Path jacocoDir = tempDir.resolve("mod-a/target/site/jacoco");
    Files.createDirectories(jacocoDir);
    Files.writeString(
        jacocoDir.resolve("jacoco.xml"),
        """
        <report name="mod-a">
          <counter type="LINE" missed="20" covered="80"/>
          <counter type="BRANCH" missed="5" covered="15"/>
        </report>
        """);

    Path teacocoDir = tempDir.resolve("mod-a/target/site/teacoco");
    Files.createDirectories(teacocoDir);
    Files.writeString(
        teacocoDir.resolve("teavm-coverage.xml"),
        """
        <report name="teacoco">
          <package name="io/instanto">
            <sourcefile name="Widget.java">
              <line nr="10" mi="0" ci="3"/>
            </sourcefile>
          </package>
          <counter type="LINE" missed="10" covered="40"/>
          <counter type="BRANCH" missed="2" covered="8"/>
        </report>
        """);

    // Browser coverage that overlaps Widget.java should be skipped in favour of TeaCoCo
    Files.writeString(
        tempDir.resolve("mod-a/target/browser-coverage.xml"),
        """
        <report name="browser">
          <package name="io/instanto">
            <sourcefile name="Widget.java">
              <line nr="10" mi="0" ci="1"/>
            </sourcefile>
          </package>
          <counter type="LINE" missed="99" covered="1"/>
        </report>
        """);

    // 3. CycloneDX BOM
    Path targetDir = tempDir.resolve("target");
    Files.createDirectories(targetDir);
    Files.writeString(
        targetDir.resolve("bom.json"),
        """
        {
          "bomFormat": "CycloneDX",
          "components": [
            {"group": "com.google.guava", "name": "guava", "version": "33.0.0-jre", "purl": "pkg:maven/com.google.guava/guava@33.0.0-jre"}
          ]
        }
        """);

    // 4. Trivy JSON
    Path trivyJson = tempDir.resolve("target/security/trivy-sbom-cve.json");
    Files.createDirectories(trivyJson.getParent());
    Files.writeString(
        trivyJson,
        """
        {
          "Results": [{
            "Target": "pom.xml",
            "Vulnerabilities": [
              {
                "VulnerabilityID": "CVE-2026-0001",
                "PkgName": "org.example:vuln-lib",
                "InstalledVersion": "1.0.0",
                "FixedVersion": "1.0.1",
                "Severity": "CRITICAL",
                "Title": "Remote code execution"
              },
              {
                "VulnerabilityID": "CVE-2026-0001",
                "PkgName": "org.example:vuln-lib",
                "InstalledVersion": "1.0.0",
                "FixedVersion": "1.0.1",
                "Severity": "CRITICAL",
                "Title": "Duplicate entry"
              }
            ]
          }]
        }
        """);

    SecurityReportCollector collector = new SecurityReportCollector();
    ObjectNode summary =
        collector.collectRepoReport(tempDir, "sarto-scene", "abc1234567890", List.of(trivyJson));

    assertEquals(15, summary.path("tests").path("tests").asInt());
    assertEquals(1, summary.path("tests").path("failures").asInt());
    assertEquals(80, summary.path("coverage").path("jacoco").path("line_covered").asInt());
    assertEquals(20, summary.path("coverage").path("jacoco").path("line_missed").asInt());
    assertEquals(40, summary.path("coverage").path("teacoco").path("line_covered").asInt());
    assertEquals(10, summary.path("coverage").path("teacoco").path("line_missed").asInt());
    assertEquals(1, summary.path("sbom").path("components").asInt());
    assertEquals(1, summary.path("cve").path("total").asInt());
    assertEquals(1, summary.path("cve").path("counts").path("CRITICAL").asInt());

    String repoMd = collector.renderRepoMarkdown(summary);
    assertTrue(repoMd.contains("CVE-2026-0001"));
    assertTrue(repoMd.contains("80.0%"));

    // Test SecurityReportMojo & SecurityDashboardMojo
    Path jsonOut = tempDir.resolve("target/security/security-summary.json");
    Path mdOut = tempDir.resolve("target/security/security-summary.md");
    SecurityReportMojo reportMojo = new SecurityReportMojo();
    SarifMergerTest.setField(reportMojo, "rootDir", tempDir.toFile());
    SarifMergerTest.setField(reportMojo, "repo", "sarto-scene");
    SarifMergerTest.setField(reportMojo, "revision", "abc1234567890");
    SarifMergerTest.setField(reportMojo, "trivyFiles", List.of(trivyJson.toString()));
    SarifMergerTest.setField(reportMojo, "jsonOut", jsonOut.toString());
    SarifMergerTest.setField(reportMojo, "mdOut", mdOut.toString());
    SarifMergerTest.setField(reportMojo, "appendStepSummary", false);
    reportMojo.execute();
    assertTrue(Files.isRegularFile(jsonOut));
    assertTrue(Files.isRegularFile(mdOut));

    Path dashMd = tempDir.resolve("target/security/dashboard.md");
    SecurityDashboardMojo dashMojo = new SecurityDashboardMojo();
    SarifMergerTest.setField(dashMojo, "inputs", List.of(jsonOut.toString()));
    SarifMergerTest.setField(dashMojo, "mdOut", dashMd.toFile());
    SarifMergerTest.setField(dashMojo, "appendStepSummary", false);
    dashMojo.execute();

    assertTrue(
        Files.readString(dashMd)
            .contains("## Nightly Verification, Coverage & Security Dashboard"));
  }

  @Test
  void collectsQualityReviewReportAndExecutesMojo(@TempDir Path tempDir) throws Exception {
    Files.writeString(
        tempDir.resolve("pom.xml"),
        "<project><modelVersion>4.0.0</modelVersion><artifactId>root</artifactId><packaging>pom</packaging><modules><module>module-one</module></modules></project>");
    Path mod = tempDir.resolve("module-one");
    Files.createDirectories(mod.resolve("src/main/java/io/instanto"));
    Files.writeString(
        mod.resolve("pom.xml"),
        "<project><modelVersion>4.0.0</modelVersion><artifactId>module-one</artifactId></project>");
    Files.writeString(
        mod.resolve("src/main/java/io/instanto/App.java"),
        """
        package io.instanto;
        public class App {
          public int add(int a, int b) {
            return a + b;
          }
        }
        """);
    Path jacoco = mod.resolve("target/site/jacoco");
    Files.createDirectories(jacoco);
    Files.writeString(
        jacoco.resolve("jacoco.xml"),
        """
        <report name="module-one">
          <package name="io/instanto">
            <class name="io/instanto/App" sourcefilename="App.java">
              <method name="add" desc="(II)I" line="3">
                <counter type="INSTRUCTION" missed="2" covered="4"/>
                <counter type="LINE" missed="1" covered="1"/>
                <counter type="COMPLEXITY" missed="1" covered="1"/>
              </method>
              <counter type="INSTRUCTION" missed="2" covered="4"/>
              <counter type="BRANCH" missed="0" covered="0"/>
              <counter type="LINE" missed="1" covered="1"/>
              <counter type="COMPLEXITY" missed="1" covered="1"/>
              <counter type="METHOD" missed="0" covered="1"/>
            </class>
            <sourcefile name="App.java">
              <line nr="3" mi="0" ci="2" mb="0" cb="0"/>
              <line nr="4" mi="2" ci="0" mb="0" cb="0"/>
            </sourcefile>
          </package>
          <counter type="INSTRUCTION" missed="2" covered="4"/>
          <counter type="BRANCH" missed="0" covered="0"/>
          <counter type="LINE" missed="1" covered="1"/>
          <counter type="COMPLEXITY" missed="1" covered="1"/>
          <counter type="METHOD" missed="0" covered="1"/>
        </report>
        """);

    Path outDir = tempDir.resolve("target/quality-review");
    QualityReviewMojo mojo = new QualityReviewMojo();
    SarifMergerTest.setField(mojo, "rootDir", tempDir.toFile());
    SarifMergerTest.setField(mojo, "output", outDir.toFile());
    SarifMergerTest.setField(mojo, "buildStatus", "passed");
    SarifMergerTest.setField(mojo, "sonarHostUrl", "http://127.0.0.1:1");
    SarifMergerTest.setField(mojo, "sonarProjectKey", "test-proj");
    mojo.execute();

    assertTrue(Files.isRegularFile(outDir.resolve("summary.md")));
    assertTrue(Files.isRegularFile(outDir.resolve("manifest.json")));
    assertTrue(Files.readString(outDir.resolve("manifest.json")).contains("module-one"));
  }
}

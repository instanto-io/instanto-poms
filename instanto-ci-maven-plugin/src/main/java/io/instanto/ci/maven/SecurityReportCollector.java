/*
 * Copyright 2026 Carl Stainton
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.instanto.ci.maven;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Aggregates Surefire/Failsafe test results, JaCoCo and TeaCoCo coverage, SpotBugs findings,
 * CycloneDX SBOMs, and Trivy CVE scans into JSON and Markdown reports.
 */
public final class SecurityReportCollector {

  private static final Map<String, Integer> SEVERITY_ORDER =
      Map.of("CRITICAL", 0, "HIGH", 1, "MEDIUM", 2, "LOW", 3, "UNKNOWN", 4);

  private static final List<String> SEVERITY_KEYS =
      List.of("CRITICAL", "HIGH", "MEDIUM", "LOW", "UNKNOWN");

  private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

  public ObjectNode collectRepoReport(
      Path rootDir, String repoName, String revision, List<Path> trivyFiles) throws IOException {
    Path root = rootDir.toAbsolutePath().normalize();
    ObjectNode report = mapper.createObjectNode();
    report.put("repository", repoName);
    report.put("revision", revision == null ? "" : revision);
    report.set("tests", collectTests(root));
    report.set("coverage", collectCoverage(root));
    report.set("spotbugs", collectSpotBugs(root));
    report.set("sbom", collectSbom(root));
    report.set("cve", collectTrivy(trivyFiles == null ? List.of() : trivyFiles));
    return report;
  }

  public void writeJsonReport(ObjectNode report, Path output) throws IOException {
    if (output.getParent() != null) {
      Files.createDirectories(output.getParent());
    }
    Files.writeString(output, mapper.writeValueAsString(report) + "\n");
  }

  public ObjectNode readJsonReport(Path input) throws IOException {
    return (ObjectNode) mapper.readTree(Files.readString(input));
  }

  public String renderRepoMarkdown(JsonNode report) {
    JsonNode t = report.path("tests");
    JsonNode covJ = report.path("coverage").path("jacoco");
    JsonNode covT = report.path("coverage").path("teacoco");
    JsonNode sb = report.path("spotbugs");
    JsonNode sbom = report.path("sbom");
    JsonNode cve = report.path("cve");
    JsonNode c = cve.path("counts");

    String repo = SarifMerger.textOrDefault(report, "repository", "unknown");
    String rev = SarifMerger.textOrNull(report, "revision");
    if (rev == null || rev.isBlank()) {
      rev = "local";
    }

    List<String> lines = new ArrayList<>();
    lines.add("### Security & Coverage: `" + repo + "`");
    lines.add("");
    lines.add("Revision: `" + rev + "`");
    lines.add("");
    lines.add("| Check | Summary |");
    lines.add("| --- | --- |");
    lines.add(
        String.format(
            Locale.ROOT,
            "| Tests | %d run, %d failed, %d errors, %d skipped (%d suites) |",
            t.path("tests").asInt(0),
            t.path("failures").asInt(0),
            t.path("errors").asInt(0),
            t.path("skipped").asInt(0),
            t.path("suites").asInt(0)));
    lines.add(
        String.format(
            Locale.ROOT,
            "| JaCoCo coverage | line %s, branch %s (%d modules) |",
            fmtPct(covJ.get("line_pct")),
            fmtPct(covJ.get("branch_pct")),
            covJ.path("reports").asInt(0)));
    if (covT.path("reports").asInt(0) > 0) {
      lines.add(
          String.format(
              Locale.ROOT,
              "| TeaCoCo browser coverage | line %s, branch %s (%d suites) |",
              fmtPct(covT.get("line_pct")),
              fmtPct(covT.get("branch_pct")),
              covT.path("reports").asInt(0)));
    }
    lines.add(
        String.format(
            Locale.ROOT,
            "| SpotBugs | %d high, %d medium, %d low (%d SARIF/XML) |",
            sb.path("high").asInt(0),
            sb.path("medium").asInt(0),
            sb.path("low").asInt(0),
            sb.path("files").asInt(0)));
    lines.add(
        String.format(
            Locale.ROOT,
            "| CycloneDX SBOM | %d unique components (%d BOMs) |",
            sbom.path("components").asInt(0),
            sbom.path("bom_files").asInt(0)));
    lines.add(
        String.format(
            Locale.ROOT,
            "| Dependency CVEs (Trivy) | %d critical, %d high, %d medium, %d low (total %d) |",
            c.path("CRITICAL").asInt(0),
            c.path("HIGH").asInt(0),
            c.path("MEDIUM").asInt(0),
            c.path("LOW").asInt(0),
            cve.path("total").asInt(0)));

    JsonNode topFindings = cve.path("top_findings");
    if (topFindings.isArray() && !topFindings.isEmpty()) {
      lines.add("");
      lines.add("#### Top Dependency Vulnerabilities");
      lines.add("");
      lines.add("| Severity | CVE | Package | Installed | Fixed |");
      lines.add("| --- | --- | --- | --- | --- |");
      int count = Math.min(10, topFindings.size());
      for (int i = 0; i < count; i++) {
        JsonNode item = topFindings.get(i);
        String fixed = SarifMerger.textOrNull(item, "fixed_version");
        if (fixed == null || fixed.isEmpty()) {
          fixed = "none";
        }
        lines.add(
            String.format(
                Locale.ROOT,
                "| %s | `%s` | `%s` | `%s` | `%s` |",
                SarifMerger.textOrDefault(item, "severity", "UNKNOWN"),
                SarifMerger.textOrDefault(item, "id", ""),
                SarifMerger.textOrDefault(item, "package", ""),
                SarifMerger.textOrDefault(item, "installed_version", ""),
                fixed));
      }
    }
    lines.add("");
    return String.join("\n", lines);
  }

  public String renderDashboard(List<JsonNode> reports, String compatSummary) {
    List<String> lines = new ArrayList<>();
    lines.add("## Nightly Verification, Coverage & Security Dashboard");
    lines.add("");
    if (compatSummary != null && !compatSummary.isBlank()) {
      lines.add(compatSummary.trim());
      lines.add("");
    }
    if (reports == null || reports.isEmpty()) {
      lines.add("No verification/security reports were generated.");
      return String.join("\n", lines) + "\n";
    }

    lines.add("### Repository Metrics");
    lines.add("");
    lines.add(
        "| Project | Tests (Fail/Err) | JaCoCo Line | TeaCoCo Line | SpotBugs (H/M/L) | SBOM Deps | CVEs (C/H/M/L) |");
    lines.add("| --- | --- | --- | --- | --- | --- | --- |");

    List<JsonNode> sorted = new ArrayList<>(reports);
    sorted.sort(Comparator.comparing(r -> SarifMerger.textOrDefault(r, "repository", "")));

    for (JsonNode rep : sorted) {
      JsonNode t = rep.path("tests");
      JsonNode cj = rep.path("coverage").path("jacoco");
      JsonNode ct = rep.path("coverage").path("teacoco");
      JsonNode sb = rep.path("spotbugs");
      JsonNode c = rep.path("cve").path("counts");
      JsonNode sbom = rep.path("sbom");

      lines.add(
          String.format(
              Locale.ROOT,
              "| `%s` | %d (%d/%d) | %s | %s | %d/%d/%d | %d | %d/%d/%d/%d |",
              SarifMerger.textOrDefault(rep, "repository", ""),
              t.path("tests").asInt(0),
              t.path("failures").asInt(0),
              t.path("errors").asInt(0),
              fmtPct(cj.get("line_pct")),
              fmtPct(ct.get("line_pct")),
              sb.path("high").asInt(0),
              sb.path("medium").asInt(0),
              sb.path("low").asInt(0),
              sbom.path("components").asInt(0),
              c.path("CRITICAL").asInt(0),
              c.path("HIGH").asInt(0),
              c.path("MEDIUM").asInt(0),
              c.path("LOW").asInt(0)));
    }
    lines.add("");
    return String.join("\n", lines);
  }

  private ObjectNode collectSpotBugs(Path root) throws IOException {
    SarifMerger.SpotBugsSummary sb = new SarifMerger().collectSummary(root);
    ObjectNode node = mapper.createObjectNode();
    node.put("high", sb.high());
    node.put("medium", sb.medium());
    node.put("low", sb.low());
    node.put("files", sb.files());
    return node;
  }

  private ObjectNode collectSbom(Path root) throws IOException {
    Path top = root.resolve("target/bom.json");
    List<Path> candidates;
    if (Files.isRegularFile(top)) {
      candidates = List.of(top);
    } else {
      candidates =
          SarifMerger.uniqueSortedPaths(
              SarifMerger.findMatchingFiles(
                  root,
                  p ->
                      "bom.json".equals(p.getFileName().toString())
                          && p.getParent() != null
                          && "target".equals(p.getParent().getFileName().toString())
                          && SarifMerger.isPrimaryTargetArtifact(root, p)));
    }
    int found = 0;
    Set<String> seenPurls = new LinkedHashSet<>();
    for (Path path : candidates) {
      if (!Files.isRegularFile(path)) {
        continue;
      }
      try {
        JsonNode data = mapper.readTree(Files.readString(path));
        found++;
        JsonNode comps = data.get("components");
        if (comps != null && comps.isArray()) {
          for (JsonNode comp : comps) {
            String purl = SarifMerger.textOrNull(comp, "purl");
            String key =
                purl != null && !purl.isEmpty()
                    ? purl
                    : SarifMerger.textOrDefault(comp, "group", "")
                        + ":"
                        + SarifMerger.textOrDefault(comp, "name", "")
                        + ":"
                        + SarifMerger.textOrDefault(comp, "version", "");
            seenPurls.add(key);
          }
        }
      } catch (IOException e) {
        // Skip invalid BOM JSON
      }
    }
    ObjectNode node = mapper.createObjectNode();
    node.put("bom_files", found);
    node.put("components", seenPurls.size());
    return node;
  }

  private record CveKey(String id, String pkg, String installed) {}

  private record CveFinding(
      String id,
      String pkg,
      String installedVersion,
      String fixedVersion,
      String severity,
      String title,
      String target) {}

  private ObjectNode collectTrivy(List<Path> trivyPaths) {
    Map<String, Integer> counts = new LinkedHashMap<>();
    for (String sev : SEVERITY_KEYS) {
      counts.put(sev, 0);
    }
    Map<CveKey, CveFinding> findings = new LinkedHashMap<>();
    int scannedFiles = 0;

    for (Path path : trivyPaths) {
      if (path == null || !Files.isRegularFile(path)) {
        continue;
      }
      scannedFiles++;
      try {
        JsonNode data = mapper.readTree(Files.readString(path));
        JsonNode results = data.get("Results");
        if (results != null && results.isArray()) {
          for (JsonNode res : results) {
            String target = SarifMerger.textOrDefault(res, "Target", "");
            JsonNode vulns = res.get("Vulnerabilities");
            if (vulns != null && vulns.isArray()) {
              for (JsonNode vuln : vulns) {
                String vulnId = SarifMerger.textOrDefault(vuln, "VulnerabilityID", "");
                String pkg = SarifMerger.textOrDefault(vuln, "PkgName", "");
                String installed = SarifMerger.textOrDefault(vuln, "InstalledVersion", "");
                String sev =
                    SarifMerger.textOrDefault(vuln, "Severity", "UNKNOWN").toUpperCase(Locale.ROOT);
                if (!SEVERITY_ORDER.containsKey(sev)) {
                  sev = "UNKNOWN";
                }
                CveKey key = new CveKey(vulnId, pkg, installed);
                if (!findings.containsKey(key)) {
                  findings.put(
                      key,
                      new CveFinding(
                          vulnId,
                          pkg,
                          installed,
                          SarifMerger.textOrDefault(vuln, "FixedVersion", ""),
                          sev,
                          SarifMerger.textOrDefault(vuln, "Title", ""),
                          target));
                }
              }
            }
          }
        }
      } catch (IOException e) {
        // Ignore unreadable Trivy JSON
      }
    }

    for (CveFinding finding : findings.values()) {
      counts.put(finding.severity(), counts.getOrDefault(finding.severity(), 0) + 1);
    }

    List<CveFinding> sortedFindings = new ArrayList<>(findings.values());
    sortedFindings.sort(
        Comparator.<CveFinding>comparingInt(f -> SEVERITY_ORDER.getOrDefault(f.severity(), 99))
            .thenComparing(CveFinding::id)
            .thenComparing(CveFinding::pkg));

    ObjectNode countsNode = mapper.createObjectNode();
    counts.forEach(countsNode::put);

    ArrayNode topArray = mapper.createArrayNode();
    for (int i = 0; i < Math.min(25, sortedFindings.size()); i++) {
      CveFinding f = sortedFindings.get(i);
      ObjectNode fNode = mapper.createObjectNode();
      fNode.put("id", f.id());
      fNode.put("package", f.pkg());
      fNode.put("installed_version", f.installedVersion());
      fNode.put("fixed_version", f.fixedVersion());
      fNode.put("severity", f.severity());
      fNode.put("title", f.title());
      fNode.put("target", f.target());
      topArray.add(fNode);
    }

    ObjectNode cveNode = mapper.createObjectNode();
    cveNode.put("scan_files", scannedFiles);
    cveNode.set("counts", countsNode);
    cveNode.put("total", sortedFindings.size());
    cveNode.set("top_findings", topArray);
    return cveNode;
  }

  private ObjectNode collectCoverageGroup(List<Path> paths) {
    long lineCov = 0;
    long lineMiss = 0;
    long branchCov = 0;
    long branchMiss = 0;
    int reports = 0;

    List<Path> sorted = new ArrayList<>(paths);
    sorted.sort(Comparator.naturalOrder());

    for (Path path : sorted) {
      try {
        Document doc = SarifMerger.parseXmlSafe(path);
        Element root = doc.getDocumentElement();
        String tag = root.getTagName();
        if ("report".equals(tag)) {
          reports++;
          NodeList children = root.getChildNodes();
          for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element el && "counter".equals(el.getTagName())) {
              String ctype = el.getAttribute("type");
              long cov = parseLongOrZero(el.getAttribute("covered"));
              long miss = parseLongOrZero(el.getAttribute("missed"));
              if ("LINE".equals(ctype)) {
                lineCov += cov;
                lineMiss += miss;
              } else if ("BRANCH".equals(ctype)) {
                branchCov += cov;
                branchMiss += miss;
              }
            }
          }
        } else if ("coverage".equals(tag)) {
          reports++;
          NodeList files = root.getElementsByTagName("file");
          for (int i = 0; i < files.getLength(); i++) {
            Element fileEl = (Element) files.item(i);
            NodeList lines = fileEl.getElementsByTagName("lineToCover");
            for (int j = 0; j < lines.getLength(); j++) {
              Element lineEl = (Element) lines.item(j);
              if ("true".equalsIgnoreCase(lineEl.getAttribute("covered"))) {
                lineCov++;
              } else {
                lineMiss++;
              }
              long branches = parseLongOrZero(lineEl.getAttribute("branchesToCover"));
              long coveredBranches = parseLongOrZero(lineEl.getAttribute("coveredBranches"));
              if (branches > 0) {
                branchCov += coveredBranches;
                branchMiss += Math.max(0L, branches - coveredBranches);
              }
            }
          }
        }
      } catch (Exception e) {
        // Skip unreadable coverage XML
      }
    }

    long lineTotal = lineCov + lineMiss;
    long branchTotal = branchCov + branchMiss;

    ObjectNode node = mapper.createObjectNode();
    node.put("reports", reports);
    node.put("line_covered", lineCov);
    node.put("line_missed", lineMiss);
    if (lineTotal > 0) {
      node.put("line_pct", roundOneDecimal(100.0 * lineCov / lineTotal));
    } else {
      node.putNull("line_pct");
    }
    node.put("branch_covered", branchCov);
    node.put("branch_missed", branchMiss);
    if (branchTotal > 0) {
      node.put("branch_pct", roundOneDecimal(100.0 * branchCov / branchTotal));
    } else {
      node.putNull("branch_pct");
    }
    return node;
  }

  private static Set<String> teavmCoveredSources(List<Path> paths) {
    Set<String> sources = new LinkedHashSet<>();
    for (Path path : paths) {
      try {
        Document doc = SarifMerger.parseXmlSafe(path);
        Element root = doc.getDocumentElement();
        if (!"report".equals(root.getTagName())) {
          continue;
        }
        NodeList pkgs = root.getElementsByTagName("package");
        for (int i = 0; i < pkgs.getLength(); i++) {
          Element pkg = (Element) pkgs.item(i);
          String pkgName = stripSlashes(pkg.getAttribute("name"));
          NodeList sfs = pkg.getElementsByTagName("sourcefile");
          for (int j = 0; j < sfs.getLength(); j++) {
            Element sf = (Element) sfs.item(j);
            String sfName = stripSlashes(sf.getAttribute("name"));
            if (!sfName.isEmpty()) {
              sources.add(pkgName.isEmpty() ? sfName : pkgName + "/" + sfName);
            }
          }
        }
      } catch (Exception e) {
        // Ignore
      }
    }
    return sources;
  }

  private static boolean browserOverlapsTeavm(Path path, Set<String> teavmSources) {
    try {
      Document doc = SarifMerger.parseXmlSafe(path);
      Element root = doc.getDocumentElement();
      if (!"coverage".equals(root.getTagName())) {
        return true;
      }
      NodeList files = root.getElementsByTagName("file");
      if (files.getLength() == 0) {
        return true;
      }
      if (teavmSources.isEmpty()) {
        return false;
      }
      for (int i = 0; i < files.getLength(); i++) {
        Element fileNode = (Element) files.item(i);
        String fpath = fileNode.getAttribute("path").replace('\\', '/');
        while (fpath.startsWith("./") || fpath.startsWith("/")) {
          fpath = fpath.substring(fpath.startsWith("./") ? 2 : 1);
        }
        for (String suffix : teavmSources) {
          if (fpath.equals(suffix) || fpath.endsWith("/" + suffix)) {
            return true;
          }
        }
      }
      return false;
    } catch (Exception e) {
      return true;
    }
  }

  private ObjectNode collectCoverage(Path root) throws IOException {
    List<Path> jacocoPaths =
        SarifMerger.uniqueSortedPaths(
            SarifMerger.findMatchingFiles(
                root,
                p -> {
                  String name = p.getFileName().toString();
                  if (!"jacoco.xml".equals(name) && !"jacocoTestReport.xml".equals(name)) {
                    return false;
                  }
                  String posix = SarifMerger.toPosix(p);
                  return (posix.contains("/target/site/jacoco/")
                          || posix.contains("/build/reports/jacoco/"))
                      && !posix.contains("/jacoco-aggregate")
                      && SarifMerger.isPrimaryTargetArtifact(root, p);
                }));

    List<Path> teavmPaths =
        SarifMerger.uniqueSortedPaths(
            SarifMerger.findMatchingFiles(
                root,
                p ->
                    "teavm-coverage.xml".equals(p.getFileName().toString())
                        && SarifMerger.toPosix(p).contains("/target/")
                        && SarifMerger.isPrimaryTargetArtifact(root, p)));

    Set<String> teavmSources = teavmCoveredSources(teavmPaths);
    List<Path> browserCandidates =
        SarifMerger.uniqueSortedPaths(
            SarifMerger.findMatchingFiles(
                root,
                p ->
                    "browser-coverage.xml".equals(p.getFileName().toString())
                        && SarifMerger.toPosix(p).contains("/target/")
                        && SarifMerger.isPrimaryTargetArtifact(root, p)));
    List<Path> teacocoCombined = new ArrayList<>(teavmPaths);
    for (Path bp : browserCandidates) {
      if (!browserOverlapsTeavm(bp, teavmSources)) {
        teacocoCombined.add(bp);
      }
    }

    ObjectNode cov = mapper.createObjectNode();
    cov.set("jacoco", collectCoverageGroup(jacocoPaths));
    cov.set("teacoco", collectCoverageGroup(teacocoCombined));
    return cov;
  }

  private ObjectNode collectTests(Path root) throws IOException {
    List<Path> candidates =
        SarifMerger.uniqueSortedPaths(
            SarifMerger.findMatchingFiles(
                root,
                p -> {
                  String name = p.getFileName().toString();
                  if (!name.startsWith("TEST-") || !name.endsWith(".xml")) {
                    return false;
                  }
                  if (!SarifMerger.isPrimaryTargetArtifact(root, p)) {
                    return false;
                  }
                  String posix = SarifMerger.toPosix(p);
                  String parentName =
                      p.getParent() != null ? p.getParent().getFileName().toString() : "";
                  return parentName.endsWith("surefire-reports")
                      || parentName.endsWith("failsafe-reports")
                      || posix.contains("/build/test-results/");
                }));

    int suites = 0;
    int tests = 0;
    int failures = 0;
    int errors = 0;
    int skipped = 0;

    for (Path path : candidates) {
      try {
        Document doc = SarifMerger.parseXmlSafe(path);
        Element rootEl = doc.getDocumentElement();
        String tag = rootEl.getTagName();
        List<Element> suiteNodes = new ArrayList<>();
        if ("testsuite".equals(tag)) {
          suiteNodes.add(rootEl);
        } else if ("testsuites".equals(tag)) {
          NodeList children = rootEl.getChildNodes();
          for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element el && "testsuite".equals(el.getTagName())) {
              suiteNodes.add(el);
            }
          }
        }
        for (Element suite : suiteNodes) {
          suites++;
          tests += (int) parseLongOrZero(suite.getAttribute("tests"));
          failures += (int) parseLongOrZero(suite.getAttribute("failures"));
          errors += (int) parseLongOrZero(suite.getAttribute("errors"));
          skipped += (int) parseLongOrZero(suite.getAttribute("skipped"));
        }
      } catch (Exception e) {
        // Ignore malformed test report
      }
    }

    ObjectNode node = mapper.createObjectNode();
    node.put("suites", suites);
    node.put("tests", tests);
    node.put("failures", failures);
    node.put("errors", errors);
    node.put("skipped", skipped);
    return node;
  }

  private static String fmtPct(JsonNode node) {
    if (node == null || node.isNull()) {
      return "n/a";
    }
    return String.format(Locale.ROOT, "%.1f%%", node.asDouble());
  }

  private static double roundOneDecimal(double val) {
    return BigDecimal.valueOf(val).setScale(1, RoundingMode.HALF_EVEN).doubleValue();
  }

  private static long parseLongOrZero(String s) {
    if (s == null || s.isBlank()) {
      return 0L;
    }
    try {
      return Long.parseLong(s.trim());
    } catch (NumberFormatException e) {
      return 0L;
    }
  }

  private static String stripSlashes(String s) {
    if (s == null) {
      return "";
    }
    int start = 0;
    int end = s.length();
    while (start < end && s.charAt(start) == '/') {
      start++;
    }
    while (end > start && s.charAt(end - 1) == '/') {
      end--;
    }
    return s.substring(start, end);
  }
}

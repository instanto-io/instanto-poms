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
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Collects fresh reactor SpotBugs, Surefire, JaCoCo, and SonarQube reports with source-origin
 * classification and SHA-256 source hashes.
 */
public final class QualityReviewCollector {

  private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

  public record ReactorModule(Path dir, String artifactId, String packaging) {}

  private record ResolvedSource(Path file, String origin) {}

  private record SarifInput(Path path, Path moduleDir) {}

  public List<ReactorModule> walkReactor(Path rootDir) throws Exception {
    List<ReactorModule> result = new ArrayList<>();
    walkReactorRecursive(rootDir.toAbsolutePath().normalize(), result);
    return result;
  }

  private void walkReactorRecursive(Path dir, List<ReactorModule> out) throws Exception {
    Path pomFile = dir.resolve("pom.xml");
    if (!Files.isRegularFile(pomFile)) {
      return;
    }
    Document doc = SarifMerger.parseXmlSafe(pomFile);
    Element root = doc.getDocumentElement();
    String artifactId = directChildText(root, "artifactId", "");
    String packaging = directChildText(root, "packaging", "jar");
    out.add(new ReactorModule(dir, artifactId, packaging));

    Element modulesEl = directChildElement(root, "modules");
    if (modulesEl != null) {
      NodeList children = modulesEl.getChildNodes();
      for (int i = 0; i < children.getLength(); i++) {
        Node child = children.item(i);
        if (child instanceof Element el && "module".equals(localOrTagName(el))) {
          String modText = el.getTextContent().trim();
          if (!modText.isEmpty()) {
            walkReactorRecursive(dir.resolve(modText).normalize(), out);
          }
        }
      }
    }
  }

  public ObjectNode collect(
      Path rootDir,
      Path outputDir,
      long sinceMillis,
      String buildStatus,
      Path buildLog,
      String sonarHost,
      String sonarProjectKey)
      throws Exception {
    Path root = rootDir.toAbsolutePath().normalize();
    Path output = outputDir.toAbsolutePath().normalize();
    if (Files.exists(output)) {
      throw new IllegalArgumentException("Output directory already exists: " + output);
    }
    Files.createDirectories(output);

    List<ReactorModule> reactor = walkReactor(root);
    ArrayNode modulesNode = mapper.createArrayNode();
    ArrayNode findingsNode = mapper.createArrayNode();
    ArrayNode sourceHashesNode = mapper.createArrayNode();
    List<SarifInput> sarifInputs = new ArrayList<>();
    List<Path> tasks = new ArrayList<>();

    for (ReactorModule mod : reactor) {
      Path moduleDir = mod.dir();
      Path relative = root.relativize(moduleDir);
      String relPosix = relative.toString().isEmpty() ? "." : SarifMerger.toPosix(relative);

      List<Path> sources = new ArrayList<>();
      for (String tree : List.of("main", "jre", "teavm")) {
        Path srcTree = moduleDir.resolve("src").resolve(tree).resolve("java");
        if (Files.isDirectory(srcTree)) {
          try (Stream<Path> walk = Files.walk(srcTree)) {
            walk.filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().endsWith(".java"))
                .forEach(sources::add);
          }
        }
      }
      List<Path> hashedFiles = new ArrayList<>(sources);
      hashedFiles.add(moduleDir.resolve("pom.xml"));
      hashedFiles.sort(Comparator.naturalOrder());
      for (Path p : hashedFiles) {
        if (Files.isRegularFile(p)) {
          ObjectNode h = mapper.createObjectNode();
          h.put("path", SarifMerger.toPosix(root.relativize(p)));
          h.put("sha256", sha256Hex(p));
          sourceHashesNode.add(h);
        }
      }

      ObjectNode entry = mapper.createObjectNode();
      entry.put("module", relPosix);
      entry.put("artifact", mod.artifactId());
      entry.put("java_sources", sources.size());
      entry.put(
          "spotbugs",
          "pom".equals(mod.packaging()) || sources.isEmpty() ? "not-applicable" : "missing");

      Path spotbugsDir = moduleDir.resolve("target/spotbugs");
      if (Files.isDirectory(spotbugsDir)) {
        List<Path> sbFiles;
        try (Stream<Path> s = Files.list(spotbugsDir)) {
          sbFiles = s.sorted().toList();
        }
        for (Path path : sbFiles) {
          if (!isFresh(path, sinceMillis)) {
            continue;
          }
          Path dest =
              output
                  .resolve("modules")
                  .resolve(relative)
                  .resolve("spotbugs")
                  .resolve(path.getFileName());
          Files.createDirectories(dest.getParent());
          Files.copy(path, dest, StandardCopyOption.REPLACE_EXISTING);

          String fileName = path.getFileName().toString();
          if (fileName.endsWith(".xml")) {
            try {
              SpotBugsXmlResult xmlRes = readSpotBugsXml(path, moduleDir, root);
              entry.put("spotbugs", "analysed");
              entry.put("findings", xmlRes.findings.size());
              entry.put("errors", xmlRes.errors);
              entry.put("missing_classes", xmlRes.missingClasses);
              if (xmlRes.errors > 0 || xmlRes.missingClasses > 0) {
                entry.put("spotbugs", "analysed-with-warnings");
              }
              for (ObjectNode bug : xmlRes.findings) {
                ObjectNode copy = bug.deepCopy();
                copy.put("module", relPosix);
                findingsNode.add(copy);
              }
            } catch (Exception e) {
              entry.put("spotbugs", "invalid-report");
              entry.put(
                  "error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            }
          } else if (fileName.endsWith(".sarif")) {
            sarifInputs.add(new SarifInput(path, moduleDir));
          }
        }
      }

      Map<String, Integer> testCounts = new LinkedHashMap<>();
      Path surefireDir = moduleDir.resolve("target/surefire-reports");
      if (Files.isDirectory(surefireDir)) {
        List<Path> testFiles;
        try (Stream<Path> s = Files.list(surefireDir)) {
          testFiles =
              s.filter(
                      p -> {
                        String n = p.getFileName().toString();
                        return n.startsWith("TEST-") && n.endsWith(".xml");
                      })
                  .sorted()
                  .toList();
        }
        for (Path path : testFiles) {
          if (isFresh(path, sinceMillis)) {
            Document doc = SarifMerger.parseXmlSafe(path);
            Element rootEl = doc.getDocumentElement();
            for (String key : List.of("tests", "failures", "errors", "skipped")) {
              int val = parseIntOrZero(rootEl.getAttribute(key));
              if (val > 0 || testCounts.containsKey(key)) {
                testCounts.put(key, testCounts.getOrDefault(key, 0) + val);
              } else {
                testCounts.put(key, 0);
              }
            }
            Path dest =
                output
                    .resolve("modules")
                    .resolve(relative)
                    .resolve("tests")
                    .resolve(path.getFileName());
            Files.createDirectories(dest.getParent());
            Files.copy(path, dest, StandardCopyOption.REPLACE_EXISTING);
          }
        }
      }
      ObjectNode testsObj = mapper.createObjectNode();
      testCounts.forEach(testsObj::put);
      entry.set("tests", testsObj);

      Path coverageDir = moduleDir.resolve("target/site/jacoco");
      if (isFresh(coverageDir.resolve("jacoco.xml"), sinceMillis)) {
        copyDirectory(coverageDir, output.resolve("modules").resolve(relative).resolve("coverage"));
        entry.put("coverage", "collected");
      }

      modulesNode.add(entry);
      Path task = moduleDir.resolve("target/sonar/report-task.txt");
      if (isFresh(task, sinceMillis)) {
        tasks.add(task);
      }
    }

    ObjectNode mergedSarif = mergeSarifWithResolvedPaths(sarifInputs, root);
    if (mergedSarif != null) {
      writeJson(output.resolve("spotbugs.sarif"), mergedSarif);
    }
    writeJson(output.resolve("spotbugs-findings.json"), findingsNode);

    Path rootTask =
        tasks.stream()
            .filter(
                t ->
                    t.getParent() != null
                        && t.getParent().getParent() != null
                        && root.equals(t.getParent().getParent().getParent()))
            .findFirst()
            .orElse(null);
    if (rootTask != null) {
      Path sonarDir = output.resolve("sonar");
      Files.createDirectories(sonarDir);
      Files.copy(
          rootTask, sonarDir.resolve("report-task.txt"), StandardCopyOption.REPLACE_EXISTING);
    }

    ObjectNode sonarStatus =
        exportSonarSnapshot(rootTask, output.resolve("sonar"), sonarHost, sonarProjectKey);
    writeJson(output.resolve("sonar/status.json"), sonarStatus);

    String revision = gitOutputOrNull(root, "git", "rev-parse", "HEAD");
    String porcelain = gitOutputOrNull(root, "git", "status", "--porcelain");
    Boolean dirty = porcelain != null ? !porcelain.isEmpty() : null;

    Map<String, Integer> byOrigin = new LinkedHashMap<>();
    Map<String, Integer> byPriority = new LinkedHashMap<>();
    int p1 = 0;
    int p2 = 0;
    int p3 = 0;
    for (JsonNode bug : findingsNode) {
      String origin = SarifMerger.textOrDefault(bug, "origin", "unresolved");
      byOrigin.put(origin, byOrigin.getOrDefault(origin, 0) + 1);
      int prio = bug.path("priority").asInt(3);
      String pKey = String.valueOf(prio);
      byPriority.put(pKey, byPriority.getOrDefault(pKey, 0) + 1);
      if (prio == 1) {
        p1++;
      } else if (prio == 2) {
        p2++;
      } else {
        p3++;
      }
    }

    ObjectNode manifest = mapper.createObjectNode();
    manifest.put("collected_at", Instant.now().toString());
    if (revision != null) {
      manifest.put("revision", revision);
    } else {
      manifest.putNull("revision");
    }
    if (dirty != null) {
      manifest.put("dirty_worktree", dirty);
    } else {
      manifest.putNull("dirty_worktree");
    }
    manifest.put("since_epoch", sinceMillis / 1000.0);
    manifest.put("build_status", buildStatus);
    manifest.set("modules", modulesNode);
    manifest.set("sonar", sonarStatus);
    ObjectNode originNode = mapper.createObjectNode();
    byOrigin.forEach(originNode::put);
    manifest.set("spotbugs_by_origin", originNode);
    ObjectNode prioNode = mapper.createObjectNode();
    byPriority.forEach(prioNode::put);
    manifest.set("spotbugs_by_priority", prioNode);

    if (buildLog != null && Files.isRegularFile(buildLog)) {
      Files.copy(buildLog, output.resolve("build.log"), StandardCopyOption.REPLACE_EXISTING);
    }
    writeJson(output.resolve("manifest.json"), manifest);
    writeJson(output.resolve("source-hashes.json"), sourceHashesNode);

    int totalTests = 0;
    int totalFailures = 0;
    int totalErrors = 0;
    int totalSkipped = 0;
    List<String> diagnostics = new ArrayList<>();
    for (JsonNode m : modulesNode) {
      JsonNode t = m.path("tests");
      totalTests += t.path("tests").asInt(0);
      totalFailures += t.path("failures").asInt(0);
      totalErrors += t.path("errors").asInt(0);
      totalSkipped += t.path("skipped").asInt(0);
      int errs = m.path("errors").asInt(0);
      int missing = m.path("missing_classes").asInt(0);
      if (errs > 0 || missing > 0) {
        diagnostics.add(
            "(" + SarifMerger.textOrDefault(m, "module", "") + ", " + errs + ", " + missing + ")");
      }
    }

    List<String> lines = new ArrayList<>();
    lines.add("# SAM code-quality reports");
    lines.add("");
    lines.add(
        String.format(
            Locale.ROOT,
            "Build: **%s** · revision `%s` · dirty worktree: %s",
            buildStatus,
            revision,
            dirty));
    lines.add(
        String.format(
            Locale.ROOT,
            "Tests: %d, failures: %d, errors: %d, skipped: %d.",
            totalTests,
            totalFailures,
            totalErrors,
            totalSkipped));
    lines.add("");
    lines.add(
        String.format(
            Locale.ROOT,
            "SpotBugs: **%d findings** (%d high, %d medium, %d low).",
            findingsNode.size(),
            p1,
            p2,
            p3));
    lines.add("Origins: " + byOrigin + ". Generated findings are retained, not suppressed.");
    lines.add("");
    lines.add(
        String.format(
            Locale.ROOT,
            "SonarQube: **%s**. %s",
            SarifMerger.textOrDefault(sonarStatus, "status", "unknown"),
            SarifMerger.textOrDefault(sonarStatus, "reason", "")));
    lines.add("");
    lines.add(
        "SpotBugs analysis diagnostics (module, errors, missing classes): "
            + (diagnostics.isEmpty() ? "none" : diagnostics)
            + ".");
    lines.add(
        "Only reports newer than the supplied run marker are included. Missing or invalid reports do not mean zero findings.");
    lines.add("");
    lines.add("| Module | SpotBugs | Findings | Tests |");
    lines.add("| --- | --- | ---: | ---: |");
    for (JsonNode m : modulesNode) {
      String fStr = m.has("findings") ? String.valueOf(m.get("findings").asInt()) : "—";
      JsonNode t = m.path("tests");
      String tStr = t.has("tests") ? String.valueOf(t.get("tests").asInt()) : "—";
      lines.add(
          String.format(
              Locale.ROOT,
              "| %s | %s | %s | %s |",
              SarifMerger.textOrDefault(m, "module", ""),
              SarifMerger.textOrDefault(m, "spotbugs", ""),
              fStr,
              tStr));
    }
    lines.add("");
    lines.add("Full findings: [JSON](spotbugs-findings.json) · [SARIF](spotbugs.sarif).");
    lines.add(
        "Raw per-module HTML/XML/SARIF, test and coverage reports are under `modules/`. Maintained-source SHA-256 hashes are in `source-hashes.json`.");
    lines.add("");
    lines.add("This is an analysis baseline, not a certification of Sarto design compliance.");
    Files.writeString(output.resolve("summary.md"), String.join("\n", lines) + "\n");
    return manifest;
  }

  private record SpotBugsXmlResult(List<ObjectNode> findings, int errors, int missingClasses) {}

  private SpotBugsXmlResult readSpotBugsXml(Path xmlPath, Path moduleDir, Path rootDir)
      throws Exception {
    Document doc = SarifMerger.parseXmlSafe(xmlPath);
    Element root = doc.getDocumentElement();
    List<ObjectNode> findings = new ArrayList<>();
    NodeList bugs = root.getElementsByTagName("BugInstance");
    for (int i = 0; i < bugs.getLength(); i++) {
      Element bug = (Element) bugs.item(i);
      NodeList sourceLines = bug.getElementsByTagName("SourceLine");
      Element primarySource = null;
      for (int j = 0; j < sourceLines.getLength(); j++) {
        Element sl = (Element) sourceLines.item(j);
        if (primarySource == null || "true".equals(sl.getAttribute("primary"))) {
          primarySource = sl;
          if ("true".equals(sl.getAttribute("primary"))) {
            break;
          }
        }
      }
      String sourcepath = primarySource != null ? primarySource.getAttribute("sourcepath") : "";
      ResolvedSource resolved = resolveSourceFile(moduleDir, sourcepath);
      Element clsEl = directChildElement(bug, "Class");
      String className = clsEl != null ? clsEl.getAttribute("classname") : null;
      String longMsg = directChildText(bug, "LongMessage", null);
      String shortMsg = directChildText(bug, "ShortMessage", null);
      String message =
          longMsg != null ? longMsg : (shortMsg != null ? shortMsg : bug.getAttribute("type"));

      ObjectNode f = mapper.createObjectNode();
      f.put("rule", bug.getAttribute("type"));
      f.put("priority", parseIntOrDefault(bug.getAttribute("priority"), 3));
      f.put("category", bug.getAttribute("category"));
      f.put("origin", resolved.origin());
      if (className != null && !className.isEmpty()) {
        f.put("class", className);
      } else {
        f.putNull("class");
      }
      f.put(
          "path",
          resolved.file() != null
              ? SarifMerger.toPosix(rootDir.relativize(resolved.file()))
              : sourcepath);
      f.put(
          "line", primarySource != null ? parseIntOrZero(primarySource.getAttribute("start")) : 0);
      f.put("message", message);
      findings.add(f);
    }

    Element errorsEl = directChildElement(root, "Errors");
    int errs = errorsEl != null ? parseIntOrZero(errorsEl.getAttribute("errors")) : 0;
    int missing = errorsEl != null ? parseIntOrZero(errorsEl.getAttribute("missingClasses")) : 0;
    return new SpotBugsXmlResult(findings, errs, missing);
  }

  private ObjectNode mergeSarifWithResolvedPaths(List<SarifInput> inputs, Path rootDir)
      throws IOException {
    ObjectNode driver = null;
    JsonNode taxonomies = null;
    List<JsonNode> rules = new ArrayList<>();
    Map<String, Integer> indices = new LinkedHashMap<>();
    List<ObjectNode> results = new ArrayList<>();

    for (SarifInput input : inputs) {
      JsonNode doc = mapper.readTree(Files.readString(input.path()));
      JsonNode runs = doc.get("runs");
      if (runs == null || !runs.isArray()) {
        continue;
      }
      for (JsonNode run : runs) {
        JsonNode candidate = run.path("tool").path("driver");
        if (driver == null && candidate.isObject()) {
          driver = ((ObjectNode) candidate).deepCopy();
          taxonomies = run.get("taxonomies");
        }
        JsonNode candidateRules = candidate.get("rules");
        if (candidateRules != null && candidateRules.isArray()) {
          for (JsonNode rule : candidateRules) {
            String id = SarifMerger.textOrNull(rule, "id");
            if (id != null && !indices.containsKey(id)) {
              indices.put(id, rules.size());
              rules.add(rule);
            }
          }
        }
        JsonNode runResults = run.get("results");
        if (runResults != null && runResults.isArray()) {
          for (JsonNode r : runResults) {
            if (!r.isObject()) {
              continue;
            }
            ObjectNode copy = ((ObjectNode) r).deepCopy();
            String ruleId = SarifMerger.textOrNull(copy, "ruleId");
            if (ruleId != null && indices.containsKey(ruleId)) {
              copy.put("ruleIndex", indices.get(ruleId));
            } else {
              copy.remove("ruleIndex");
            }
            rewriteArtifactLocations(copy.get("locations"), input.moduleDir(), rootDir);
            rewriteArtifactLocations(copy.get("relatedLocations"), input.moduleDir(), rootDir);
            results.add(copy);
          }
        }
      }
    }

    if (driver == null) {
      return null;
    }
    ArrayNode rulesArr = mapper.createArrayNode();
    rules.forEach(rulesArr::add);
    driver.set("rules", rulesArr);

    ArrayNode resultsArr = mapper.createArrayNode();
    results.forEach(resultsArr::add);

    ObjectNode runOut = mapper.createObjectNode();
    ObjectNode toolOut = mapper.createObjectNode();
    toolOut.set("driver", driver);
    runOut.set("tool", toolOut);
    runOut.set("taxonomies", taxonomies != null ? taxonomies : mapper.createArrayNode());
    runOut.set("results", resultsArr);

    ObjectNode sarifOut = mapper.createObjectNode();
    sarifOut.put("version", "2.1.0");
    sarifOut.put("$schema", "https://json.schemastore.org/sarif-2.1.0.json");
    ArrayNode runsOut = mapper.createArrayNode();
    runsOut.add(runOut);
    sarifOut.set("runs", runsOut);
    return sarifOut;
  }

  private static void rewriteArtifactLocations(JsonNode locations, Path moduleDir, Path rootDir) {
    if (locations == null || !locations.isArray()) {
      return;
    }
    for (JsonNode loc : locations) {
      JsonNode artifact = loc.path("physicalLocation").path("artifactLocation");
      if (artifact.isObject()) {
        ObjectNode artObj = (ObjectNode) artifact;
        String uri = SarifMerger.textOrNull(artObj, "uri");
        ResolvedSource resolved = resolveSourceFile(moduleDir, uri);
        if (resolved.file() != null) {
          artObj.put("uri", SarifMerger.toPosix(rootDir.relativize(resolved.file())));
          artObj.remove("uriBaseId");
          artObj.remove("index");
        }
      }
    }
  }

  private static ResolvedSource resolveSourceFile(Path moduleDir, String rawSourcePath) {
    String sourcepath =
        rawSourcePath != null ? URLDecoder.decode(rawSourcePath, StandardCharsets.UTF_8) : "";
    if (sourcepath.isEmpty()) {
      return new ResolvedSource(null, "unresolved");
    }
    for (String tree : List.of("main", "jre", "teavm", "test")) {
      Path candidate = moduleDir.resolve("src").resolve(tree).resolve("java").resolve(sourcepath);
      if (Files.isRegularFile(candidate)) {
        return new ResolvedSource(candidate, "authored");
      }
    }
    Path genRoot = moduleDir.resolve("target/generated-sources");
    if (Files.isDirectory(genRoot)) {
      try (Stream<Path> dirs = Files.list(genRoot)) {
        for (Path sub : dirs.sorted().toList()) {
          Path candidate = sub.resolve(sourcepath);
          if (Files.isRegularFile(candidate)) {
            return new ResolvedSource(candidate, "generated");
          }
        }
      } catch (IOException e) {
        // Ignore
      }
    }
    return new ResolvedSource(null, "unresolved");
  }

  private ObjectNode exportSonarSnapshot(
      Path taskFile, Path destination, String host, String project) throws IOException {
    String token = QualitySummaryMojo.resolveToken(null);
    ObjectNode status = mapper.createObjectNode();
    if (taskFile == null || !Files.isRegularFile(taskFile)) {
      status.put("status", "not-run");
      status.put("reason", "No fresh SonarScanner task report");
      status.put("token_available", !token.isEmpty());
      return status;
    }
    if (token.isEmpty()) {
      status.put("status", "unavailable");
      status.put("reason", "Result export needs SONAR_READ_TOKEN or SONAR_TOKEN");
      return status;
    }
    URI parsed = URI.create(host);
    if (!"https".equalsIgnoreCase(parsed.getScheme())
        || parsed.getHost() == null
        || parsed.getUserInfo() != null) {
      throw new IllegalArgumentException(
          "Use an HTTPS Sonar server URL without embedded credentials");
    }

    Map<String, String> taskProps = new LinkedHashMap<>();
    for (String line : Files.readAllLines(taskFile)) {
      int eq = line.indexOf('=');
      if (eq > 0) {
        taskProps.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
      }
    }
    String ceTaskId = taskProps.get("ceTaskId");
    if (ceTaskId == null || ceTaskId.isEmpty()) {
      status.put("status", "export-failed");
      status.put("reason", "Missing ceTaskId in report-task.txt");
      return status;
    }

    SonarQubeClient client = new SonarQubeClient(host, token);
    JsonNode computation = client.get("/api/ce/task", Map.of("id", ceTaskId));
    if (computation == null || !computation.has("task")) {
      status.put("status", "export-failed");
      status.put("reason", "Sonar API request failed; check server access and Browse permission");
      return status;
    }
    writeJson(destination.resolve("compute-task.json"), computation);
    JsonNode state = computation.path("task");
    String taskStatus = SarifMerger.textOrDefault(state, "status", "UNKNOWN");
    if (!"SUCCESS".equals(taskStatus)) {
      status.put("status", taskStatus);
      status.put("reason", "Sonar background analysis has not succeeded");
      return status;
    }
    String analysisId = SarifMerger.textOrDefault(state, "analysisId", "");
    JsonNode gate =
        client.get("/api/qualitygates/project_status", Map.of("analysisId", analysisId));
    if (gate == null) {
      status.put("status", "export-failed");
      status.put("reason", "Could not fetch quality gate for analysis " + analysisId);
      return status;
    }
    writeJson(destination.resolve("quality-gate.json"), gate);

    JsonNode measures =
        client.get(
            "/api/measures/component",
            Map.of(
                "component",
                project,
                "metricKeys",
                "bugs,vulnerabilities,code_smells,coverage,duplicated_lines_density,ncloc"));
    if (measures != null) {
      writeJson(destination.resolve("measures.json"), measures);
    }

    ArrayNode allIssues = mapper.createArrayNode();
    int total = 0;
    for (int page = 1; page <= 20; page++) {
      JsonNode resp =
          client.get(
              "/api/issues/search",
              Map.of(
                  "componentKeys",
                  project,
                  "resolved",
                  "false",
                  "ps",
                  "500",
                  "p",
                  String.valueOf(page)));
      if (resp == null) {
        break;
      }
      JsonNode pageIssues = resp.get("issues");
      if (pageIssues != null && pageIssues.isArray()) {
        pageIssues.forEach(allIssues::add);
      }
      total =
          resp.path("paging").has("total")
              ? resp.path("paging").path("total").asInt(0)
              : resp.path("total").asInt(0);
      if (allIssues.size() >= total) {
        break;
      }
    }
    ObjectNode issuesDoc = mapper.createObjectNode();
    issuesDoc.put("total", total);
    issuesDoc.set("issues", allIssues);
    issuesDoc.put("truncated", allIssues.size() < total);
    issuesDoc.put("scope", "Current project snapshot; quality gate is pinned to analysisId");
    writeJson(destination.resolve("issues.json"), issuesDoc);

    status.put("status", "collected");
    status.put("analysis_id", analysisId);
    status.put(
        "quality_gate", SarifMerger.textOrDefault(gate.path("projectStatus"), "status", "UNKNOWN"));
    status.put("issue_count", total);
    status.put("exported_issues", allIssues.size());
    status.put("truncated", allIssues.size() < total);
    return status;
  }

  private void writeJson(Path path, JsonNode value) throws IOException {
    if (path.getParent() != null) {
      Files.createDirectories(path.getParent());
    }
    Files.writeString(path, mapper.writeValueAsString(value) + "\n");
  }

  private static boolean isFresh(Path path, long sinceMillis) throws IOException {
    return Files.isRegularFile(path) && Files.getLastModifiedTime(path).toMillis() >= sinceMillis;
  }

  private static void copyDirectory(Path src, Path dest) throws IOException {
    try (Stream<Path> walk = Files.walk(src)) {
      for (Path p : walk.toList()) {
        Path target = dest.resolve(src.relativize(p));
        if (Files.isDirectory(p)) {
          Files.createDirectories(target);
        } else if (Files.isRegularFile(p)) {
          Files.createDirectories(target.getParent());
          Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING);
        }
      }
    }
  }

  static String sha256Hex(Path file) throws IOException {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] bytes = Files.readAllBytes(file);
      return HexFormat.of().formatHex(md.digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String gitOutputOrNull(Path cwd, String... command) {
    try {
      Process process =
          new ProcessBuilder(command)
              .directory(cwd.toFile())
              .redirectError(ProcessBuilder.Redirect.DISCARD)
              .start();
      String out =
          new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
      return process.waitFor() == 0 ? out : null;
    } catch (IOException | InterruptedException e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      return null;
    }
  }

  private static String localOrTagName(Element el) {
    String local = el.getLocalName();
    return local != null ? local : el.getTagName();
  }

  private static Element directChildElement(Element parent, String name) {
    NodeList children = parent.getChildNodes();
    for (int i = 0; i < children.getLength(); i++) {
      Node child = children.item(i);
      if (child instanceof Element el && name.equals(localOrTagName(el))) {
        return el;
      }
    }
    return null;
  }

  private static String directChildText(Element parent, String name, String fallback) {
    Element el = directChildElement(parent, name);
    return el != null ? el.getTextContent().trim() : fallback;
  }

  private static int parseIntOrZero(String s) {
    return parseIntOrDefault(s, 0);
  }

  private static int parseIntOrDefault(String s, int fallback) {
    if (s == null || s.isBlank()) {
      return fallback;
    }
    try {
      return Integer.parseInt(s.trim());
    } catch (NumberFormatException e) {
      return fallback;
    }
  }
}

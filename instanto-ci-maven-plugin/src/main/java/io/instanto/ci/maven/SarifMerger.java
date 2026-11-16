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
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Merges module-level SpotBugs SARIF files into a single SARIF 2.1.0 run and summarizes SpotBugs
 * finding severities across a repository.
 */
public final class SarifMerger {

  private static final Set<String> IGNORED_DIRS =
      Set.of(".build", ".compat-baseline", "node_modules", ".git");

  private static final Set<String> STAGING_TARGET_DIRS =
      Set.of(
          "compatibility-verification",
          "quality-review",
          "pages",
          "coverage-inheritance",
          "terser-inheritance");

  private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

  /** Summary of SpotBugs findings across a repository. */
  public record SpotBugsSummary(int high, int medium, int low, int files, int mergedRuns) {
    public int total() {
      return high + medium + low;
    }
  }

  /**
   * Scans {@code rootDir} for {@code * /target/spotbugs/*.sarif} files and merges them into {@code
   * outputFile}. Returns the number of merged runs, or {@code 0} if no SpotBugs SARIF files were
   * found.
   */
  public int mergeSpotBugsSarif(Path rootDir, Path outputFile) throws IOException {
    Path resolvedRoot = rootDir.toAbsolutePath().normalize();
    Path outputParent = outputFile.toAbsolutePath().normalize().getParent();
    if (outputParent != null && Files.exists(outputParent)) {
      deleteRecursively(outputParent);
    }

    List<Path> sarifFiles = findSpotBugsSarifFiles(resolvedRoot, outputFile);
    List<JsonNode> runs = new ArrayList<>();
    for (Path path : sarifFiles) {
      try {
        JsonNode root = mapper.readTree(Files.readString(path));
        JsonNode fileRuns = root.get("runs");
        if (fileRuns != null && fileRuns.isArray()) {
          for (JsonNode run : fileRuns) {
            runs.add(run);
          }
        }
      } catch (IOException e) {
        // Skip unreadable or malformed SARIF file
      }
    }

    if (runs.isEmpty()) {
      return 0;
    }

    ObjectNode driver = null;
    List<JsonNode> rules = new ArrayList<>();
    Map<String, Integer> ruleIndex = new LinkedHashMap<>();
    List<ObjectNode> results = new ArrayList<>();

    for (JsonNode run : runs) {
      JsonNode candidate = run.path("tool").path("driver");
      if (driver == null && candidate.isObject() && !candidate.isEmpty()) {
        driver = ((ObjectNode) candidate).deepCopy();
      }
      JsonNode candidateRules = candidate.get("rules");
      if (candidateRules != null && candidateRules.isArray()) {
        for (JsonNode rule : candidateRules) {
          String ruleId = textOrNull(rule, "id");
          if (ruleId != null && !ruleId.isEmpty() && !ruleIndex.containsKey(ruleId)) {
            ruleIndex.put(ruleId, rules.size());
            rules.add(rule);
          }
        }
      }
      JsonNode runResults = run.get("results");
      if (runResults != null && runResults.isArray()) {
        for (JsonNode res : runResults) {
          if (!res.isObject()) {
            continue;
          }
          ObjectNode copy = ((ObjectNode) res).deepCopy();
          String ruleId = textOrNull(copy, "ruleId");
          if (ruleId != null && ruleIndex.containsKey(ruleId)) {
            copy.put("ruleIndex", ruleIndex.get(ruleId));
          } else {
            copy.remove("ruleIndex");
          }
          results.add(copy);
        }
      }
    }

    ObjectNode mergedDriver =
        driver != null ? driver : mapper.createObjectNode().put("name", "SpotBugs");
    ArrayNode rulesArray = mapper.createArrayNode();
    rules.forEach(rulesArray::add);
    mergedDriver.set("rules", rulesArray);

    ArrayNode resultsArray = mapper.createArrayNode();
    results.forEach(resultsArray::add);

    ObjectNode runNode = mapper.createObjectNode();
    ObjectNode toolNode = mapper.createObjectNode();
    toolNode.set("driver", mergedDriver);
    runNode.set("tool", toolNode);
    runNode.set("results", resultsArray);

    ObjectNode sarifDoc = mapper.createObjectNode();
    sarifDoc.put("version", "2.1.0");
    sarifDoc.put("$schema", "https://json.schemastore.org/sarif-2.1.0.json");
    ArrayNode runsArray = mapper.createArrayNode();
    runsArray.add(runNode);
    sarifDoc.set("runs", runsArray);

    if (outputParent != null) {
      Files.createDirectories(outputParent);
    }
    Files.writeString(outputFile, mapper.writeValueAsString(sarifDoc) + "\n");
    return runs.size();
  }

  /** Collects SpotBugs finding counts from merged SARIF, per-module SARIF, or SpotBugs XML. */
  public SpotBugsSummary collectSummary(Path rootDir) throws IOException {
    Path resolvedRoot = rootDir.toAbsolutePath().normalize();
    Path merged = resolvedRoot.resolve("target/spotbugs-reports/spotbugs.sarif");
    List<Path> sarifFiles;
    if (Files.isRegularFile(merged)) {
      sarifFiles = List.of(merged);
    } else {
      sarifFiles =
          uniqueSortedPaths(
              findMatchingFiles(
                  resolvedRoot,
                  p ->
                      p.getFileName().toString().endsWith(".sarif")
                          && toPosix(p).contains("/target/spotbugs/")
                          && isPrimaryTargetArtifact(resolvedRoot, p)));
    }

    int high = 0;
    int medium = 0;
    int low = 0;
    int runsCount = 0;

    if (!sarifFiles.isEmpty()) {
      for (Path path : sarifFiles) {
        try {
          JsonNode data = mapper.readTree(Files.readString(path));
          JsonNode runs = data.get("runs");
          if (runs != null && runs.isArray()) {
            for (JsonNode run : runs) {
              runsCount++;
              JsonNode results = run.get("results");
              if (results != null && results.isArray()) {
                for (JsonNode result : results) {
                  String level = textOrDefault(result, "level", "warning");
                  if ("error".equals(level)) {
                    high++;
                  } else if ("note".equals(level)) {
                    low++;
                  } else {
                    medium++;
                  }
                }
              }
            }
          }
        } catch (IOException e) {
          // Ignore malformed SARIF
        }
      }
      return new SpotBugsSummary(high, medium, low, sarifFiles.size(), runsCount);
    }

    List<Path> xmlFiles =
        uniqueSortedPaths(
            findMatchingFiles(
                resolvedRoot,
                p -> {
                  String name = p.getFileName().toString();
                  String posix = toPosix(p);
                  return ((name.endsWith("-spotbugs.xml") && posix.contains("/target/spotbugs/"))
                          || ("spotbugsXml.xml".equals(name) && posix.contains("/target/")))
                      && isPrimaryTargetArtifact(resolvedRoot, p);
                }));
    for (Path path : xmlFiles) {
      try {
        Document doc = parseXmlSafe(path);
        NodeList bugs = doc.getElementsByTagName("BugInstance");
        for (int i = 0; i < bugs.getLength(); i++) {
          Element bug = (Element) bugs.item(i);
          String priority = bug.getAttribute("priority");
          if ("1".equals(priority)) {
            high++;
          } else if ("3".equals(priority)) {
            low++;
          } else {
            medium++;
          }
        }
      } catch (Exception e) {
        // Ignore malformed XML
      }
    }
    return new SpotBugsSummary(high, medium, low, xmlFiles.size(), 0);
  }

  static boolean isPrimaryTargetArtifact(Path root, Path path) {
    Path rel;
    try {
      rel = root.toAbsolutePath().normalize().relativize(path.toAbsolutePath().normalize());
    } catch (IllegalArgumentException e) {
      return false;
    }
    List<String> parts = new ArrayList<>();
    for (Path part : rel) {
      String name = part.toString();
      if (IGNORED_DIRS.contains(name)) {
        return false;
      }
      parts.add(name);
    }
    List<Integer> targetIndices = new ArrayList<>();
    for (int i = 0; i < parts.size(); i++) {
      if ("target".equals(parts.get(i))) {
        targetIndices.add(i);
      }
    }
    if (targetIndices.size() > 1) {
      return false;
    }
    if (!targetIndices.isEmpty()) {
      int idx = targetIndices.get(0);
      if (idx + 1 < parts.size() && STAGING_TARGET_DIRS.contains(parts.get(idx + 1))) {
        return false;
      }
    }
    return true;
  }

  static List<Path> uniqueSortedPaths(List<Path> candidates) {
    List<Path> sorted = new ArrayList<>(candidates);
    sorted.sort(Comparator.naturalOrder());
    Set<Path> seen = new LinkedHashSet<>();
    List<Path> unique = new ArrayList<>();
    for (Path path : sorted) {
      Path resolved = path.toAbsolutePath().normalize();
      if (seen.add(resolved)) {
        unique.add(path);
      }
    }
    return unique;
  }

  static List<Path> findMatchingFiles(Path rootDir, java.util.function.Predicate<Path> predicate)
      throws IOException {
    if (!Files.exists(rootDir)) {
      return List.of();
    }
    List<Path> matches = new ArrayList<>();
    try (Stream<Path> stream = Files.walk(rootDir)) {
      stream.filter(Files::isRegularFile).filter(predicate).forEach(matches::add);
    }
    return matches;
  }

  static Document parseXmlSafe(Path path) throws Exception {
    DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
    dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", false);
    dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
    dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
    dbf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
    dbf.setExpandEntityReferences(false);
    return dbf.newDocumentBuilder().parse(path.toFile());
  }

  static String toPosix(Path path) {
    return path.toString().replace(File.separatorChar, '/');
  }

  static String textOrNull(JsonNode node, String field) {
    if (node == null) {
      return null;
    }
    JsonNode child = node.get(field);
    return child != null && !child.isNull() ? child.asText() : null;
  }

  static String textOrDefault(JsonNode node, String field, String fallback) {
    String val = textOrNull(node, field);
    return val != null ? val : fallback;
  }

  private static List<Path> findSpotBugsSarifFiles(Path rootDir, Path outputFile)
      throws IOException {
    Path normalizedOut = outputFile.toAbsolutePath().normalize();
    return uniqueSortedPaths(
        findMatchingFiles(
            rootDir,
            p ->
                !p.toAbsolutePath().normalize().equals(normalizedOut)
                    && p.getFileName().toString().endsWith(".sarif")
                    && toPosix(p).contains("/target/spotbugs/")
                    && isPrimaryTargetArtifact(rootDir, p)));
  }

  static void deleteRecursively(Path root) throws IOException {
    if (!Files.exists(root)) {
      return;
    }
    try (Stream<Path> walk = Files.walk(root)) {
      walk.sorted(Comparator.reverseOrder())
          .forEach(
              p -> {
                try {
                  Files.deleteIfExists(p);
                } catch (IOException e) {
                  throw new UncheckedIOException(e);
                }
              });
    } catch (UncheckedIOException e) {
      throw e.getCause();
    }
  }
}

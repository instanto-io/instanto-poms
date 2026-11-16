/*
 * Copyright 2026 Carl Stainton
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.instanto.ci.maven;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Queries SonarQube APIs and renders workflow quality summaries and quality-gate failure
 * diagnostics.
 */
public final class SonarQubeClient {

  private final String hostUrl;
  private final String token;
  private final HttpClient httpClient;
  private final ObjectMapper mapper = new ObjectMapper();
  private final List<Integer> deniedCodes = new ArrayList<>();

  public SonarQubeClient(String hostUrl, String token) {
    this(
        hostUrl,
        token,
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build());
  }

  public SonarQubeClient(String hostUrl, String token, HttpClient httpClient) {
    String cleanHost =
        hostUrl == null || hostUrl.isBlank() ? "https://sonar.instanto.io" : hostUrl.trim();
    while (cleanHost.endsWith("/")) {
      cleanHost = cleanHost.substring(0, cleanHost.length() - 1);
    }
    this.hostUrl = cleanHost;
    this.token = token == null ? "" : token.trim();
    this.httpClient = httpClient;
  }

  public List<Integer> deniedCodes() {
    return List.copyOf(deniedCodes);
  }

  public JsonNode get(String path, Map<String, String> params) {
    StringBuilder url = new StringBuilder(hostUrl).append(path);
    if (params != null && !params.isEmpty()) {
      url.append("?");
      boolean first = true;
      for (Map.Entry<String, String> entry : params.entrySet()) {
        if (!first) {
          url.append("&");
        }
        first = false;
        url.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
            .append("=")
            .append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
      }
    }
    HttpRequest.Builder reqBuilder =
        HttpRequest.newBuilder(URI.create(url.toString())).timeout(Duration.ofSeconds(30)).GET();
    if (!token.isEmpty()) {
      reqBuilder.header("Authorization", "Bearer " + token);
    }
    try {
      HttpResponse<String> response =
          httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() >= 200 && response.statusCode() < 300) {
        return mapper.readTree(response.body());
      }
      deniedCodes.add(response.statusCode());
      return null;
    } catch (IOException | InterruptedException e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      return null;
    }
  }

  /** Renders the Markdown quality summary for SpotBugs, SBOM/CVE, and SonarQube. */
  public String renderQualitySummary(Path rootDir, String projectKey) throws IOException {
    List<String> lines = new ArrayList<>();
    lines.add("## Quality");
    lines.add("");
    lines.add("| Check | Result |");
    lines.add("| --- | --- |");

    Path sarif = rootDir.resolve("target/spotbugs-reports/spotbugs.sarif");
    if (Files.isRegularFile(sarif)) {
      SarifMerger.SpotBugsSummary sb = new SarifMerger().collectSummary(rootDir);
      lines.add(
          String.format(
              Locale.ROOT,
              "| SpotBugs | %d high, %d medium, %d low |",
              sb.high(),
              sb.medium(),
              sb.low()));
    } else {
      lines.add("| SpotBugs | no analysis produced |");
    }

    Path securityJson = rootDir.resolve("target/security/security-summary.json");
    if (Files.isRegularFile(securityJson)) {
      JsonNode sec = mapper.readTree(Files.readString(securityJson));
      JsonNode sbom = sec.path("sbom");
      JsonNode counts = sec.path("cve").path("counts");
      lines.add(
          String.format(
              Locale.ROOT,
              "| CycloneDX SBOM | %d components (%d BOMs) |",
              sbom.path("components").asInt(0),
              sbom.path("bom_files").asInt(0)));
      lines.add(
          String.format(
              Locale.ROOT,
              "| Dependency CVEs | %d critical, %d high, %d medium, %d low |",
              counts.path("CRITICAL").asInt(0),
              counts.path("HIGH").asInt(0),
              counts.path("MEDIUM").asInt(0),
              counts.path("LOW").asInt(0)));
    }

    if (token.isEmpty()) {
      lines.add("| SonarQube | analysis runs on main; summary needs a token |");
    } else {
      JsonNode gate = get("/api/qualitygates/project_status", Map.of("projectKey", projectKey));
      JsonNode measures =
          get(
              "/api/measures/component",
              Map.of(
                  "component",
                  projectKey,
                  "metricKeys",
                  String.join(
                      ",",
                      "coverage",
                      "new_coverage",
                      "bugs",
                      "vulnerabilities",
                      "code_smells",
                      "new_bugs",
                      "new_vulnerabilities",
                      "new_code_smells",
                      "duplicated_lines_density",
                      "new_duplicated_lines_density",
                      "ncloc")));
      JsonNode issues =
          get(
              "/api/issues/search",
              Map.of(
                  "componentKeys",
                  projectKey,
                  "inNewCodePeriod",
                  "true",
                  "resolved",
                  "false",
                  "ps",
                  "100"));

      if (deniedCodes.contains(401) || deniedCodes.contains(403)) {
        lines.add("| SonarQube | needs `SONAR_READ_TOKEN` with **Browse** permission |");
        System.out.println(
            "::warning::SonarQube summary needs SONAR_READ_TOKEN with Browse permission");
      } else if (gate != null || measures != null) {
        String status =
            gate != null
                ? SarifMerger.textOrDefault(gate.path("projectStatus"), "status", "unknown")
                : "unknown";
        Map<String, String> values = new LinkedHashMap<>();
        if (measures != null) {
          JsonNode measureArr = measures.path("component").path("measures");
          if (measureArr.isArray()) {
            for (JsonNode m : measureArr) {
              String metric = SarifMerger.textOrNull(m, "metric");
              String value = SarifMerger.textOrNull(m, "value");
              if (metric != null && value != null) {
                values.put(metric, value);
              }
            }
          }
        }
        String coverage = values.get("coverage");
        String newCoverage = values.get("new_coverage");
        lines.add(
            String.format(
                Locale.ROOT,
                "| SonarQube | gate %s; coverage %s; new-code coverage %s |",
                status.toLowerCase(Locale.ROOT),
                coverage != null ? coverage + "%" : "not measured",
                newCoverage != null ? newCoverage + "%" : "not measured"));

        if (gate != null) {
          JsonNode conditions = gate.path("projectStatus").path("conditions");
          if (conditions.isArray()) {
            for (JsonNode cond : conditions) {
              if ("ERROR".equals(SarifMerger.textOrNull(cond, "status"))) {
                lines.add(
                    String.format(
                        Locale.ROOT,
                        "| SonarQube gate | %s is %s (needs %s %s) |",
                        SarifMerger.textOrDefault(cond, "metricKey", "condition"),
                        SarifMerger.textOrDefault(cond, "actualValue", "?"),
                        SarifMerger.textOrDefault(cond, "comparator", "?"),
                        SarifMerger.textOrDefault(cond, "errorThreshold", "?")));
              }
            }
          }
        }

        lines.add(
            String.format(
                Locale.ROOT,
                "| SonarQube issues | %s bugs, %s vulnerabilities, %s code smells |",
                values.getOrDefault("bugs", "?"),
                values.getOrDefault("vulnerabilities", "?"),
                values.getOrDefault("code_smells", "?")));

        if (issues != null && issues.path("issues").isArray()) {
          Map<String, List<JsonNode>> grouped = new LinkedHashMap<>();
          for (JsonNode issue : issues.path("issues")) {
            String rule = SarifMerger.textOrDefault(issue, "rule", "unknown");
            grouped.computeIfAbsent(rule, k -> new ArrayList<>()).add(issue);
          }
          List<Map.Entry<String, List<JsonNode>>> sortedGroups =
              new ArrayList<>(grouped.entrySet());
          sortedGroups.sort(
              Comparator.<Map.Entry<String, List<JsonNode>>>comparingInt(e -> -e.getValue().size())
                  .thenComparing(Map.Entry::getKey));
          for (int i = 0; i < Math.min(5, sortedGroups.size()); i++) {
            Map.Entry<String, List<JsonNode>> entry = sortedGroups.get(i);
            JsonNode first = entry.getValue().get(0);
            String severity =
                SarifMerger.textOrDefault(first, "severity", "issue").toLowerCase(Locale.ROOT);
            String message = SarifMerger.textOrDefault(first, "message", "");
            lines.add(
                String.format(
                    Locale.ROOT,
                    "| SonarQube %s | %d × `%s`: %s |",
                    severity,
                    entry.getValue().size(),
                    entry.getKey(),
                    message));
          }
        }
      } else {
        lines.add("| SonarQube | gate and measurements unavailable |");
      }
    }

    lines.add("");
    lines.add("SpotBugs High findings fail the build. The SonarQube quality gate is");
    lines.add("advisory on `main`, so snapshots still publish when only the gate fails.");
    return String.join("\n", lines) + "\n";
  }

  /**
   * Prints diagnostics for a failed SonarQube quality gate from {@code
   * target/sonar/report-task.txt}.
   */
  public List<String> diagnoseQualityGate(Path rootDir, String projectKey) throws IOException {
    List<String> output = new ArrayList<>();
    Path report = rootDir.resolve("target/sonar/report-task.txt");
    if (!Files.isRegularFile(report)) {
      output.add("No Sonar report task: the scanner failed before submitting analysis.");
      return output;
    }
    Map<String, String> properties = new LinkedHashMap<>();
    for (String line : Files.readAllLines(report)) {
      int eq = line.indexOf('=');
      if (eq > 0) {
        properties.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
      }
    }
    String ceTaskId = properties.get("ceTaskId");
    if (ceTaskId == null || ceTaskId.isEmpty()) {
      output.add("No ceTaskId found in target/sonar/report-task.txt.");
      return output;
    }
    JsonNode taskResp = get("/api/ce/task", Map.of("id", ceTaskId));
    if (deniedCodes.contains(401) || deniedCodes.contains(403)) {
      output.add(
          "::warning::Sonar analysis token cannot read diagnostics. Supply SONAR_READ_TOKEN with Browse permission or inspect the project dashboard.");
      return output;
    }
    if (taskResp == null || !taskResp.has("task")) {
      output.add("Unable to read Sonar task status for " + ceTaskId);
      return output;
    }
    JsonNode task = taskResp.path("task");
    String analysisId = SarifMerger.textOrNull(task, "analysisId");
    if (analysisId == null || analysisId.isEmpty()) {
      output.add("Sonar task status: " + SarifMerger.textOrDefault(task, "status", "unknown"));
      return output;
    }
    JsonNode gateResp = get("/api/qualitygates/project_status", Map.of("analysisId", analysisId));
    if (gateResp != null && gateResp.has("projectStatus")) {
      JsonNode gate = gateResp.path("projectStatus");
      output.add(
          "Quality gate: "
              + SarifMerger.textOrDefault(gate, "status", "unknown")
              + " analysis: "
              + analysisId);
      JsonNode conditions = gate.path("conditions");
      if (conditions.isArray()) {
        for (JsonNode cond : conditions) {
          output.add(mapper.writeValueAsString(cond));
        }
      }
    }
    JsonNode issues =
        get(
            "/api/issues/search",
            Map.of(
                "componentKeys",
                projectKey,
                "inNewCodePeriod",
                "true",
                "resolved",
                "false",
                "ps",
                "100"));
    if (issues != null) {
      output.add("Open issues in new code: " + issues.path("total").asInt(0));
      JsonNode issueArr = issues.path("issues");
      if (issueArr.isArray()) {
        for (JsonNode issue : issueArr) {
          ObjectNode summary = mapper.createObjectNode();
          for (String key : List.of("component", "key", "line", "message", "rule", "severity")) {
            summary.set(key, issue.get(key));
          }
          output.add(mapper.writeValueAsString(summary));
        }
      }
    }
    return output;
  }

  static void appendStepSummary(String markdown) throws IOException {
    String summaryPath = System.getenv("GITHUB_STEP_SUMMARY");
    if (summaryPath != null && !summaryPath.isBlank()) {
      Files.writeString(
          Path.of(summaryPath),
          markdown,
          StandardCharsets.UTF_8,
          StandardOpenOption.CREATE,
          StandardOpenOption.APPEND);
    }
  }
}

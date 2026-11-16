/*
 * Copyright 2026 Carl Stainton
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.instanto.ci.maven;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

/**
 * Aggregates SpotBugs, CycloneDX SBOM, Trivy CVEs, test results, and coverage metrics for a
 * repository into JSON and Markdown reports.
 */
@Mojo(name = "security-report", aggregator = true, requiresProject = false, threadSafe = true)
public final class SecurityReportMojo extends AbstractMojo {

  @Parameter(property = "instanto.ci.rootDir", defaultValue = "${user.dir}")
  private File rootDir;

  @Parameter(property = "instanto.ci.repo")
  private String repo;

  @Parameter(property = "instanto.ci.revision", defaultValue = "")
  private String revision;

  @Parameter(property = "instanto.ci.trivyFiles")
  private List<String> trivyFiles;

  @Parameter(
      property = "instanto.ci.jsonOut",
      defaultValue = "target/security/security-summary.json")
  private String jsonOut;

  @Parameter(property = "instanto.ci.mdOut", defaultValue = "target/security/security-summary.md")
  private String mdOut;

  @Parameter(property = "instanto.ci.appendStepSummary", defaultValue = "false")
  private boolean appendStepSummary;

  @Override
  public void execute() throws MojoExecutionException {
    Path root = rootDir != null ? rootDir.toPath().toAbsolutePath().normalize() : Path.of(".");
    String resolvedRepo = QualitySummaryMojo.resolveProjectKey(repo);
    List<Path> trivyPaths = new ArrayList<>();
    if (trivyFiles != null && !trivyFiles.isEmpty()) {
      for (String raw : trivyFiles) {
        if (raw != null && !raw.isBlank()) {
          for (String part : raw.split(",")) {
            if (!part.isBlank()) {
              Path candidate = Path.of(part.trim());
              trivyPaths.add(candidate.isAbsolute() ? candidate : root.resolve(candidate));
            }
          }
        }
      }
    } else {
      Path sbomCve = root.resolve("target/security/trivy-sbom-cve.json");
      if (Files.isRegularFile(sbomCve)) {
        trivyPaths.add(sbomCve);
      }
      Path fsCve = root.resolve("target/security/trivy-fs-cve.json");
      if (Files.isRegularFile(fsCve)) {
        trivyPaths.add(fsCve);
      }
    }

    Path jsonPath = resolveOutput(root, jsonOut);
    Path mdPath = resolveOutput(root, mdOut);

    try {
      SecurityReportCollector collector = new SecurityReportCollector();
      ObjectNode report = collector.collectRepoReport(root, resolvedRepo, revision, trivyPaths);
      collector.writeJsonReport(report, jsonPath);
      String markdown = collector.renderRepoMarkdown(report);
      if (mdPath.getParent() != null) {
        Files.createDirectories(mdPath.getParent());
      }
      Files.writeString(mdPath, markdown);

      JsonNode counts = report.path("cve").path("counts");
      int critical = counts.path("CRITICAL").asInt(0);
      int high = counts.path("HIGH").asInt(0);
      if (critical > 0 || high > 0) {
        System.out.printf(
            "::warning title=Dependency CVEs in %s::Found %d CRITICAL and %d HIGH vulnerabilities.%n",
            resolvedRepo, critical, high);
      }
      if (appendStepSummary) {
        SonarQubeClient.appendStepSummary(markdown);
      }
      System.out.print(markdown);
    } catch (IOException e) {
      throw new MojoExecutionException("Failed to collect security report", e);
    }
  }

  static Path resolveOutput(Path root, String configured) {
    Path p = Path.of(configured);
    return p.isAbsolute() ? p : root.resolve(p);
  }
}

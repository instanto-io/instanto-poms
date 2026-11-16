/*
 * Copyright 2026 Carl Stainton
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.instanto.ci.maven;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

/** Aggregates per-repository security-summary JSON files into the unified Nightly Dashboard. */
@Mojo(name = "security-dashboard", aggregator = true, requiresProject = false, threadSafe = true)
public final class SecurityDashboardMojo extends AbstractMojo {

  @Parameter(property = "instanto.ci.inputs")
  private List<String> inputs;

  @Parameter(property = "instanto.ci.artifactsDir")
  private File artifactsDir;

  @Parameter(property = "instanto.ci.compatSummary")
  private File compatSummary;

  @Parameter(property = "instanto.ci.mdOut", defaultValue = "nightly-dashboard.md")
  private File mdOut;

  @Parameter(property = "instanto.ci.appendStepSummary", defaultValue = "false")
  private boolean appendStepSummary;

  @Override
  public void execute() throws MojoExecutionException {
    try {
      SecurityReportCollector collector = new SecurityReportCollector();
      List<Path> jsonPaths = new ArrayList<>();
      if (inputs != null) {
        for (String item : inputs) {
          if (item != null && !item.isBlank()) {
            for (String part : item.split(",")) {
              Path p = Path.of(part.trim());
              if (Files.isRegularFile(p)) {
                jsonPaths.add(p);
              }
            }
          }
        }
      }
      if (artifactsDir != null && artifactsDir.isDirectory()) {
        try (Stream<Path> walk = Files.walk(artifactsDir.toPath())) {
          walk.filter(Files::isRegularFile)
              .filter(p -> p.getFileName().toString().endsWith(".security-summary.json"))
              .sorted()
              .forEach(jsonPaths::add);
        }
      }

      List<JsonNode> reports = new ArrayList<>();
      for (Path p : SarifMerger.uniqueSortedPaths(jsonPaths)) {
        reports.add(collector.readJsonReport(p));
      }

      String compatText =
          compatSummary != null && compatSummary.isFile()
              ? Files.readString(compatSummary.toPath())
              : null;

      String md = collector.renderDashboard(reports, compatText);
      Path outPath = mdOut != null ? mdOut.toPath() : Path.of("nightly-dashboard.md");
      if (outPath.getParent() != null) {
        Files.createDirectories(outPath.getParent());
      }
      Files.writeString(outPath, md);
      if (appendStepSummary) {
        SonarQubeClient.appendStepSummary(md);
      }
      System.out.print(md);
    } catch (IOException e) {
      throw new MojoExecutionException("Failed to render nightly security dashboard", e);
    }
  }
}

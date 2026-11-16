/*
 * Copyright 2026 Carl Stainton
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.instanto.ci.maven;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

/**
 * Merges SpotBugs SARIF reports and writes a combined SpotBugs, SBOM/CVE, and SonarQube quality
 * summary.
 */
@Mojo(name = "quality-summary", aggregator = true, requiresProject = false, threadSafe = true)
public final class QualitySummaryMojo extends AbstractMojo {

  @Parameter(property = "instanto.ci.rootDir", defaultValue = "${user.dir}")
  private File rootDir;

  @Parameter(property = "instanto.ci.sonarProjectKey")
  private String sonarProjectKey;

  @Parameter(property = "instanto.ci.sonarHostUrl", defaultValue = "https://sonar.instanto.io")
  private String sonarHostUrl;

  @Parameter(property = "instanto.ci.sonarToken")
  private String sonarToken;

  @Parameter(property = "instanto.ci.summaryOutput")
  private File summaryOutput;

  @Parameter(property = "instanto.ci.appendStepSummary", defaultValue = "true")
  private boolean appendStepSummary;

  @Override
  public void execute() throws MojoExecutionException {
    Path root = rootDir != null ? rootDir.toPath() : Path.of(".");
    Path sarifOut = root.resolve("target/spotbugs-reports/spotbugs.sarif");
    try {
      if (!Files.isRegularFile(sarifOut)) {
        int merged = new SarifMerger().mergeSpotBugsSarif(root, sarifOut);
        if (merged > 0) {
          getLog().info("Merged " + merged + " SpotBugs run(s) into " + sarifOut);
        }
      }
      String resolvedKey = resolveProjectKey(sonarProjectKey);
      String resolvedHost =
          resolveEnvOrDefault(sonarHostUrl, "SONAR_HOST_URL", "https://sonar.instanto.io");
      String resolvedToken = resolveToken(sonarToken);

      SonarQubeClient client = new SonarQubeClient(resolvedHost, resolvedToken);
      String markdown = client.renderQualitySummary(root, resolvedKey);
      if (summaryOutput != null) {
        Path outPath = summaryOutput.toPath();
        if (outPath.getParent() != null) {
          Files.createDirectories(outPath.getParent());
        }
        Files.writeString(outPath, markdown);
      }
      if (appendStepSummary) {
        SonarQubeClient.appendStepSummary(markdown);
      }
      getLog().info(markdown.trim());
    } catch (IOException e) {
      throw new MojoExecutionException("Failed to generate quality summary", e);
    }
  }

  static String resolveProjectKey(String configured) {
    if (configured != null && !configured.isBlank()) {
      return configured.trim();
    }
    String envKey = System.getenv("SONAR_PROJECT_KEY");
    if (envKey != null && !envKey.isBlank()) {
      return envKey.trim();
    }
    String ghRepo = System.getenv("GITHUB_REPOSITORY");
    if (ghRepo != null && ghRepo.contains("/")) {
      return ghRepo.substring(ghRepo.lastIndexOf('/') + 1);
    }
    return ghRepo != null && !ghRepo.isBlank() ? ghRepo.trim() : "unknown";
  }

  static String resolveEnvOrDefault(String configured, String envName, String fallback) {
    String envVal = System.getenv(envName);
    if (envVal != null && !envVal.isBlank()) {
      return envVal.trim();
    }
    if (configured != null && !configured.isBlank()) {
      return configured.trim();
    }
    return fallback;
  }

  static String resolveToken(String configured) {
    if (configured != null && !configured.isBlank()) {
      return configured.trim();
    }
    String readToken = System.getenv("SONAR_READ_TOKEN");
    if (readToken != null && !readToken.isBlank()) {
      return readToken.trim();
    }
    String token = System.getenv("SONAR_TOKEN");
    return token != null ? token.trim() : "";
  }
}

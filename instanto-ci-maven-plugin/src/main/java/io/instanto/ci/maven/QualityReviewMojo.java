/*
 * Copyright 2026 Carl Stainton
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.instanto.ci.maven;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

/**
 * Collects fresh reactor analysis outputs (SpotBugs, Surefire, JaCoCo, SonarQube) and source
 * SHA-256 hashes into a quality-review bundle.
 */
@Mojo(name = "quality-review", aggregator = true, requiresProject = false, threadSafe = true)
public final class QualityReviewMojo extends AbstractMojo {

  private static final DateTimeFormatter TS_FMT =
      DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

  @Parameter(property = "instanto.ci.rootDir", defaultValue = "${user.dir}")
  private File rootDir;

  @Parameter(property = "instanto.ci.output")
  private File output;

  @Parameter(property = "instanto.ci.sinceMarker")
  private File sinceMarker;

  @Parameter(property = "instanto.ci.buildStatus", defaultValue = "unknown")
  private String buildStatus;

  @Parameter(property = "instanto.ci.buildLog")
  private File buildLog;

  @Parameter(property = "instanto.ci.sonarHostUrl", defaultValue = "https://sonar.instanto.io")
  private String sonarHostUrl;

  @Parameter(property = "instanto.ci.sonarProjectKey")
  private String sonarProjectKey;

  @Override
  public void execute() throws MojoExecutionException {
    Path root = rootDir != null ? rootDir.toPath().toAbsolutePath().normalize() : Path.of(".");
    Path outDir =
        output != null
            ? output.toPath()
            : root.resolve("target/quality-review").resolve(TS_FMT.format(Instant.now()));
    long sinceMillis = 0L;
    if (sinceMarker != null) {
      if (!sinceMarker.isFile()) {
        throw new MojoExecutionException("Missing sinceMarker file: " + sinceMarker);
      }
      try {
        sinceMillis = Files.getLastModifiedTime(sinceMarker.toPath()).toMillis();
      } catch (Exception e) {
        throw new MojoExecutionException("Cannot read sinceMarker timestamp", e);
      }
    }

    String resolvedHost =
        QualitySummaryMojo.resolveEnvOrDefault(
            sonarHostUrl, "SONAR_HOST_URL", "https://sonar.instanto.io");
    String resolvedKey = QualitySummaryMojo.resolveProjectKey(sonarProjectKey);

    try {
      QualityReviewCollector collector = new QualityReviewCollector();
      ObjectNode result =
          collector.collect(
              root,
              outDir,
              sinceMillis,
              buildStatus,
              buildLog != null ? buildLog.toPath() : null,
              resolvedHost,
              resolvedKey);
      getLog()
          .info("Collected " + result.path("modules").size() + " reactor modules into " + outDir);
      getLog()
          .info(
              "SonarQube: " + SarifMerger.textOrDefault(result.path("sonar"), "status", "unknown"));
    } catch (Exception e) {
      throw new MojoExecutionException("Failed to collect quality review reports", e);
    }
  }
}

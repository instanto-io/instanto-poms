/*
 * Copyright 2026 Carl Stainton
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.instanto.ci.maven;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

/** Packages and uploads CI report archives to Forgejo's generic package registry. */
@Mojo(name = "publish-report", aggregator = true, requiresProject = false, threadSafe = true)
public final class PublishReportMojo extends AbstractMojo {

  @Parameter(property = "instanto.ci.rootDir", defaultValue = "${user.dir}")
  private File rootDir;

  @Parameter(property = "instanto.ci.name", required = true)
  private String name;

  @Parameter(property = "instanto.ci.paths", required = true)
  private List<String> paths;

  @Parameter(property = "instanto.ci.registryUrl", defaultValue = ReportPublisher.DEFAULT_REGISTRY)
  private String registryUrl;

  @Override
  public void execute() throws MojoExecutionException {
    Path base = rootDir != null ? rootDir.toPath() : Path.of(".");
    List<String> expandedPaths = new ArrayList<>();
    if (paths != null) {
      for (String raw : paths) {
        if (raw != null && !raw.isBlank()) {
          for (String part : raw.split(",")) {
            if (!part.isBlank()) {
              expandedPaths.add(part.trim());
            }
          }
        }
      }
    }

    String username = System.getenv("FORGEJO_PACKAGE_USER");
    String token = System.getenv("FORGEJO_PACKAGE_TOKEN");
    if (token == null || token.isBlank()) {
      token = System.getenv("FORGEJO_PACKAGES_TOKEN");
    }
    String ghRepo = System.getenv("GITHUB_REPOSITORY");
    if (ghRepo == null || ghRepo.isBlank()) {
      ghRepo = "instanto-io/local";
    }
    String runId = System.getenv("GITHUB_RUN_ID");
    if (runId == null || runId.isBlank()) {
      runId = "0";
    }
    String runAttempt = System.getenv("GITHUB_RUN_ATTEMPT");
    if (runAttempt == null || runAttempt.isBlank()) {
      runAttempt = "1";
    }

    try {
      ReportPublisher publisher =
          new ReportPublisher(
              registryUrl,
              java.net.http.HttpClient.newBuilder()
                  .connectTimeout(java.time.Duration.ofSeconds(30))
                  .followRedirects(java.net.http.HttpClient.Redirect.NEVER)
                  .build());
      ReportPublisher.PublishedReport published =
          publisher.publishReport(
              base, name, expandedPaths, username, token, ghRepo, runId, runAttempt);
      if (published == null) {
        System.out.println("No report files were produced; nothing to publish.");
        return;
      }
      System.out.printf(
          Locale.ROOT,
          "Published %d report files: %s%nSHA-256: %s%n",
          published.fileCount(),
          published.url(),
          published.sha256());
      String summaryMd =
          String.format(
              Locale.ROOT,
              "%n### %s%n%n[Download reports](%s) (%d files).%n%nSHA-256: `%s`%n",
              name,
              published.url(),
              published.fileCount(),
              published.sha256());
      SonarQubeClient.appendStepSummary(summaryMd);
      String output = System.getenv("GITHUB_OUTPUT");
      if (output != null && !output.isBlank()) {
        Files.writeString(
            Path.of(output),
            "report-url=" + published.url() + "\n",
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND);
      }
    } catch (Exception e) {
      throw new MojoExecutionException(e.getMessage(), e);
    }
  }
}

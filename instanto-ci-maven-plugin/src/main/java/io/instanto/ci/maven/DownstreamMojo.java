/*
 * Copyright 2026 Carl Stainton
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.instanto.ci.maven;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

/**
 * Resolves an upstream repository and builds all transitive downstream consumers in an isolated
 * Maven repository.
 */
@Mojo(name = "downstream", aggregator = true, requiresProject = false, threadSafe = true)
public final class DownstreamMojo extends AbstractMojo {

  @Parameter(
      property = "instanto.ci.catalog",
      defaultValue = ".github/downstream-projects.json",
      required = true)
  private File catalog;

  @Parameter(property = "instanto.ci.upstream")
  private String upstream;

  @Parameter(property = "instanto.ci.source", defaultValue = "${user.dir}")
  private File source;

  @Parameter(property = "instanto.ci.workspace")
  private File workspace;

  @Parameter(property = "instanto.ci.settings")
  private File settings;

  @Parameter(property = "instanto.ci.planOnly", defaultValue = "false")
  private boolean planOnly;

  @Parameter(property = "instanto.ci.resolveOnly", defaultValue = "false")
  private boolean resolveOnly;

  @Parameter(property = "instanto.ci.verifyUpstream", defaultValue = "false")
  private boolean verifyUpstream;

  @Parameter(property = "instanto.ci.skipAnalysis", defaultValue = "false")
  private boolean skipAnalysis;

  @Override
  public void execute() throws MojoExecutionException, MojoFailureException {
    try {
      DownstreamOrchestrator orchestrator = new DownstreamOrchestrator();
      Map<String, DownstreamOrchestrator.ProjectConfig> projects =
          orchestrator.loadCatalog(catalog.toPath());

      String requestedUpstream = upstream != null ? upstream.trim() : "";
      String ghRepo = System.getenv("GITHUB_REPOSITORY");
      String repoShort =
          ghRepo != null && ghRepo.contains("/")
              ? ghRepo.substring(ghRepo.lastIndexOf('/') + 1)
              : (ghRepo != null ? ghRepo : "");
      String resolvedName = !requestedUpstream.isEmpty() ? requestedUpstream : repoShort;
      Path resolvedSource =
          source != null
              ? source.toPath().toAbsolutePath().normalize()
              : Path.of(".").toAbsolutePath().normalize();

      if (resolveOnly) {
        if (!requestedUpstream.isEmpty()) {
          DownstreamOrchestrator.ProjectConfig cfg = projects.get(resolvedName);
          if (cfg == null) {
            throw new MojoFailureException("Unknown upstream project: " + resolvedName);
          }
          resolvedSource = Path.of("manual-upstream").toAbsolutePath().normalize();
          orchestrator.checkout(cfg.repository(), resolvedSource, null);
        } else if (projects.containsKey(resolvedName)
            && ghRepo != null
            && !projects.get(resolvedName).repository().equals(ghRepo)) {
          throw new MojoFailureException("Calling repository does not match the catalog");
        }
        String ghOut = System.getenv("GITHUB_OUTPUT");
        if (ghOut != null && !ghOut.isBlank()) {
          Files.writeString(
              Path.of(ghOut),
              "name=" + resolvedName + "\nsource=" + resolvedSource + "\n",
              StandardCharsets.UTF_8,
              StandardOpenOption.CREATE,
              StandardOpenOption.APPEND);
        }
        return;
      }

      int exitCode =
          orchestrator.runDownstream(
              catalog.toPath(),
              resolvedName,
              resolvedSource,
              workspace != null ? workspace.toPath() : null,
              settings != null ? settings.toPath() : null,
              planOnly,
              verifyUpstream,
              skipAnalysis);
      if (exitCode != 0) {
        throw new MojoFailureException("Downstream compatibility failed for " + resolvedName);
      }
    } catch (MojoFailureException e) {
      throw e;
    } catch (Exception e) {
      throw new MojoExecutionException("Downstream compatibility execution failed", e);
    }
  }
}

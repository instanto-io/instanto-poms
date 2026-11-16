/*
 * Copyright 2026 Carl Stainton
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.instanto.ci.maven;

import io.instanto.ci.maven.DownstreamOrchestrator.ProjectConfig;
import java.io.File;
import java.util.Map;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

/** Runs the full verification and coverage suite for one repository in the nightly pipeline. */
@Mojo(name = "nightly-verify", aggregator = true, requiresProject = false, threadSafe = true)
public final class NightlyVerifyMojo extends AbstractMojo {

  @Parameter(
      property = "instanto.ci.catalog",
      defaultValue = ".github/downstream-projects.json",
      required = true)
  private File catalog;

  @Parameter(property = "instanto.ci.project", required = true)
  private String project;

  @Parameter(property = "instanto.ci.checkout", required = true)
  private File checkout;

  @Parameter(property = "instanto.ci.repository", required = true)
  private File repository;

  @Parameter(property = "instanto.ci.settings", required = true)
  private File settings;

  @Parameter(property = "instanto.ci.logDir", required = true)
  private File logDir;

  @Parameter(property = "instanto.ci.overlay")
  private File overlay;

  @Override
  public void execute() throws MojoExecutionException, MojoFailureException {
    try {
      NightlyCoordinator coordinator = new NightlyCoordinator();
      Map<String, ProjectConfig> projects = coordinator.downstream().loadCatalog(catalog.toPath());
      ProjectConfig cfg = projects.get(project);
      if (cfg == null) {
        throw new MojoExecutionException("Unknown project in catalog: " + project);
      }
      boolean ok =
          coordinator.runVerifyProject(
              cfg,
              project,
              checkout.toPath(),
              repository.toPath(),
              settings.toPath(),
              logDir.toPath(),
              overlay != null ? overlay.toPath() : null,
              null);
      if (!ok) {
        throw new MojoFailureException("Nightly verification failed for " + project);
      }
    } catch (MojoFailureException e) {
      throw e;
    } catch (Exception e) {
      throw new MojoExecutionException("Failed to execute nightly verification for " + project, e);
    }
  }
}

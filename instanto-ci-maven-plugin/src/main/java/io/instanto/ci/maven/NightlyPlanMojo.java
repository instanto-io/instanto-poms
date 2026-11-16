/*
 * Copyright 2026 Carl Stainton
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.instanto.ci.maven;

import java.io.File;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

/** Detects upstream changes since the last baseline and writes the nightly build plan. */
@Mojo(name = "nightly-plan", aggregator = true, requiresProject = false, threadSafe = true)
public final class NightlyPlanMojo extends AbstractMojo {

  @Parameter(
      property = "instanto.ci.catalog",
      defaultValue = ".github/downstream-projects.json",
      required = true)
  private File catalog;

  @Parameter(property = "instanto.ci.baseline")
  private File baseline;

  @Parameter(property = "instanto.ci.outputDir", defaultValue = "plan-out", required = true)
  private File outputDir;

  @Parameter(property = "instanto.ci.upstream", defaultValue = "")
  private String upstream;

  @Parameter(property = "instanto.ci.force", defaultValue = "false")
  private boolean force;

  @Parameter(property = "instanto.ci.bootstrapOnly", defaultValue = "false")
  private boolean bootstrapOnly;

  @Override
  public void execute() throws MojoExecutionException {
    try {
      new NightlyCoordinator()
          .runPlanCommand(
              catalog.toPath(),
              baseline != null ? baseline.toPath() : null,
              outputDir.toPath(),
              upstream,
              force,
              bootstrapOnly);
    } catch (Exception e) {
      throw new MojoExecutionException("Failed to compute nightly build plan", e);
    }
  }
}

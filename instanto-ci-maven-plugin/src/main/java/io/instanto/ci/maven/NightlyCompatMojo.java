/*
 * Copyright 2026 Carl Stainton
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.instanto.ci.maven;

import java.io.File;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

/** Runs the incremental nightly compatibility cascade and blame isolation. */
@Mojo(name = "nightly-compat", aggregator = true, requiresProject = false, threadSafe = true)
public final class NightlyCompatMojo extends AbstractMojo {

  @Parameter(
      property = "instanto.ci.catalog",
      defaultValue = ".github/downstream-projects.json",
      required = true)
  private File catalog;

  @Parameter(property = "instanto.ci.plan", required = true)
  private File plan;

  @Parameter(property = "instanto.ci.baseline")
  private File baseline;

  @Parameter(property = "instanto.ci.workspace", required = true)
  private File workspace;

  @Parameter(property = "instanto.ci.settings")
  private File settings;

  @Parameter(property = "instanto.ci.overlayOut")
  private File overlayOut;

  @Parameter(property = "instanto.ci.maxBlameCandidates", defaultValue = "4")
  private int maxBlameCandidates;

  @Override
  public void execute() throws MojoExecutionException, MojoFailureException {
    try {
      int exitCode =
          new NightlyCoordinator()
              .runCompatCommand(
                  catalog.toPath(),
                  plan.toPath(),
                  baseline != null ? baseline.toPath() : null,
                  workspace.toPath(),
                  settings != null ? settings.toPath() : null,
                  overlayOut != null ? overlayOut.toPath() : null,
                  maxBlameCandidates);
      if (exitCode != 0) {
        throw new MojoFailureException("Nightly compatibility cascade failed");
      }
    } catch (MojoFailureException e) {
      throw e;
    } catch (Exception e) {
      throw new MojoExecutionException("Failed to run nightly compatibility cascade", e);
    }
  }
}

/*
 * Copyright 2026 Carl Stainton
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.instanto.ci.maven;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

/** Merges per-module SpotBugs SARIF files into a single SARIF 2.1.0 report. */
@Mojo(name = "merge-sarif", aggregator = true, requiresProject = false, threadSafe = true)
public final class MergeSarifMojo extends AbstractMojo {

  @Parameter(property = "instanto.ci.rootDir", defaultValue = "${user.dir}")
  private File rootDir;

  @Parameter(
      property = "instanto.ci.sarifOutput",
      defaultValue = "target/spotbugs-reports/spotbugs.sarif")
  private String sarifOutput;

  @Override
  public void execute() throws MojoExecutionException {
    Path root = rootDir != null ? rootDir.toPath() : Path.of(".");
    Path out = root.resolve(sarifOutput);
    try {
      int merged = new SarifMerger().mergeSpotBugsSarif(root, out);
      if (merged == 0) {
        getLog().info("No SpotBugs SARIF files found");
      } else {
        getLog().info("Merged " + merged + " SpotBugs run(s) into " + out);
      }
    } catch (IOException e) {
      throw new MojoExecutionException("Failed to merge SpotBugs SARIF reports", e);
    }
  }
}

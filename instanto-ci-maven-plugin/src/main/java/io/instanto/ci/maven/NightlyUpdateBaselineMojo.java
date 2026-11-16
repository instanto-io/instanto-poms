/*
 * Copyright 2026 Carl Stainton
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.instanto.ci.maven;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

/** Updates baseline.json with nightly compatibility and Stage 3 verification outcomes. */
@Mojo(
    name = "nightly-update-baseline",
    aggregator = true,
    requiresProject = false,
    threadSafe = true)
public final class NightlyUpdateBaselineMojo extends AbstractMojo {

  @Parameter(
      property = "instanto.ci.catalog",
      defaultValue = ".github/downstream-projects.json",
      required = true)
  private File catalog;

  @Parameter(property = "instanto.ci.baseline")
  private File baseline;

  @Parameter(property = "instanto.ci.plan", required = true)
  private File plan;

  @Parameter(property = "instanto.ci.compatResults")
  private File compatResults;

  @Parameter(property = "instanto.ci.stage3Status")
  private List<String> stage3Status;

  @Parameter(property = "instanto.ci.artifactsDir")
  private File artifactsDir;

  @Parameter(property = "instanto.ci.out", required = true)
  private File out;

  @Override
  public void execute() throws MojoExecutionException {
    try {
      List<String> combinedStage3 = new ArrayList<>();
      if (stage3Status != null) {
        for (String item : stage3Status) {
          if (item != null && !item.isBlank()) {
            for (String part : item.split(",")) {
              if (!part.isBlank()) {
                combinedStage3.add(part.trim());
              }
            }
          }
        }
      }
      if (artifactsDir != null && artifactsDir.isDirectory()) {
        ObjectMapper mapper = new ObjectMapper();
        try (Stream<Path> walk = Files.walk(artifactsDir.toPath())) {
          List<Path> statusFiles =
              walk.filter(Files::isRegularFile)
                  .filter(p -> p.getFileName().toString().endsWith(".status.json"))
                  .sorted()
                  .toList();
          for (Path sf : statusFiles) {
            JsonNode doc = mapper.readTree(Files.readString(sf));
            String p = SarifMerger.textOrNull(doc, "project");
            String s = SarifMerger.textOrNull(doc, "status");
            if (p != null && s != null) {
              combinedStage3.add(p + "=" + s);
            }
          }
        }
      }

      new NightlyCoordinator()
          .runUpdateBaselineCommand(
              catalog.toPath(),
              baseline != null ? baseline.toPath() : null,
              plan.toPath(),
              compatResults != null ? compatResults.toPath() : null,
              combinedStage3,
              out.toPath());
    } catch (Exception e) {
      throw new MojoExecutionException("Failed to update nightly baseline", e);
    }
  }
}

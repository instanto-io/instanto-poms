/*
 * Copyright 2026 Carl Stainton
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.instanto.ci.maven;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

/** Prints diagnostic details when a SonarQube quality gate fails. */
@Mojo(name = "sonar-diagnose", aggregator = true, requiresProject = false, threadSafe = true)
public final class SonarDiagnoseMojo extends AbstractMojo {

  @Parameter(property = "instanto.ci.rootDir", defaultValue = "${user.dir}")
  private File rootDir;

  @Parameter(property = "instanto.ci.sonarProjectKey")
  private String sonarProjectKey;

  @Parameter(property = "instanto.ci.sonarHostUrl", defaultValue = "https://sonar.instanto.io")
  private String sonarHostUrl;

  @Parameter(property = "instanto.ci.sonarToken")
  private String sonarToken;

  @Override
  public void execute() throws MojoExecutionException {
    Path root = rootDir != null ? rootDir.toPath() : Path.of(".");
    String resolvedKey = QualitySummaryMojo.resolveProjectKey(sonarProjectKey);
    String resolvedHost =
        QualitySummaryMojo.resolveEnvOrDefault(
            sonarHostUrl, "SONAR_HOST_URL", "https://sonar.instanto.io");
    String resolvedToken = QualitySummaryMojo.resolveToken(sonarToken);

    try {
      SonarQubeClient client = new SonarQubeClient(resolvedHost, resolvedToken);
      List<String> lines = client.diagnoseQualityGate(root, resolvedKey);
      for (String line : lines) {
        System.out.println(line);
      }
    } catch (IOException e) {
      throw new MojoExecutionException("Failed to diagnose SonarQube quality gate", e);
    }
  }
}

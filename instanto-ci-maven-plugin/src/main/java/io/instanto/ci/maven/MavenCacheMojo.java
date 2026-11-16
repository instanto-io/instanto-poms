/*
 * Copyright 2026 Carl Stainton
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.instanto.ci.maven;

import java.io.IOException;
import java.nio.file.Path;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

/** Prepares or saves the runner-local Maven Central download cache. */
@Mojo(name = "maven-cache", aggregator = true, requiresProject = false, threadSafe = true)
public final class MavenCacheMojo extends AbstractMojo {

  @Parameter(property = "instanto.ci.cacheMode", required = true)
  private String cacheMode;

  @Parameter(property = "instanto.ci.mavenArgs", defaultValue = "")
  private String mavenArgs;

  @Override
  public void execute() throws MojoExecutionException {
    String envCache = System.getenv("MAVEN_DOWNLOAD_CACHE");
    Path cacheRoot =
        envCache != null && !envCache.isBlank()
            ? Path.of(envCache.trim())
            : Path.of(System.getProperty("user.home"), ".local/share/instanto-maven-cache/v1");
    String ghEnv = System.getenv("GITHUB_ENV");
    Path ghEnvPath = ghEnv != null && !ghEnv.isBlank() ? Path.of(ghEnv) : null;
    try {
      new MavenDownloadCache().executeMode(cacheMode, mavenArgs, cacheRoot, ghEnvPath);
    } catch (IOException | RuntimeException e) {
      throw new MojoExecutionException("Maven download cache failed in mode " + cacheMode, e);
    }
  }
}

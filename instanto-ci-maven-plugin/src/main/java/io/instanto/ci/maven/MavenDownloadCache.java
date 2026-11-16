/*
 * Copyright 2026 Carl Stainton
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.instanto.ci.maven;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Restores and saves public Maven Central release artifacts into a runner-local download cache
 * while excluding snapshots and locally installed artifacts.
 */
public final class MavenDownloadCache {

  public static final String CENTRAL = "https://repo.maven.apache.org/maven2";
  public static final String FALLBACK =
      "https://maven-central.storage-download.googleapis.com/maven2";
  public static final String PROBE = "/org/apache/ant/ant/1.10.15/ant-1.10.15.pom";
  private static final Set<String> PUBLIC_REPOSITORIES = Set.of("central", "instanto-central");
  private static final List<String> CHECKSUM_SUFFIXES =
      List.of(".sha1", ".sha256", ".sha512", ".md5", ".asc");

  private final HttpClient httpClient;

  public MavenDownloadCache() {
    this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build());
  }

  public MavenDownloadCache(HttpClient httpClient) {
    this.httpClient = httpClient;
  }

  public static Path resolveLocalRepository(String arguments) {
    String repo = Path.of(System.getProperty("user.home"), ".m2", "repository").toString();
    if (arguments != null && !arguments.isBlank()) {
      for (String token : splitArgs(arguments)) {
        if (token.startsWith("-Dmaven.repo.local=")) {
          repo = token.substring("-Dmaven.repo.local=".length());
        }
      }
    }
    return Path.of(repo).toAbsolutePath().normalize();
  }

  public static String normalizeRemoteMarker(String text) {
    if (text == null || text.isEmpty()) {
      return "";
    }
    String[] rawLines = text.split("\\r?\\n", -1);
    int limit = text.endsWith("\n") ? rawLines.length - 1 : rawLines.length;
    if (limit == 0) {
      return "";
    }
    List<String> lines = new ArrayList<>();
    for (int i = 0; i < limit; i++) {
      String line = rawLines[i];
      if (line.startsWith("#") || !line.contains(">") || !line.contains("=")) {
        lines.add(line);
        continue;
      }
      int eq = line.indexOf('=');
      String left = line.substring(0, eq);
      String right = line.substring(eq + 1);
      int gt = left.lastIndexOf('>');
      String name = left.substring(0, gt);
      String origin = left.substring(gt + 1);
      if (PUBLIC_REPOSITORIES.contains(origin)) {
        lines.add(name + ">central=" + right);
      } else {
        lines.add(line);
      }
    }
    if (lines.isEmpty()) {
      return "";
    }
    return String.join("\n", lines) + "\n";
  }

  public int restore(Path cacheDir, Path repositoryDir) throws IOException {
    if (!Files.exists(cacheDir)) {
      return 0;
    }
    int count = 0;
    List<Path> sources;
    try (Stream<Path> walk = Files.walk(cacheDir)) {
      sources = walk.toList();
    }
    for (Path source : sources) {
      if (Files.isRegularFile(source) && !Files.isSymbolicLink(source)) {
        Path target = repositoryDir.resolve(cacheDir.relativize(source));
        if ("_remote.repositories".equals(source.getFileName().toString())) {
          if (target.getParent() != null) {
            Files.createDirectories(target.getParent());
          }
          Path tmp = target.resolveSibling(target.getFileName() + ".cache-tmp");
          Files.writeString(tmp, normalizeRemoteMarker(Files.readString(source)));
          Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        } else {
          copyFileAtomic(source, target);
        }
        count++;
      }
    }
    return count;
  }

  public int save(Path repositoryDir, Path cacheDir) throws IOException {
    if (!Files.exists(repositoryDir)) {
      return 0;
    }
    int count = 0;
    List<Path> markers;
    try (Stream<Path> walk = Files.walk(repositoryDir)) {
      markers =
          walk.filter(Files::isRegularFile)
              .filter(p -> "_remote.repositories".equals(p.getFileName().toString()))
              .toList();
    }
    for (Path marker : markers) {
      Path relative = repositoryDir.relativize(marker.getParent());
      if (isInstantoOrSnapshot(relative)) {
        continue;
      }
      List<MarkerEntry> entries = new ArrayList<>();
      for (String line : Files.readAllLines(marker)) {
        if (line.startsWith("#") || !line.contains(">") || !line.contains("=")) {
          continue;
        }
        String left = line.substring(0, line.indexOf('='));
        int gt = left.lastIndexOf('>');
        String name = left.substring(0, gt);
        String origin = left.substring(gt + 1);
        if (origin.isEmpty()) {
          entries.clear();
          break;
        }
        if (PUBLIC_REPOSITORIES.contains(origin) && !name.contains("/") && !name.contains("\\")) {
          entries.add(new MarkerEntry(name, name + ">central="));
        }
      }
      if (entries.isEmpty()) {
        continue;
      }
      List<String> tracked = new ArrayList<>();
      for (MarkerEntry entry : entries) {
        Path source = marker.getParent().resolve(entry.name());
        if (!Files.isRegularFile(source) || Files.isSymbolicLink(source)) {
          continue;
        }
        Path destDir = cacheDir.resolve(relative);
        copyFileAtomic(source, destDir.resolve(entry.name()));
        for (String suffix : CHECKSUM_SUFFIXES) {
          Path checksum = source.resolveSibling(source.getFileName() + suffix);
          if (Files.isRegularFile(checksum) && !Files.isSymbolicLink(checksum)) {
            copyFileAtomic(checksum, destDir.resolve(checksum.getFileName()));
          }
        }
        tracked.add(entry.line());
        count++;
      }
      if (!tracked.isEmpty()) {
        Path targetMarker = cacheDir.resolve(relative).resolve(marker.getFileName());
        if (targetMarker.getParent() != null) {
          Files.createDirectories(targetMarker.getParent());
        }
        Files.writeString(targetMarker, String.join("\n", tracked) + "\n");
      }
    }
    return count;
  }

  public boolean isReachable(String baseUrl) {
    String clean = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    try {
      HttpRequest req =
          HttpRequest.newBuilder(URI.create(clean + PROBE))
              .timeout(Duration.ofSeconds(8))
              .method("HEAD", HttpRequest.BodyPublishers.noBody())
              .build();
      HttpResponse<Void> resp = httpClient.send(req, HttpResponse.BodyHandlers.discarding());
      return resp.statusCode() == 200;
    } catch (Exception e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      return false;
    }
  }

  public String resolveCentralMirror(String configured) {
    if (configured != null && !configured.isBlank()) {
      URI parsed = URI.create(configured.trim());
      if (!"https".equalsIgnoreCase(parsed.getScheme())
          || parsed.getHost() == null
          || parsed.getUserInfo() != null) {
        throw new IllegalArgumentException(
            "MAVEN_CENTRAL_MIRROR_URL must be an HTTPS URL without credentials");
      }
      String clean = configured.trim();
      while (clean.endsWith("/")) {
        clean = clean.substring(0, clean.length() - 1);
      }
      return clean;
    }
    if (isReachable(CENTRAL)) {
      return "";
    }
    if (isReachable(FALLBACK)) {
      System.out.println(
          "Central unavailable or rate-limited; using Google's public Central mirror");
      return FALLBACK;
    }
    throw new IllegalStateException("Neither Maven Central nor its fallback mirror is reachable");
  }

  public void executeMode(String mode, String mavenArgs, Path cacheRoot, Path githubEnvFile)
      throws IOException {
    Path centralCache = cacheRoot.resolve("central");
    Path repository = resolveLocalRepository(mavenArgs);
    Files.createDirectories(centralCache);
    Files.createDirectories(repository);
    if ("prepare".equals(mode)) {
      int restored = restore(centralCache, repository);
      Path wrapper = cacheRoot.resolve("wrapper-home");
      Files.createDirectories(wrapper);
      String mirror = resolveCentralMirror(System.getenv("MAVEN_CENTRAL_MIRROR_URL"));
      if (githubEnvFile != null) {
        StringBuilder envLines = new StringBuilder();
        envLines.append("MAVEN_DOWNLOAD_CACHE=").append(cacheRoot).append("\n");
        envLines.append("MAVEN_USER_HOME=").append(wrapper).append("\n");
        if (!mirror.isEmpty()) {
          envLines.append("MAVEN_CENTRAL_MIRROR_URL=").append(mirror).append("\n");
          envLines.append("MVNW_REPOURL=").append(mirror).append("\n");
        }
        Files.writeString(
            githubEnvFile,
            envLines.toString(),
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND);
      }
      System.out.println(
          "Runner download cache: restored " + restored + " files into " + repository);
      System.out.println("Maven wrapper cache: " + wrapper);
    } else if ("save".equals(mode)) {
      int saved = save(repository, centralCache);
      System.out.println("Runner download cache: saved " + saved + " Central release artifacts");
    } else {
      throw new IllegalArgumentException("Unsupported cache mode: " + mode);
    }
  }

  private record MarkerEntry(String name, String line) {}

  private static boolean isInstantoOrSnapshot(Path relative) {
    if (relative.getNameCount() >= 2
        && "io".equals(relative.getName(0).toString())
        && "instanto".equals(relative.getName(1).toString())) {
      return true;
    }
    for (Path part : relative) {
      if (part.toString().contains("SNAPSHOT")) {
        return true;
      }
    }
    return false;
  }

  private static void copyFileAtomic(Path source, Path target) throws IOException {
    if (Files.isSymbolicLink(source)) {
      return;
    }
    if (target.getParent() != null) {
      Files.createDirectories(target.getParent());
    }
    Path tmp = target.resolveSibling(target.getFileName() + ".cache-tmp");
    Files.copy(source, tmp, StandardCopyOption.REPLACE_EXISTING);
    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
  }

  static List<String> splitArgs(String raw) {
    List<String> tokens = new ArrayList<>();
    if (raw == null || raw.isBlank()) {
      return tokens;
    }
    StringBuilder current = new StringBuilder();
    char quote = 0;
    for (int i = 0; i < raw.length(); i++) {
      char c = raw.charAt(i);
      if (quote != 0) {
        if (c == quote) {
          quote = 0;
        } else {
          current.append(c);
        }
      } else if (c == '\'' || c == '"') {
        quote = c;
      } else if (Character.isWhitespace(c)) {
        if (!current.isEmpty()) {
          tokens.add(current.toString());
          current.setLength(0);
        }
      } else {
        current.append(c);
      }
    }
    if (!current.isEmpty()) {
      tokens.add(current.toString());
    }
    return tokens;
  }
}

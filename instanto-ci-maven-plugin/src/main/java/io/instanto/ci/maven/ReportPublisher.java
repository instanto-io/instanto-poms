/*
 * Copyright 2026 Carl Stainton
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.instanto.ci.maven;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;

/**
 * Packages and publishes CI diagnostic report bundles to Forgejo's generic package registry with
 * no-redirect credential protection and round-trip SHA-256 verification.
 */
public final class ReportPublisher {

  public static final String DEFAULT_REGISTRY =
      "https://packages.instanto.io/api/packages/instanto-io/generic";

  private static final Pattern VALID_COMPONENT = Pattern.compile("[A-Za-z0-9_.+-]+");

  private final String registryUrl;
  private final HttpClient httpClient;

  public ReportPublisher() {
    this(
        DEFAULT_REGISTRY,
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build());
  }

  public ReportPublisher(String registryUrl, HttpClient httpClient) {
    String clean =
        registryUrl == null || registryUrl.isBlank() ? DEFAULT_REGISTRY : registryUrl.trim();
    while (clean.endsWith("/")) {
      clean = clean.substring(0, clean.length() - 1);
    }
    this.registryUrl = clean;
    this.httpClient = httpClient;
  }

  public static String validateComponent(String value) {
    if (value == null || !VALID_COMPONENT.matcher(value).matches()) {
      throw new IllegalArgumentException("Invalid package name or run identifier");
    }
    return value;
  }

  public Map<String, Path> collectFiles(Path baseDir, List<String> patterns) throws IOException {
    Path resolvedBase = baseDir.toAbsolutePath().normalize();
    Map<String, Path> files = new LinkedHashMap<>();

    for (String pattern : patterns) {
      if (pattern == null || pattern.isBlank()) {
        continue;
      }
      List<Path> matchedRoots =
          new ArrayList<>(resolvePatternMatches(resolvedBase, pattern.trim()));
      matchedRoots.sort(Comparator.naturalOrder());

      for (Path match : matchedRoots) {
        checkNoSymlinkChain(match, resolvedBase);
        List<Path> candidates;
        if (Files.isDirectory(match)) {
          try (Stream<Path> walk = Files.walk(match)) {
            candidates = walk.sorted().toList();
          }
        } else {
          candidates = List.of(match);
        }
        for (Path candidate : candidates) {
          checkNoSymlinkChain(candidate, resolvedBase);
          if (Files.isRegularFile(candidate)) {
            String archiveName =
                Files.isDirectory(match)
                    ? SarifMerger.toPosix(match.relativize(candidate))
                    : candidate.getFileName().toString();
            Path existing = files.get(archiveName);
            if (existing != null && !existing.equals(candidate)) {
              throw new IllegalArgumentException("Duplicate archive path: " + archiveName);
            }
            files.put(archiveName, candidate);
          }
        }
      }
    }
    return files;
  }

  public void createTarGz(Map<String, Path> files, Path archivePath) throws IOException {
    if (archivePath.getParent() != null) {
      Files.createDirectories(archivePath.getParent());
    }
    List<String> sortedNames = new ArrayList<>(files.keySet());
    sortedNames.sort(Comparator.naturalOrder());

    try (OutputStream fos = Files.newOutputStream(archivePath);
        BufferedOutputStream bos = new BufferedOutputStream(fos);
        GzipCompressorOutputStream gzos = new GzipCompressorOutputStream(bos);
        TarArchiveOutputStream tar = new TarArchiveOutputStream(gzos)) {
      tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
      for (String name : sortedNames) {
        Path source = files.get(name);
        TarArchiveEntry entry = new TarArchiveEntry(source.toFile(), name);
        tar.putArchiveEntry(entry);
        Files.copy(source, tar);
        tar.closeArchiveEntry();
      }
      tar.finish();
    }
  }

  public String publish(Path archive, String url, String username, String token)
      throws IOException, InterruptedException {
    String credentials =
        Base64.getEncoder()
            .encodeToString((username + ":" + token).getBytes(StandardCharsets.UTF_8));
    String authHeader = "Basic " + credentials;

    HttpRequest putReq =
        HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(120))
            .header("Authorization", authHeader)
            .header("Content-Type", "application/gzip")
            .PUT(HttpRequest.BodyPublishers.ofFile(archive))
            .build();

    HttpResponse<Void> putResp = httpClient.send(putReq, HttpResponse.BodyHandlers.discarding());
    if (putResp.statusCode() != 201) {
      if (putResp.statusCode() >= 400) {
        throw new IOException(
            "Report registry request failed (HTTP "
                + putResp.statusCode()
                + "); check package-write access and registry availability.");
      }
      throw new IOException("Unexpected upload status: " + putResp.statusCode());
    }

    String expected = QualityReviewCollector.sha256Hex(archive);

    HttpRequest getReq =
        HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(120))
            .header("Authorization", authHeader)
            .GET()
            .build();
    HttpResponse<InputStream> getResp =
        httpClient.send(getReq, HttpResponse.BodyHandlers.ofInputStream());
    if (getResp.statusCode() != 200) {
      throw new IOException(
          "Report registry verification request failed (HTTP " + getResp.statusCode() + ")");
    }
    MessageDigest md;
    try {
      md = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
    try (InputStream in = getResp.body()) {
      byte[] buf = new byte[64 * 1024];
      int n;
      while ((n = in.read(buf)) >= 0) {
        md.update(buf, 0, n);
      }
    }
    String actual = HexFormat.of().formatHex(md.digest());
    if (!expected.equals(actual)) {
      throw new IOException("Published report checksum does not match the local archive");
    }
    return expected;
  }

  public record PublishedReport(String url, String sha256, int fileCount) {}

  public PublishedReport publishReport(
      Path baseDir,
      String name,
      List<String> patterns,
      String username,
      String token,
      String githubRepo,
      String runId,
      String runAttempt)
      throws IOException, InterruptedException {
    Map<String, Path> files = collectFiles(baseDir, patterns);
    if (files.isEmpty()) {
      return null;
    }
    if (username == null || username.isBlank() || token == null || token.isBlank()) {
      throw new IllegalArgumentException(
          "Report publication requires FORGEJO_PACKAGE_USER and a package-write token");
    }
    String cleanRepo = validateComponent(githubRepo.replace('/', '-'));
    String cleanName = validateComponent(name);
    String pkg = "ci-" + cleanRepo + "-" + cleanName;
    String version =
        validateComponent(
            runId + "-" + runAttempt + "-" + UUID.randomUUID().toString().replace("-", ""));
    String url = registryUrl + "/" + pkg + "/" + version + "/" + cleanName + ".tar.gz";

    Path tmpDir = Files.createTempDirectory("ci-report-");
    try {
      Path archive = tmpDir.resolve(cleanName + ".tar.gz");
      createTarGz(files, archive);
      String digest = publish(archive, url, username, token);
      return new PublishedReport(url, digest, files.size());
    } finally {
      SarifMerger.deleteRecursively(tmpDir);
    }
  }

  private static void checkNoSymlinkChain(Path candidate, Path stopAt) {
    Path current = candidate;
    while (current != null) {
      if (Files.isSymbolicLink(current)) {
        throw new IllegalArgumentException("Report paths must not contain symbolic links");
      }
      if (stopAt != null && current.equals(stopAt)) {
        break;
      }
      current = current.getParent();
    }
  }

  private static List<Path> resolvePatternMatches(Path baseDir, String rawPattern)
      throws IOException {
    boolean hasGlob =
        rawPattern.contains("*") || rawPattern.contains("?") || rawPattern.contains("[");
    Path direct = Path.of(rawPattern);
    if (!hasGlob) {
      Path resolved = direct.isAbsolute() ? direct : baseDir.resolve(direct);
      if (Files.exists(resolved) || Files.isSymbolicLink(resolved)) {
        return List.of(resolved);
      }
      return List.of();
    }

    Path searchRoot = baseDir;
    String globExpr = rawPattern.replace('\\', '/');
    if (direct.isAbsolute()) {
      Path cur = direct;
      while (cur != null
          && (cur.toString().contains("*")
              || cur.toString().contains("?")
              || cur.toString().contains("["))) {
        cur = cur.getParent();
      }
      if (cur != null) {
        searchRoot = cur;
        globExpr = SarifMerger.toPosix(searchRoot.relativize(direct));
      }
    }
    if (!Files.exists(searchRoot)) {
      return List.of();
    }
    PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + globExpr);
    Path rootFinal = searchRoot;
    List<Path> matches = new ArrayList<>();
    try (Stream<Path> walk = Files.walk(rootFinal)) {
      walk.forEach(
          p -> {
            if (!p.equals(rootFinal)) {
              Path rel = rootFinal.relativize(p);
              if (matcher.matches(rel)) {
                matches.add(p);
              }
            }
          });
    }
    return matches;
  }
}

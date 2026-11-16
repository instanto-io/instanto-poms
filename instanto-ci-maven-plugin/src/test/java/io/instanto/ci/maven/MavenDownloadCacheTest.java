package io.instanto.ci.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MavenDownloadCacheTest {

  @Test
  void normalizesRemoteMarkersAndFiltersSnapshotsAndInstantoArtifacts(@TempDir Path tempDir)
      throws Exception {
    Path cache = tempDir.resolve("cache/central");
    Path repo = tempDir.resolve("m2/repository");
    Files.createDirectories(cache);
    Files.createDirectories(repo);

    String rawMarker =
        """
        #NOTE: This is a Maven Resolver internal implementation file
        commons-io-2.16.1.jar>instanto-central=
        commons-io-2.16.1.pom>central=
        internal-1.0.jar>forgejo-instanto=
        """;
    String normalized = MavenDownloadCache.normalizeRemoteMarker(rawMarker);
    assertTrue(normalized.contains("commons-io-2.16.1.jar>central="));
    assertTrue(normalized.contains("commons-io-2.16.1.pom>central="));
    assertTrue(normalized.contains("internal-1.0.jar>forgejo-instanto="));

    // Populate repo with:
    // 1. Public central artifact
    Path publicDir = repo.resolve("commons-io/commons-io/2.16.1");
    Files.createDirectories(publicDir);
    Files.writeString(publicDir.resolve("commons-io-2.16.1.jar"), "jar-bytes");
    Files.writeString(publicDir.resolve("commons-io-2.16.1.jar.sha1"), "deadbeef");
    Files.writeString(
        publicDir.resolve("_remote.repositories"), "commons-io-2.16.1.jar>instanto-central=\n");

    // 2. Instanto artifact (must be excluded)
    Path instantoDir = repo.resolve("io/instanto/sarto-core/0.1.0");
    Files.createDirectories(instantoDir);
    Files.writeString(instantoDir.resolve("sarto-core-0.1.0.jar"), "internal");
    Files.writeString(
        instantoDir.resolve("_remote.repositories"), "sarto-core-0.1.0.jar>central=\n");

    // 3. Locally installed artifact with blank origin (must be excluded)
    Path localInstallDir = repo.resolve("org/example/local/1.0.0");
    Files.createDirectories(localInstallDir);
    Files.writeString(localInstallDir.resolve("local-1.0.0.jar"), "local");
    Files.writeString(
        localInstallDir.resolve("_remote.repositories"),
        "local-1.0.0.jar>central=\nlocal-1.0.0.pom>=\n");

    MavenDownloadCache downloadCache = new MavenDownloadCache();
    int saved = downloadCache.save(repo, cache);
    assertEquals(1, saved);
    assertTrue(
        Files.isRegularFile(cache.resolve("commons-io/commons-io/2.16.1/commons-io-2.16.1.jar")));
    assertTrue(
        Files.isRegularFile(
            cache.resolve("commons-io/commons-io/2.16.1/commons-io-2.16.1.jar.sha1")));
    assertFalse(Files.exists(cache.resolve("io/instanto")));
    assertFalse(Files.exists(cache.resolve("org/example/local")));

    // Restore into a fresh repository
    Path freshRepo = tempDir.resolve("fresh-m2");
    int restored = downloadCache.restore(cache, freshRepo);
    assertEquals(3, restored); // jar + sha1 + _remote.repositories
    assertEquals(
        "commons-io-2.16.1.jar>central=\n",
        Files.readString(freshRepo.resolve("commons-io/commons-io/2.16.1/_remote.repositories")));
  }

  @Test
  void parsesLocalRepositoryAndValidatesMirrorUrl(@TempDir Path tempDir) throws Exception {
    Path expected = tempDir.resolve("custom-m2").toAbsolutePath().normalize();
    assertEquals(
        expected,
        MavenDownloadCache.resolveLocalRepository("-B -Dmaven.repo.local=" + expected + " verify"));

    MavenDownloadCache downloadCache = new MavenDownloadCache();
    assertEquals(
        "https://mirror.example.com/maven2",
        downloadCache.resolveCentralMirror("https://mirror.example.com/maven2/"));
    assertThrows(
        IllegalArgumentException.class,
        () -> downloadCache.resolveCentralMirror("http://insecure.example.com/maven2"));
    assertThrows(
        IllegalArgumentException.class,
        () -> downloadCache.resolveCentralMirror("https://user:pass@mirror.example.com/maven2"));

    MavenCacheMojo mojo = new MavenCacheMojo();
    SarifMergerTest.setField(mojo, "cacheMode", "save");
    SarifMergerTest.setField(mojo, "mavenArgs", "-Dmaven.repo.local=" + expected);
    mojo.execute();

    Path cacheScript =
        Path.of(
            "/Users/carls/IdeaProjects/io.instanto/instanto-poms/.github/actions/maven-cache/cache.sh");
    if (Files.isRegularFile(cacheScript)) {
      Path scriptCacheRoot = tempDir.resolve("script-cache");
      Path scriptRepo = tempDir.resolve("script-m2");
      Path pubDir = scriptRepo.resolve("commons-io/commons-io/2.16.1");
      Files.createDirectories(pubDir);
      Files.writeString(pubDir.resolve("commons-io-2.16.1.jar"), "jar-bytes");
      Files.writeString(
          pubDir.resolve("_remote.repositories"), "commons-io-2.16.1.jar>instanto-central=\n");

      ProcessBuilder savePb =
          new ProcessBuilder(
              "bash",
              cacheScript.toString(),
              "save",
              "--maven-args=-Dmaven.repo.local=" + scriptRepo);
      savePb.environment().put("MAVEN_DOWNLOAD_CACHE", scriptCacheRoot.toString());
      assertEquals(0, savePb.start().waitFor());
      assertTrue(
          Files.isRegularFile(
              scriptCacheRoot.resolve(
                  "central/commons-io/commons-io/2.16.1/commons-io-2.16.1.jar")));

      Path restoredRepo = tempDir.resolve("script-restored-m2");
      Path ghEnv = tempDir.resolve("github.env");
      Files.writeString(ghEnv, "");
      ProcessBuilder prepPb =
          new ProcessBuilder(
              "bash",
              cacheScript.toString(),
              "prepare",
              "--maven-args=-Dmaven.repo.local=" + restoredRepo);
      prepPb.environment().put("MAVEN_DOWNLOAD_CACHE", scriptCacheRoot.toString());
      prepPb.environment().put("MAVEN_CENTRAL_MIRROR_URL", "https://repo.maven.apache.org/maven2");
      prepPb.environment().put("GITHUB_ENV", ghEnv.toString());
      assertEquals(0, prepPb.start().waitFor());
      assertTrue(
          Files.isRegularFile(
              restoredRepo.resolve("commons-io/commons-io/2.16.1/commons-io-2.16.1.jar")));
    }
  }
}

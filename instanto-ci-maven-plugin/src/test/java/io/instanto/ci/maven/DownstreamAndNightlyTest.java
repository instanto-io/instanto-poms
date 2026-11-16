package io.instanto.ci.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.instanto.ci.maven.DownstreamOrchestrator.ProjectConfig;
import java.io.BufferedOutputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DownstreamAndNightlyTest {

  private final ObjectMapper mapper = new ObjectMapper();

  private static ProjectConfig project(String repo, List<String> deps) {
    return new ProjectConfig(
        repo,
        "main",
        deps,
        List.of(),
        List.of(),
        List.of(),
        List.of("clean", "verify"),
        List.of(),
        false,
        List.of("self-hosted", "instanto-container"),
        null,
        Map.of(),
        List.of());
  }

  @Test
  void loadsRealCatalogComputesAffectedClosureAndTarjanGroups() throws Exception {
    DownstreamOrchestrator orchestrator = new DownstreamOrchestrator();
    Path catalogPath =
        Path.of(
            "/Users/carls/IdeaProjects/io.instanto/instanto-poms/.github/downstream-projects.json");
    if (Files.isRegularFile(catalogPath)) {
      Map<String, ProjectConfig> realProjects = orchestrator.loadCatalog(catalogPath);
      assertTrue(realProjects.containsKey("instanto-poms"));
      assertTrue(realProjects.containsKey("sarto"));
      Set<String> affected = DownstreamOrchestrator.affectedProjects(realProjects, "instanto-poms");
      assertTrue(affected.contains("sarto"));
      List<List<String>> groups = DownstreamOrchestrator.buildGroups(realProjects, affected);
      assertFalse(groups.isEmpty());
    }

    Map<String, ProjectConfig> sample = new LinkedHashMap<>();
    sample.put("root", project("instanto-io/root", List.of()));
    sample.put("cycle-a", project("instanto-io/cycle-a", List.of("root", "cycle-b")));
    sample.put("cycle-b", project("instanto-io/cycle-b", List.of("cycle-a")));
    sample.put("leaf", project("instanto-io/leaf", List.of("cycle-b")));

    Set<String> closure = DownstreamOrchestrator.affectedProjects(sample, "root");
    assertEquals(Set.of("root", "cycle-a", "cycle-b", "leaf"), closure);

    Set<String> consumersOnly = Set.of("cycle-a", "cycle-b", "leaf");
    List<List<String>> groups = DownstreamOrchestrator.buildGroups(sample, consumersOnly);
    assertEquals(List.of(List.of("cycle-a", "cycle-b"), List.of("leaf")), groups);

    List<String> log = new ArrayList<>();
    Map<String, String> res =
        DownstreamOrchestrator.executeGroups(
            sample,
            groups,
            "root",
            (name, phase) -> {
              log.add(name + ":" + phase);
              return true;
            },
            true);
    assertEquals("passed", res.get("cycle-a"));
    assertEquals("passed", res.get("cycle-b"));
    assertEquals("passed", res.get("leaf"));
    assertEquals(
        List.of(
            "cycle-a:install-cycle",
            "cycle-b:install-cycle",
            "cycle-a:verify-cycle",
            "cycle-b:verify-cycle",
            "leaf:verify"),
        log);

    // If cycle-a fails during install-cycle, cycle-b is blocked and leaf is blocked
    Map<String, String> failRes =
        DownstreamOrchestrator.executeGroups(
            sample, groups, "root", (name, phase) -> !"cycle-a".equals(name), true);
    assertEquals("failed", failRes.get("cycle-a"));
    assertEquals("blocked", failRes.get("cycle-b"));
    assertEquals("blocked", failRes.get("leaf"));
  }

  @Test
  void plansNightlyRunsIsolatesBlameAndUpdatesBaseline(@TempDir Path tempDir) throws Exception {
    Map<String, ProjectConfig> projects = new LinkedHashMap<>();
    projects.put("lib-a", project("instanto-io/lib-a", List.of()));
    projects.put("lib-b", project("instanto-io/lib-b", List.of("lib-a")));
    projects.put("app-c", project("instanto-io/app-c", List.of("lib-b")));

    NightlyCoordinator coordinator = new NightlyCoordinator();
    ObjectNode emptyBaseline = coordinator.loadBaseline(tempDir.resolve("missing.json"));

    Map<String, String> shas1 =
        Map.of(
            "lib-a", "111111111111",
            "lib-b", "222222222222",
            "app-c", "333333333333");

    // 1. Bootstrap mode
    ObjectNode bootPlan =
        coordinator.planNightly(projects, shas1, emptyBaseline, null, false, true, null);
    assertEquals("bootstrap", bootPlan.path("mode").asText());
    assertFalse(bootPlan.path("should_run").asBoolean());

    ObjectNode greenBaseline =
        coordinator.updateBaseline(
            projects, emptyBaseline, bootPlan, null, null, "2026-11-15T00:00:00Z");
    assertEquals(
        "111111111111",
        greenBaseline.path("projects").path("lib-a").path("last_green_sha").asText());

    // 2. Skip when nothing changed
    ObjectNode skipPlan =
        coordinator.planNightly(projects, shas1, greenBaseline, null, false, false, null);
    assertEquals("skip", skipPlan.path("mode").asText());
    assertFalse(skipPlan.path("should_run").asBoolean());

    // 3. Incremental when lib-a advances
    Map<String, String> shas2 =
        Map.of(
            "lib-a", "aaaaaaaaaaaa",
            "lib-b", "222222222222",
            "app-c", "333333333333");
    ObjectNode incPlan =
        coordinator.planNightly(
            projects,
            shas2,
            greenBaseline,
            null,
            false,
            false,
            (repo, base, head) -> {
              ObjectNode r = mapper.createObjectNode();
              r.put("ahead_by", 1);
              r.set("commits", mapper.createArrayNode());
              return r;
            });
    assertEquals("run", incPlan.path("mode").asText());
    assertTrue(incPlan.path("should_run").asBoolean());
    assertEquals(3, incPlan.path("affected").size());

    // 4. Blame isolation: single suspect (lib-a changed, app-c failed)
    Map<String, String> results =
        Map.of(
            "lib-a", "passed",
            "lib-b", "passed",
            "app-c", "failed");
    ObjectNode singleBlame =
        coordinator.isolateBlame(
            projects,
            results,
            shas2,
            greenBaseline.path("projects"),
            (failed, suspect, all) -> true,
            4,
            6);
    assertEquals("single-change", singleBlame.path("app-c").path("attribution").asText());
    assertEquals("lib-a", singleBlame.path("app-c").path("culprits").get(0).asText());

    // 5. Blame isolation: probe-isolated when both lib-a and lib-b changed
    Map<String, String> shasBoth =
        Map.of(
            "lib-a", "aaaaaaaaaaaa",
            "lib-b", "bbbbbbbbbbbb",
            "app-c", "333333333333");
    ObjectNode probedBlame =
        coordinator.isolateBlame(
            projects,
            results,
            shasBoth,
            greenBaseline.path("projects"),
            // Probe returns false (fails) when suspect == lib-b
            (failed, suspect, all) -> !"lib-b".equals(suspect),
            4,
            6);
    assertEquals("isolated", probedBlame.path("app-c").path("attribution").asText());
    assertEquals("lib-b", probedBlame.path("app-c").path("culprits").get(0).asText());

    // 6. Baseline update keeps lib-a's last_green_sha pinned while downstream app-c is broken,
    // advances last_security_sha when stage3 passes, and advances last_green_sha once all pass
    ObjectNode updated =
        coordinator.updateBaseline(
            projects,
            greenBaseline,
            incPlan,
            results,
            Map.of("lib-a", "passed", "lib-b", "passed", "app-c", "failed"),
            "2026-11-15T01:00:00Z");
    assertEquals(
        "111111111111", updated.path("projects").path("lib-a").path("last_green_sha").asText());
    assertEquals(
        "aaaaaaaaaaaa", updated.path("projects").path("lib-a").path("last_security_sha").asText());
    assertEquals("failed", updated.path("projects").path("app-c").path("last_result").asText());

    Map<String, String> allPassed =
        Map.of(
            "lib-a", "passed",
            "lib-b", "passed",
            "app-c", "passed");
    ObjectNode updatedAllGreen =
        coordinator.updateBaseline(
            projects, greenBaseline, incPlan, allPassed, allPassed, "2026-11-15T02:00:00Z");
    assertEquals(
        "aaaaaaaaaaaa",
        updatedAllGreen.path("projects").path("lib-a").path("last_green_sha").asText());

    assertTrue(coordinator.renderPlanSummary(incPlan).contains("## Nightly Change Detection Plan"));
    assertTrue(
        coordinator
            .renderCompatSummary(incPlan, results, probedBlame)
            .contains("## Nightly Downstream Compatibility"));
  }

  @Test
  void exportsAndAppliesM2OverlayAndRejectsUnsafeEntries(@TempDir Path tempDir) throws Exception {
    Path m2 = tempDir.resolve("m2");
    Path snapDir = m2.resolve("io/instanto/sarto-core/0.1.0-SNAPSHOT");
    Files.createDirectories(snapDir);
    Files.writeString(snapDir.resolve("sarto-core-0.1.0-SNAPSHOT.jar"), "jar-data");
    Files.writeString(snapDir.resolve("_remote.repositories"), "sarto-core>forgejo=\n");

    Path archive = tempDir.resolve("overlay.tar.gz");
    int exported =
        NightlyCoordinator.exportM2Overlay(
            m2, archive, NightlyCoordinator.DEFAULT_OVERLAY_PREFIXES);
    assertEquals(1, exported);
    assertFalse(Files.exists(snapDir.resolve("_remote.repositories")));

    Path targetM2 = tempDir.resolve("target-m2");
    int applied = NightlyCoordinator.applyM2Overlay(archive, targetM2);
    assertEquals(1, applied);
    assertTrue(
        Files.isRegularFile(
            targetM2.resolve(
                "io/instanto/sarto-core/0.1.0-SNAPSHOT/sarto-core-0.1.0-SNAPSHOT.jar")));

    // Unsafe path traversal archive must be rejected
    Path unsafeArchive = tempDir.resolve("unsafe.tar.gz");
    try (OutputStream fos = Files.newOutputStream(unsafeArchive);
        BufferedOutputStream bos = new BufferedOutputStream(fos);
        GzipCompressorOutputStream gzos = new GzipCompressorOutputStream(bos);
        TarArchiveOutputStream tar = new TarArchiveOutputStream(gzos)) {
      TarArchiveEntry entry = new TarArchiveEntry("../escape.txt");
      byte[] data = "bad".getBytes();
      entry.setSize(data.length);
      tar.putArchiveEntry(entry);
      tar.write(data);
      tar.closeArchiveEntry();
      tar.finish();
    }
    assertThrows(
        IllegalArgumentException.class,
        () -> NightlyCoordinator.applyM2Overlay(unsafeArchive, tempDir.resolve("dest-m2")));
  }
}

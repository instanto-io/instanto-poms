/*
 * Copyright 2026 Carl Stainton
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.instanto.ci.maven;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.instanto.ci.maven.DownstreamOrchestrator.PostVerifyStep;
import io.instanto.ci.maven.DownstreamOrchestrator.ProjectConfig;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.BiPredicate;
import java.util.stream.Stream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;

/**
 * Coordinates the incremental, blame-traceable nightly pipeline (plan, compatibility and blame
 * isolation, full verification, SonarQube analysis, m2 overlay transfer, and baseline updates).
 */
public final class NightlyCoordinator {

  private static final DateTimeFormatter UTC_ISO =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

  public static final List<String> DEFAULT_OVERLAY_PREFIXES = List.of("io/instanto", "eurocontrol");

  private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
  private final DownstreamOrchestrator downstream;

  @FunctionalInterface
  public interface CommitRangeFetcher {
    ObjectNode fetch(String repo, String baseSha, String headSha);
  }

  @FunctionalInterface
  public interface BlameProbe {
    boolean probe(String failedName, String suspect, List<String> orderedSuspects);
  }

  public NightlyCoordinator() {
    this.downstream = new DownstreamOrchestrator();
  }

  NightlyCoordinator(DownstreamOrchestrator downstream) {
    this.downstream = Objects.requireNonNull(downstream);
  }

  DownstreamOrchestrator downstream() {
    return downstream;
  }

  public static String utcNow() {
    return UTC_ISO.format(Instant.now());
  }

  public ObjectNode loadBaseline(Path path) throws IOException {
    ObjectNode empty = mapper.createObjectNode();
    empty.put("schema_version", 1);
    empty.putNull("updated_at");
    empty.set("projects", mapper.createObjectNode());
    if (path == null || !Files.isRegularFile(path)) {
      return empty;
    }
    String text = Files.readString(path).trim();
    if (text.isEmpty()) {
      return empty;
    }
    JsonNode parsed = mapper.readTree(text);
    ObjectNode out = mapper.createObjectNode();
    out.put("schema_version", 1);
    out.set("updated_at", parsed.get("updated_at"));
    JsonNode projs = parsed.get("projects");
    out.set(
        "projects",
        projs != null && projs.isObject() ? projs.deepCopy() : mapper.createObjectNode());
    return out;
  }

  public Map<String, String> fetchHeadShas(Map<String, ProjectConfig> projects)
      throws IOException, InterruptedException {
    Map<String, String> shas = new TreeMap<>();
    for (String name : new TreeSet<>(projects.keySet())) {
      String repo = projects.get(name).repository();
      JsonNode info = downstream.api("repos/" + repo);
      String branch = SarifMerger.textOrDefault(info, "default_branch", "main");
      String sha =
          SarifMerger.textOrDefault(
              downstream.api("repos/" + repo + "/commits/" + branch), "sha", "");
      shas.put(name, sha);
    }
    return shas;
  }

  public ObjectNode fetchCommitRange(String repo, String baseSha, String headSha, int limit) {
    ObjectNode res = mapper.createObjectNode();
    if (baseSha != null) {
      res.put("base_sha", baseSha);
    } else {
      res.putNull("base_sha");
    }
    if (headSha != null) {
      res.put("head_sha", headSha);
    } else {
      res.putNull("head_sha");
    }
    if (baseSha == null
        || baseSha.isEmpty()
        || headSha == null
        || headSha.isEmpty()
        || baseSha.equals(headSha)) {
      res.put("ahead_by", 0);
      res.set("commits", mapper.createArrayNode());
      return res;
    }
    try {
      JsonNode data = downstream.api("repos/" + repo + "/compare/" + baseSha + "..." + headSha);
      JsonNode rawCommits = data.get("commits");
      List<ObjectNode> commits = new ArrayList<>();
      if (rawCommits != null && rawCommits.isArray()) {
        int total = rawCommits.size();
        int start = Math.max(0, total - limit);
        for (int i = start; i < total; i++) {
          JsonNode item = rawCommits.get(i);
          JsonNode commit = item.path("commit");
          String author = SarifMerger.textOrNull(commit.path("author"), "name");
          if (author == null || author.isEmpty()) {
            author = SarifMerger.textOrDefault(item.path("author"), "login", "unknown");
          }
          String rawMsg = SarifMerger.textOrDefault(commit, "message", "");
          String firstLine = rawMsg.lines().findFirst().orElse("");
          if (firstLine.length() > 120) {
            firstLine = firstLine.substring(0, 120);
          }
          String fullSha = SarifMerger.textOrDefault(item, "sha", "");
          ObjectNode c = mapper.createObjectNode();
          c.put("sha", fullSha.length() > 12 ? fullSha.substring(0, 12) : fullSha);
          c.put("full_sha", fullSha);
          c.put("author", author);
          c.put("message", firstLine);
          c.put("date", SarifMerger.textOrDefault(commit.path("author"), "date", ""));
          commits.add(c);
        }
      }
      Collections.reverse(commits);
      res.put(
          "ahead_by",
          data.has("total_commits")
              ? data.path("total_commits").asInt(commits.size())
              : commits.size());
      ArrayNode arr = mapper.createArrayNode();
      commits.forEach(arr::add);
      res.set("commits", arr);
      return res;
    } catch (IOException | InterruptedException | RuntimeException e) {
      res.putNull("ahead_by");
      res.set("commits", mapper.createArrayNode());
      res.put("compare_unavailable", true);
      return res;
    }
  }

  public static Set<String> ancestorsOf(Map<String, ProjectConfig> projects, String node) {
    Set<String> visited = new TreeSet<>();
    ProjectConfig rootCfg = projects.get(node);
    if (rootCfg == null) {
      return visited;
    }
    Deque<String> frontier = new ArrayDeque<>(rootCfg.dependencies());
    while (!frontier.isEmpty()) {
      String cur = frontier.pop();
      if (visited.contains(cur) || !projects.containsKey(cur)) {
        continue;
      }
      visited.add(cur);
      frontier.addAll(projects.get(cur).dependencies());
    }
    return visited;
  }

  public static Set<String> transitiveAffected(
      Map<String, ProjectConfig> projects, Collection<String> roots) {
    Set<String> affected = new TreeSet<>();
    for (String root : roots) {
      affected.addAll(DownstreamOrchestrator.affectedProjects(projects, root));
    }
    return affected;
  }

  public ObjectNode planNightly(
      Map<String, ProjectConfig> projects,
      Map<String, String> headShas,
      JsonNode baseline,
      Collection<String> forcedUpstreams,
      boolean forceAll,
      boolean bootstrapOnly,
      CommitRangeFetcher commitFetcher) {
    JsonNode baseProjects = baseline.path("projects");
    List<String> dirtySinceSeen = new ArrayList<>();
    Set<String> unhealthyVsGreen = new TreeSet<>();

    for (String name : new TreeSet<>(projects.keySet())) {
      JsonNode entry = baseProjects.get(name);
      String head = headShas.get(name);
      if (entry == null || !head.equals(SarifMerger.textOrNull(entry, "last_seen_sha"))) {
        dirtySinceSeen.add(name);
      }
      if (entry == null || !head.equals(SarifMerger.textOrNull(entry, "last_green_sha"))) {
        unhealthyVsGreen.add(name);
      }
    }

    if (bootstrapOnly) {
      return createPlanNode(
          "bootstrap",
          false,
          List.of(),
          new ArrayList<>(unhealthyVsGreen),
          List.of(),
          List.of(),
          mapper.createArrayNode(),
          headShas,
          mapper.createObjectNode());
    }

    Set<String> forced =
        forcedUpstreams != null ? new TreeSet<>(forcedUpstreams) : Collections.emptySet();
    Set<String> unknownForced = new TreeSet<>(forced);
    unknownForced.removeAll(projects.keySet());
    if (!unknownForced.isEmpty()) {
      throw new IllegalArgumentException(
          "Unknown upstream project(s): " + String.join(", ", unknownForced));
    }

    List<String> changed;
    if (!forced.isEmpty()) {
      changed = new ArrayList<>(forced);
    } else if (forceAll) {
      changed = new ArrayList<>(new TreeSet<>(projects.keySet()));
    } else {
      changed = dirtySinceSeen;
    }

    if (changed.isEmpty()) {
      return createPlanNode(
          "skip",
          false,
          List.of(),
          new ArrayList<>(unhealthyVsGreen),
          List.of(),
          List.of(),
          mapper.createArrayNode(),
          headShas,
          mapper.createObjectNode());
    }

    Set<String> affected = transitiveAffected(projects, changed);
    List<List<String>> groups = DownstreamOrchestrator.buildGroups(projects, affected);

    Set<String> affectedAndAncestors = new TreeSet<>(affected);
    for (String n : affected) {
      affectedAndAncestors.addAll(ancestorsOf(projects, n));
    }
    Set<String> traceCandidates = new TreeSet<>(changed);
    for (String u : unhealthyVsGreen) {
      if (affectedAndAncestors.contains(u)) {
        traceCandidates.add(u);
      }
    }

    CommitRangeFetcher fetcher =
        commitFetcher != null ? commitFetcher : (r, b, h) -> fetchCommitRange(r, b, h, 20);
    ObjectNode commitRanges = mapper.createObjectNode();
    for (String name : traceCandidates) {
      JsonNode entry = baseProjects.path(name);
      String baseSha = SarifMerger.textOrNull(entry, "last_green_sha");
      if (baseSha == null || baseSha.isEmpty()) {
        baseSha = SarifMerger.textOrNull(entry, "last_seen_sha");
      }
      String headSha = headShas.get(name);
      if (baseSha != null && !baseSha.isEmpty() && !baseSha.equals(headSha)) {
        commitRanges.set(name, fetcher.fetch(projects.get(name).repository(), baseSha, headSha));
      } else {
        ObjectNode emptyRange = mapper.createObjectNode();
        if (baseSha != null) {
          emptyRange.put("base_sha", baseSha);
          emptyRange.put("ahead_by", 0);
        } else {
          emptyRange.putNull("base_sha");
          emptyRange.putNull("ahead_by");
        }
        emptyRange.put("head_sha", headSha);
        emptyRange.set("commits", mapper.createArrayNode());
        commitRanges.set(name, emptyRange);
      }
    }

    ArrayNode matrixInclude = mapper.createArrayNode();
    for (String name : affected) {
      ProjectConfig cfg = projects.get(name);
      ObjectNode item = mapper.createObjectNode();
      item.put("name", name);
      item.put("repository", cfg.repository());
      item.put("sha", headShas.get(name));
      ArrayNode labels = mapper.createArrayNode();
      cfg.runnerLabels().forEach(labels::add);
      item.set("runner_labels", labels);
      item.put("install_playwright", cfg.installPlaywright());
      item.put("node_version", cfg.nodeVersion());
      ArrayNode sArgs = mapper.createArrayNode();
      cfg.sonarArgs().forEach(sArgs::add);
      item.set("sonar_args", sArgs);
      matrixInclude.add(item);
    }

    return createPlanNode(
        "run",
        true,
        changed,
        new ArrayList<>(unhealthyVsGreen),
        new ArrayList<>(affected),
        groups,
        matrixInclude,
        headShas,
        commitRanges);
  }

  public static Map<String, String> executeNightlyGroups(
      Map<String, ProjectConfig> projects,
      List<List<String>> groups,
      BiPredicate<String, String> buildFn) {
    Map<String, String> results = new TreeMap<>();
    for (List<String> group : groups) {
      boolean depFailed = false;
      for (String name : group) {
        ProjectConfig cfg = projects.get(name);
        if (cfg != null) {
          for (String dep : cfg.dependencies()) {
            if (!group.contains(dep) && results.containsKey(dep)) {
              if (!"passed".equals(results.get(dep))) {
                depFailed = true;
                break;
              }
            }
          }
        }
      }
      if (depFailed) {
        for (String name : group) {
          results.put(name, "blocked");
        }
        continue;
      }

      if (group.size() == 1) {
        String name = group.get(0);
        results.put(name, buildFn.test(name, "verify") ? "passed" : "failed");
        continue;
      }

      boolean ready = true;
      for (String name : group) {
        if (!buildFn.test(name, "install-cycle")) {
          results.put(name, "failed");
          ready = false;
        }
      }
      if (ready) {
        for (String name : group) {
          results.put(name, buildFn.test(name, "verify-cycle") ? "passed" : "failed");
        }
      } else {
        for (String name : group) {
          results.putIfAbsent(name, "blocked");
        }
      }
      if (group.stream().anyMatch(n -> !"passed".equals(results.get(n)))) {
        for (String name : group) {
          if ("passed".equals(results.get(name))) {
            results.put(name, "blocked");
          }
        }
      }
    }
    return results;
  }

  public ObjectNode isolateBlame(
      Map<String, ProjectConfig> projects,
      Map<String, String> results,
      Map<String, String> headShas,
      JsonNode baselineProjects,
      BlameProbe probeFn,
      int maxCandidates,
      int maxProbeClosure) {
    Set<String> dirtyWithGreen = new TreeSet<>();
    Set<String> changedWithoutGreen = new TreeSet<>();

    for (String name : projects.keySet()) {
      JsonNode baseEntry = baselineProjects != null ? baselineProjects.get(name) : null;
      String lastGreen = SarifMerger.textOrNull(baseEntry, "last_green_sha");
      String head = headShas.get(name);
      if (lastGreen != null && !lastGreen.isEmpty() && !lastGreen.equals(head)) {
        dirtyWithGreen.add(name);
      }
      if (results.containsKey(name)
          && (lastGreen == null || lastGreen.isEmpty() || !lastGreen.equals(head))) {
        changedWithoutGreen.add(name);
      }
    }

    ObjectNode blame = mapper.createObjectNode();
    for (Map.Entry<String, String> entry : new TreeMap<>(results).entrySet()) {
      String failedName = entry.getKey();
      if (!"failed".equals(entry.getValue())) {
        continue;
      }
      Set<String> lineage = new TreeSet<>(ancestorsOf(projects, failedName));
      lineage.add(failedName);

      List<String> suspects =
          lineage.stream().filter(changedWithoutGreen::contains).sorted().toList();
      List<String> isolatable = lineage.stream().filter(dirtyWithGreen::contains).sorted().toList();

      if (suspects.isEmpty()) {
        ObjectNode b = mapper.createObjectNode();
        b.put("attribution", "unchanged-chain");
        b.set("culprits", mapper.createArrayNode());
        b.put(
            "detail",
            "Neither the failing repo nor any upstream changed since last green baseline.");
        blame.set(failedName, b);
        continue;
      }
      if (suspects.size() == 1) {
        ObjectNode b = mapper.createObjectNode();
        b.put("attribution", "single-change");
        b.set("culprits", toStringArrayNode(suspects));
        b.put(
            "detail",
            "Only `"
                + suspects.get(0)
                + "` changed in this dependency chain since last green baseline.");
        blame.set(failedName, b);
        continue;
      }
      if (suspects.size() > maxCandidates || isolatable.size() != suspects.size()) {
        ObjectNode b = mapper.createObjectNode();
        b.put("attribution", "multiple-candidates");
        b.set("culprits", toStringArrayNode(suspects));
        b.put(
            "detail",
            suspects.size()
                + " repos changed without full green baselines or exceed isolation cap ("
                + maxCandidates
                + ").");
        blame.set(failedName, b);
        continue;
      }

      List<String> ordered = new ArrayList<>();
      for (String s : isolatable) {
        if (!s.equals(failedName)) {
          ordered.add(s);
        }
      }
      if (isolatable.contains(failedName)) {
        ordered.add(failedName);
      }

      long closureSize = lineage.stream().filter(results::containsKey).count();
      if (closureSize > maxProbeClosure) {
        Set<String> closure = new TreeSet<>(ancestorsOf(projects, failedName));
        closure.retainAll(suspects);
        closure.add(failedName);
        if (closure.size() > maxProbeClosure) {
          ObjectNode b = mapper.createObjectNode();
          b.put("attribution", "multiple-candidates");
          b.set("culprits", toStringArrayNode(ordered));
          b.put(
              "detail",
              "Chain has "
                  + closure.size()
                  + " changed repos (> cap "
                  + maxProbeClosure
                  + "); see commit ranges for each suspect.");
          blame.set(failedName, b);
          continue;
        }
      }

      List<String> culprits = new ArrayList<>();
      ObjectNode probeOutcomes = mapper.createObjectNode();
      for (String suspect : ordered) {
        boolean ok = probeFn.probe(failedName, suspect, ordered);
        probeOutcomes.put(suspect, ok ? "passed" : "failed");
        if (!ok) {
          culprits.add(suspect);
        }
      }

      ObjectNode b = mapper.createObjectNode();
      if (!culprits.isEmpty()) {
        b.put("attribution", "isolated");
        b.set("culprits", toStringArrayNode(culprits));
        b.set("probes", probeOutcomes);
        b.put("detail", "Pinning individual suspects against green baseline isolated failure.");
      } else {
        b.put("attribution", "interaction-or-transient");
        b.set("culprits", toStringArrayNode(ordered));
        b.set("probes", probeOutcomes);
        b.put(
            "detail",
            "No single suspect failed alone against green baseline; failure likely requires combined changes across suspects.");
      }
      blame.set(failedName, b);
    }
    return blame;
  }

  public static int exportM2Overlay(Path repositoryDir, Path archivePath, List<String> prefixes)
      throws IOException {
    Path repoRoot = repositoryDir.toAbsolutePath().normalize();
    Path archiveParent = archivePath.toAbsolutePath().normalize().getParent();
    if (archiveParent != null) {
      Files.createDirectories(archiveParent);
    }
    DownstreamOrchestrator.stripRemoteRepositories(repoRoot);
    int added = 0;
    try (OutputStream fos = Files.newOutputStream(archivePath);
        BufferedOutputStream bos = new BufferedOutputStream(fos);
        GzipCompressorOutputStream gzos = new GzipCompressorOutputStream(bos);
        TarArchiveOutputStream tar = new TarArchiveOutputStream(gzos)) {
      tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
      for (String prefix : prefixes) {
        Path target = repoRoot.resolve(prefix);
        if (!Files.exists(target)) {
          continue;
        }
        List<Path> items;
        try (Stream<Path> walk = Files.walk(target)) {
          items = walk.filter(Files::isRegularFile).sorted().toList();
        }
        for (Path item : items) {
          String arcname = SarifMerger.toPosix(repoRoot.relativize(item));
          TarArchiveEntry entry = new TarArchiveEntry(item.toFile(), arcname);
          tar.putArchiveEntry(entry);
          Files.copy(item, tar);
          tar.closeArchiveEntry();
          added++;
        }
      }
      tar.finish();
    }
    return added;
  }

  public static int applyM2Overlay(Path archivePath, Path repositoryDir) throws IOException {
    Path repoRoot = repositoryDir.toAbsolutePath().normalize();
    Files.createDirectories(repoRoot);
    if (archivePath == null || !Files.isRegularFile(archivePath)) {
      return 0;
    }
    int count = 0;
    try (InputStream fis = Files.newInputStream(archivePath);
        BufferedInputStream bis = new BufferedInputStream(fis);
        GzipCompressorInputStream gzis = new GzipCompressorInputStream(bis);
        TarArchiveInputStream tar = new TarArchiveInputStream(gzis)) {
      TarArchiveEntry entry;
      while ((entry = tar.getNextEntry()) != null) {
        if (entry.isSymbolicLink()
            || entry.isLink()
            || entry.isCharacterDevice()
            || entry.isBlockDevice()
            || entry.isFIFO()) {
          throw new IllegalArgumentException(
              "Unsupported non-regular entry in overlay archive: " + entry.getName());
        }
        Path dest = repoRoot.resolve(entry.getName()).normalize();
        if (!dest.equals(repoRoot) && !dest.startsWith(repoRoot)) {
          throw new IllegalArgumentException("Unsafe path in overlay archive: " + entry.getName());
        }
        if (entry.isDirectory()) {
          Files.createDirectories(dest);
        } else if (entry.isFile()) {
          Path destParent = dest.getParent();
          if (destParent != null) {
            Files.createDirectories(destParent);
          }
          try (OutputStream out = Files.newOutputStream(dest)) {
            tar.transferTo(out);
          }
          count++;
        } else {
          throw new IllegalArgumentException(
              "Unsupported non-regular entry in overlay archive: " + entry.getName());
        }
      }
    }
    DownstreamOrchestrator.stripRemoteRepositories(repoRoot);
    return count;
  }

  public ObjectNode updateBaseline(
      Map<String, ProjectConfig> projects,
      JsonNode baseline,
      JsonNode planData,
      Map<String, String> compatResults,
      Map<String, String> stage3Results,
      String timestamp) {
    String now = timestamp != null && !timestamp.isBlank() ? timestamp : utcNow();
    JsonNode oldProjects = baseline.path("projects");
    JsonNode headShas = planData.path("head_shas");
    Map<String, String> compat = compatResults != null ? compatResults : Map.of();
    Map<String, String> stage3 = stage3Results != null ? stage3Results : Map.of();
    String mode = SarifMerger.textOrDefault(planData, "mode", "");

    ObjectNode updated = mapper.createObjectNode();
    for (String name : new TreeSet<>(projects.keySet())) {
      JsonNode prev = oldProjects.path(name);
      String head = SarifMerger.textOrNull(headShas, name);
      if (head == null || head.isEmpty()) {
        head = SarifMerger.textOrNull(prev, "last_seen_sha");
      }
      String prevGreen = SarifMerger.textOrNull(prev, "last_green_sha");
      String prevSec = SarifMerger.textOrNull(prev, "last_security_sha");

      ObjectNode entry = mapper.createObjectNode();
      if ("bootstrap".equals(mode)) {
        putOrNull(entry, "last_seen_sha", head);
        putOrNull(entry, "last_green_sha", head);
        putOrNull(entry, "last_security_sha", prevSec);
        entry.put("last_result", "bootstrapped");
        entry.put("last_checked_at", now);
        updated.set(name, entry);
        continue;
      }

      if (!compat.containsKey(name)) {
        putOrNull(entry, "last_seen_sha", head);
        putOrNull(entry, "last_green_sha", prevGreen);
        putOrNull(entry, "last_security_sha", prevSec);
        entry.put("last_result", SarifMerger.textOrDefault(prev, "last_result", "unchanged"));
        putOrNull(entry, "last_checked_at", SarifMerger.textOrNull(prev, "last_checked_at"));
        updated.set(name, entry);
        continue;
      }

      Set<String> dependants =
          new TreeSet<>(DownstreamOrchestrator.affectedProjects(projects, name));
      dependants.retainAll(compat.keySet());
      boolean chainCompatOk = dependants.stream().allMatch(d -> "passed".equals(compat.get(d)));
      String secStatus = stage3.get(name);
      boolean secOk = "passed".equals(secStatus);
      boolean chainStage3Ok =
          !stage3.isEmpty()
              && dependants.stream()
                  .filter(d -> "passed".equals(compat.get(d)))
                  .allMatch(d -> "passed".equals(stage3.get(d)));

      String greenSha = (chainCompatOk && secOk && chainStage3Ok) ? head : prevGreen;
      String secSha = secOk ? head : prevSec;

      putOrNull(entry, "last_seen_sha", head);
      putOrNull(entry, "last_green_sha", greenSha);
      putOrNull(entry, "last_security_sha", secSha);
      entry.put(
          "last_result", secStatus != null ? secStatus : compat.getOrDefault(name, "unknown"));
      entry.put("last_checked_at", now);
      updated.set(name, entry);
    }

    ObjectNode out = mapper.createObjectNode();
    out.put("schema_version", 1);
    out.put("updated_at", now);
    out.set("projects", updated);
    return out;
  }

  public static String renderPlanSummary(JsonNode planData) {
    String mode = SarifMerger.textOrDefault(planData, "mode", "");
    List<String> lines = new ArrayList<>();
    lines.add("## Nightly Change Detection Plan");
    lines.add("");
    if ("bootstrap".equals(mode)) {
      lines.add(
          "Bootstrapped baseline for "
              + planData.path("head_shas").size()
              + " projects without running builds.");
      return String.join("\n", lines) + "\n";
    }
    if (!planData.path("should_run").asBoolean(false)) {
      lines.add(
          "No upstream library changes detected since the last checked nightly; downstream compatibility & security skipped.");
      JsonNode unhealthy = planData.get("unhealthy_vs_green");
      if (unhealthy != null && unhealthy.isArray() && !unhealthy.isEmpty()) {
        List<String> items = new ArrayList<>();
        unhealthy.forEach(u -> items.add("`" + u.asText() + "`"));
        lines.add("");
        lines.add(
            "Unresolved failures from earlier nightlies (unchanged today): "
                + String.join(", ", items));
      }
      return String.join("\n", lines) + "\n";
    }

    List<String> changed = new ArrayList<>();
    planData.path("changed").forEach(c -> changed.add("`" + c.asText() + "`"));
    List<String> affected = new ArrayList<>();
    planData.path("affected").forEach(a -> affected.add("`" + a.asText() + "`"));

    lines.add("- **Changed / triggered**: " + String.join(", ", changed));
    lines.add("- **Affected projects (" + affected.size() + ")**: " + String.join(", ", affected));

    JsonNode commitRanges = planData.get("commit_ranges");
    if (commitRanges != null && commitRanges.isObject() && !commitRanges.isEmpty()) {
      lines.add("");
      lines.add("### Commit Ranges Since Last Green Nightly");
      lines.add("");
      Set<String> sortedNames = new TreeSet<>();
      commitRanges.fieldNames().forEachRemaining(sortedNames::add);
      for (String name : sortedNames) {
        JsonNode info = commitRanges.get(name);
        String base = truncateSha(SarifMerger.textOrDefault(info, "base_sha", "none"));
        String head = truncateSha(SarifMerger.textOrDefault(info, "head_sha", ""));
        lines.add("- **`" + name + "`** (`" + base + "`..`" + head + "`)");
        JsonNode commits = info.get("commits");
        if (commits != null && commits.isArray()) {
          for (int i = 0; i < Math.min(5, commits.size()); i++) {
            JsonNode c = commits.get(i);
            lines.add(
                "  - `"
                    + SarifMerger.textOrDefault(c, "sha", "")
                    + "` "
                    + SarifMerger.textOrDefault(c, "message", "")
                    + " ("
                    + SarifMerger.textOrDefault(c, "author", "")
                    + ")");
          }
        }
      }
    }
    lines.add("");
    return String.join("\n", lines) + "\n";
  }

  public static String renderCompatSummary(
      JsonNode planData, Map<String, String> results, JsonNode blame) {
    List<String> lines = new ArrayList<>();
    lines.add("## Nightly Downstream Compatibility");
    lines.add("");
    lines.add("| Project | Revision | Result | Blame / Suspects |");
    lines.add("| --- | --- | --- | --- |");

    JsonNode revisions = planData.path("head_shas");
    JsonNode commitRanges = planData.path("commit_ranges");

    for (Map.Entry<String, String> entry : new TreeMap<>(results).entrySet()) {
      String name = entry.getKey();
      String sha = truncateSha(SarifMerger.textOrDefault(revisions, name, "unknown"));
      String status = entry.getValue();
      String note = "";
      JsonNode b = blame != null ? blame.get(name) : null;
      if (b != null && b.isObject()) {
        List<String> culprits = new ArrayList<>();
        b.path("culprits").forEach(c -> culprits.add("`" + c.asText() + "`"));
        String culpritStr = culprits.isEmpty() ? "none" : String.join(", ", culprits);
        note =
            SarifMerger.textOrDefault(b, "attribution", "")
                + ": "
                + culpritStr
                + " — "
                + SarifMerger.textOrDefault(b, "detail", "");
      }
      lines.add("| `" + name + "` | `" + sha + "` | " + status + " | " + note + " |");
    }

    if (blame != null && blame.isObject() && !blame.isEmpty()) {
      lines.add("");
      lines.add("### Breakage Attribution Details");
      lines.add("");
      Set<String> failedNames = new TreeSet<>();
      blame.fieldNames().forEachRemaining(failedNames::add);
      for (String failedName : failedNames) {
        JsonNode info = blame.get(failedName);
        lines.add(
            "#### `"
                + failedName
                + "` ("
                + SarifMerger.textOrDefault(info, "attribution", "")
                + ")");
        lines.add(SarifMerger.textOrDefault(info, "detail", ""));
        for (JsonNode cNode : info.path("culprits")) {
          String culprit = cNode.asText();
          JsonNode cr = commitRanges.get(culprit);
          if (cr != null && cr.isObject()) {
            String base = truncateSha(SarifMerger.textOrDefault(cr, "base_sha", "initial"));
            String head = truncateSha(SarifMerger.textOrDefault(cr, "head_sha", ""));
            lines.add("- Suspect `" + culprit + "` (`" + base + "`..`" + head + "`):");
            JsonNode commits = cr.get("commits");
            if (commits != null && commits.isArray()) {
              for (int i = 0; i < Math.min(10, commits.size()); i++) {
                JsonNode c = commits.get(i);
                lines.add(
                    "  - `"
                        + SarifMerger.textOrDefault(c, "sha", "")
                        + "` "
                        + SarifMerger.textOrDefault(c, "message", "")
                        + " ("
                        + SarifMerger.textOrDefault(c, "author", "")
                        + ")");
              }
            }
          }
        }
        lines.add("");
      }
    }

    lines.add(
        "Compatibility builds run against pinned commits inside an isolated Maven repository; no snapshot deployments occur during compatibility or blame isolation.");
    return String.join("\n", lines) + "\n";
  }

  public void runPlanCommand(
      Path catalogPath,
      Path baselinePath,
      Path outputDir,
      String upstreamCsv,
      boolean force,
      boolean bootstrapOnly)
      throws IOException, InterruptedException {
    Map<String, ProjectConfig> projects = downstream.loadCatalog(catalogPath);
    ObjectNode baseline = loadBaseline(baselinePath);
    Map<String, String> headShas = fetchHeadShas(projects);
    List<String> forced = new ArrayList<>();
    if (upstreamCsv != null && !upstreamCsv.isBlank()) {
      for (String s : upstreamCsv.split(",")) {
        if (!s.isBlank()) {
          forced.add(s.trim());
        }
      }
    }
    ObjectNode planData =
        planNightly(
            projects,
            headShas,
            baseline,
            forced.isEmpty() ? null : forced,
            force,
            bootstrapOnly,
            null);
    Files.createDirectories(outputDir);
    Files.writeString(outputDir.resolve("plan.json"), mapper.writeValueAsString(planData) + "\n");
    String summary = renderPlanSummary(planData);
    Files.writeString(outputDir.resolve("plan-summary.md"), summary);
    if (bootstrapOnly) {
      ObjectNode boot = updateBaseline(projects, baseline, planData, null, null, null);
      Files.writeString(
          outputDir.resolve("bootstrap-baseline.json"), mapper.writeValueAsString(boot) + "\n");
    }
    SonarQubeClient.appendStepSummary(summary);
    String ghOut = System.getenv("GITHUB_OUTPUT");
    if (ghOut != null && !ghOut.isBlank()) {
      boolean shouldRun = planData.path("should_run").asBoolean(false);
      String mode = SarifMerger.textOrDefault(planData, "mode", "skip");
      String matrixJson = new ObjectMapper().writeValueAsString(planData.path("matrix_include"));
      Files.writeString(
          Path.of(ghOut),
          "should_run="
              + (shouldRun ? "true" : "false")
              + "\n"
              + "mode="
              + mode
              + "\n"
              + "matrix_include="
              + matrixJson
              + "\n",
          StandardCharsets.UTF_8,
          StandardOpenOption.CREATE,
          StandardOpenOption.APPEND);
    }
    System.out.print(summary);
  }

  public int runCompatCommand(
      Path catalogPath,
      Path planPath,
      Path baselinePath,
      Path workspaceDir,
      Path customSettings,
      Path overlayOut,
      int maxBlameCandidates)
      throws IOException, InterruptedException {
    Map<String, ProjectConfig> projects = downstream.loadCatalog(catalogPath);
    JsonNode planData = mapper.readTree(Files.readString(planPath));
    ObjectNode baseline = loadBaseline(baselinePath);
    if (!planData.path("should_run").asBoolean(false)) {
      System.out.println("Plan indicates no compatibility build needed.");
      return 0;
    }

    Path workspace = workspaceDir.toAbsolutePath().normalize();
    Files.createDirectories(workspace);
    Path output = workspace.resolve("reports");
    Files.createDirectories(output);
    Path repository = workspace.resolve("m2");
    Files.createDirectories(repository);

    Set<String> affected = new TreeSet<>();
    planData.path("affected").forEach(a -> affected.add(a.asText()));
    Map<String, String> headShas = new TreeMap<>();
    planData
        .path("head_shas")
        .fields()
        .forEachRemaining(e -> headShas.put(e.getKey(), e.getValue().asText()));

    Map<String, Path> sources = new LinkedHashMap<>();
    Set<String> checkoutFailed = new LinkedHashSet<>();
    for (String name : affected) {
      Path dest = workspace.resolve("src").resolve(name);
      Path destParent = dest.getParent();
      if (destParent != null) {
        Files.createDirectories(destParent);
      }
      sources.put(name, dest);
      try {
        downstream.checkout(projects.get(name).repository(), dest, headShas.get(name));
      } catch (IOException | InterruptedException | RuntimeException e) {
        System.out.println(
            "::error::Cannot check out "
                + name
                + "@"
                + headShas.get(name)
                + ": "
                + e.getClass().getSimpleName());
        checkoutFailed.add(name);
      }
    }

    Path settings =
        customSettings != null
            ? customSettings.toAbsolutePath().normalize()
            : workspace.resolve("settings.xml");
    if (customSettings == null) {
      DownstreamOrchestrator.writeSettings(settings, sources.values());
    }
    Path centralSettings = workspace.resolve("central-settings.xml");
    DownstreamOrchestrator.writeCentralSettings(centralSettings);

    BiPredicate<String, String> buildFn =
        (name, phase) -> {
          if (checkoutFailed.contains(name)) {
            return false;
          }
          System.out.println("::group::" + name + " " + phase + " at " + headShas.get(name));
          System.out.flush();
          ProjectConfig cfg = projects.get(name);
          Map<String, String> ph =
              DownstreamOrchestrator.createPlaceholders(
                  sources.get(name), repository, settings, null, centralSettings);
          Map<String, String> repoEnv = DownstreamOrchestrator.expandEnv(cfg.env(), ph);
          List<String> args = new ArrayList<>();
          args.add("--no-snapshot-updates");
          args.addAll(DownstreamOrchestrator.expandPlaceholders(cfg.arguments(), ph));
          args.add("clean");
          args.add("install");
          boolean skipTests = "install-cycle".equals(phase);
          if (skipTests) {
            args.add("-DskipTests");
          }
          args.addAll(DownstreamOrchestrator.fastSkipFlags(args, true));
          boolean ok;
          try {
            ok =
                DownstreamOrchestrator.runMaven(
                    sources.get(name),
                    args,
                    repository,
                    settings,
                    output.resolve(name + "-" + phase + ".log"),
                    repoEnv);
            DownstreamOrchestrator.stripRemoteRepositories(repository);
          } catch (IOException | InterruptedException | RuntimeException e) {
            System.out.println(
                "::error::Cannot build " + name + ": " + e.getClass().getSimpleName());
            ok = false;
          }
          if (ok && !skipTests) {
            List<List<String>> checks = cfg.checks();
            for (int i = 0; i < checks.size(); i++) {
              List<String> checkArgs = new ArrayList<>();
              checkArgs.add("--no-snapshot-updates");
              checkArgs.addAll(DownstreamOrchestrator.expandPlaceholders(checks.get(i), ph));
              checkArgs.addAll(DownstreamOrchestrator.fastSkipFlags(checkArgs, true));
              try {
                ok =
                    DownstreamOrchestrator.runMaven(
                        sources.get(name),
                        checkArgs,
                        repository,
                        settings,
                        output.resolve(name + "-" + phase + "-check-" + i + ".log"),
                        repoEnv);
                DownstreamOrchestrator.stripRemoteRepositories(repository);
              } catch (IOException | InterruptedException | RuntimeException e) {
                ok = false;
              }
              if (!ok) {
                break;
              }
            }
          }
          System.out.println("::endgroup::");
          System.out.flush();
          return ok;
        };

    List<List<String>> groups = new ArrayList<>();
    for (JsonNode gNode : planData.path("groups")) {
      List<String> g = new ArrayList<>();
      gNode.forEach(item -> g.add(item.asText()));
      groups.add(g);
    }

    Map<String, String> results = executeNightlyGroups(projects, groups, buildFn);
    JsonNode baseProjects = baseline.path("projects");

    BlameProbe probeFn =
        (failedName, suspect, allSuspects) -> {
          try {
            Path probeDir =
                workspace.resolve("blame-probes").resolve(failedName + "__with__" + suspect);
            if (Files.exists(probeDir)) {
              SarifMerger.deleteRecursively(probeDir);
            }
            Path probeRepo = probeDir.resolve("m2");
            Files.createDirectories(probeRepo);
            Path probeLogs = probeDir.resolve("logs");
            Files.createDirectories(probeLogs);

            Set<String> closure = new TreeSet<>(ancestorsOf(projects, failedName));
            closure.retainAll(allSuspects);
            closure.add(failedName);
            List<List<String>> probeGroups = DownstreamOrchestrator.buildGroups(projects, closure);

            Map<String, Path> probeSources = new LinkedHashMap<>();
            for (String member : closure) {
              String sha =
                  member.equals(suspect)
                      ? headShas.get(member)
                      : SarifMerger.textOrDefault(
                          baseProjects.path(member), "last_green_sha", headShas.get(member));
              Path dest = probeDir.resolve("src").resolve(member);
              Path destParent = dest.getParent();
              if (destParent != null) {
                Files.createDirectories(destParent);
              }
              probeSources.put(member, dest);
              downstream.checkout(projects.get(member).repository(), dest, sha);
            }

            Path probeSettings = probeDir.resolve("settings.xml");
            Path probeCentral = probeDir.resolve("central-settings.xml");
            DownstreamOrchestrator.writeSettings(probeSettings, probeSources.values());
            DownstreamOrchestrator.writeCentralSettings(probeCentral);

            BiPredicate<String, String> probeBuild =
                (member, phase) -> {
                  boolean isTarget = member.equals(failedName) && !"install-cycle".equals(phase);
                  ProjectConfig cfg = projects.get(member);
                  Map<String, String> ph =
                      DownstreamOrchestrator.createPlaceholders(
                          probeSources.get(member), probeRepo, probeSettings, null, probeCentral);
                  Map<String, String> repoEnv = DownstreamOrchestrator.expandEnv(cfg.env(), ph);
                  List<String> cmdArgs = new ArrayList<>();
                  cmdArgs.add("--no-snapshot-updates");
                  cmdArgs.addAll(DownstreamOrchestrator.expandPlaceholders(cfg.arguments(), ph));
                  cmdArgs.add("clean");
                  cmdArgs.add("install");
                  if (!isTarget) {
                    cmdArgs.add("-DskipTests");
                  }
                  cmdArgs.addAll(DownstreamOrchestrator.fastSkipFlags(cmdArgs, true));
                  try {
                    boolean ok =
                        DownstreamOrchestrator.runMaven(
                            probeSources.get(member),
                            cmdArgs,
                            probeRepo,
                            probeSettings,
                            probeLogs.resolve(member + "-" + phase + ".log"),
                            repoEnv);
                    DownstreamOrchestrator.stripRemoteRepositories(probeRepo);
                    if (ok && isTarget) {
                      List<List<String>> checks = cfg.checks();
                      for (int idx = 0; idx < checks.size(); idx++) {
                        List<String> cArgs = new ArrayList<>();
                        cArgs.add("--no-snapshot-updates");
                        cArgs.addAll(
                            DownstreamOrchestrator.expandPlaceholders(checks.get(idx), ph));
                        cArgs.addAll(DownstreamOrchestrator.fastSkipFlags(cArgs, true));
                        ok =
                            DownstreamOrchestrator.runMaven(
                                probeSources.get(member),
                                cArgs,
                                probeRepo,
                                probeSettings,
                                probeLogs.resolve(member + "-check-" + idx + ".log"),
                                repoEnv);
                        DownstreamOrchestrator.stripRemoteRepositories(probeRepo);
                        if (!ok) {
                          break;
                        }
                      }
                    }
                    return ok;
                  } catch (IOException | InterruptedException | RuntimeException e) {
                    return false;
                  }
                };

            Map<String, String> probeRes = executeNightlyGroups(projects, probeGroups, probeBuild);
            return "passed".equals(probeRes.get(failedName));
          } catch (IOException | InterruptedException | RuntimeException e) {
            return false;
          }
        };

    ObjectNode blame =
        isolateBlame(projects, results, headShas, baseProjects, probeFn, maxBlameCandidates, 6);

    ObjectNode compatData = mapper.createObjectNode();
    compatData.set("changed", planData.path("changed"));
    ObjectNode revNode = mapper.createObjectNode();
    for (String k : affected) {
      revNode.put(k, headShas.get(k));
    }
    compatData.set("revisions", revNode);
    compatData.set("groups", planData.path("groups"));
    ObjectNode resNode = mapper.createObjectNode();
    results.forEach(resNode::put);
    compatData.set("results", resNode);
    compatData.set("blame", blame);

    Files.writeString(
        output.resolve("compat-results.json"), mapper.writeValueAsString(compatData) + "\n");
    String summary = renderCompatSummary(planData, results, blame);
    Files.writeString(output.resolve("compat-summary.md"), summary);
    SonarQubeClient.appendStepSummary(summary);
    System.out.print(summary);

    boolean allPassed = results.values().stream().allMatch("passed"::equals);
    if (allPassed && overlayOut != null) {
      int count = exportM2Overlay(repository, overlayOut, DEFAULT_OVERLAY_PREFIXES);
      System.out.println("Exported " + count + " verified snapshot files to " + overlayOut);
    }
    return allPassed ? 0 : 1;
  }

  public boolean runVerifyProject(
      ProjectConfig cfg,
      String projectName,
      Path checkoutDir,
      Path repository,
      Path settings,
      Path logDir,
      Path overlay,
      String runnerTemp)
      throws IOException, InterruptedException {
    Path resolvedCheckout = checkoutDir.toAbsolutePath().normalize();
    Path resolvedRepo = repository.toAbsolutePath().normalize();
    Path resolvedSettings = settings.toAbsolutePath().normalize();
    Path resolvedLogs = logDir.toAbsolutePath().normalize();
    Files.createDirectories(resolvedLogs);

    if (overlay != null && Files.isRegularFile(overlay)) {
      int restored = applyM2Overlay(overlay, resolvedRepo);
      System.out.println("Restored " + restored + " snapshot entries from compatibility overlay");
    }

    Path centralSettings = resolvedSettings.resolveSibling("central-settings.xml");
    DownstreamOrchestrator.writeSettings(resolvedSettings, List.of(resolvedCheckout));
    DownstreamOrchestrator.writeCentralSettings(centralSettings);
    Map<String, String> ph =
        DownstreamOrchestrator.createPlaceholders(
            resolvedCheckout, resolvedRepo, resolvedSettings, runnerTemp, centralSettings);
    Map<String, String> repoEnv = DownstreamOrchestrator.expandEnv(cfg.env(), ph);

    List<String> verifyGoals = DownstreamOrchestrator.expandPlaceholders(cfg.verifyGoals(), ph);
    List<String> baseArgs = new ArrayList<>();
    baseArgs.add("--no-snapshot-updates");
    baseArgs.addAll(DownstreamOrchestrator.expandPlaceholders(cfg.arguments(), ph));
    baseArgs.addAll(verifyGoals);

    boolean ok =
        DownstreamOrchestrator.runMaven(
            resolvedCheckout,
            baseArgs,
            resolvedRepo,
            resolvedSettings,
            resolvedLogs.resolve(projectName + "-verify.log"),
            repoEnv);
    DownstreamOrchestrator.stripRemoteRepositories(resolvedRepo);

    if (ok) {
      List<List<String>> checks = !cfg.fullChecks().isEmpty() ? cfg.fullChecks() : cfg.checks();
      for (int idx = 0; idx < checks.size(); idx++) {
        List<String> checkArgs = new ArrayList<>();
        checkArgs.add("--no-snapshot-updates");
        checkArgs.addAll(DownstreamOrchestrator.expandPlaceholders(checks.get(idx), ph));
        ok =
            DownstreamOrchestrator.runMaven(
                resolvedCheckout,
                checkArgs,
                resolvedRepo,
                resolvedSettings,
                resolvedLogs.resolve(projectName + "-fullcheck-" + idx + ".log"),
                repoEnv);
        DownstreamOrchestrator.stripRemoteRepositories(resolvedRepo);
        if (!ok) {
          break;
        }
      }
    }

    if (ok) {
      String sonarToken = System.getenv("SONAR_TOKEN");
      boolean sonarPresent = sonarToken != null && !sonarToken.isBlank();
      List<PostVerifyStep> postSteps = cfg.postVerifyCommands();
      for (int idx = 0; idx < postSteps.size(); idx++) {
        PostVerifyStep step = postSteps.get(idx);
        if (step.ifSonar() && !sonarPresent) {
          continue;
        }
        List<String> cmd = DownstreamOrchestrator.expandPlaceholders(step.cmd(), ph);
        Map<String, String> stepEnv = new LinkedHashMap<>(repoEnv);
        stepEnv.put("MAVEN_SETTINGS", resolvedSettings.toString());
        stepEnv.put("MAVEN_REPO_LOCAL", resolvedRepo.toString());
        stepEnv.putAll(DownstreamOrchestrator.expandEnv(step.env(), ph));
        Path cwd =
            step.cwd() != null && !step.cwd().isBlank()
                ? resolvedCheckout
                    .resolve(
                        DownstreamOrchestrator.expandPlaceholders(List.of(step.cwd()), ph).get(0))
                    .normalize()
                : resolvedCheckout;
        System.out.println(
            "::group::" + projectName + " post-verify " + idx + ": " + String.join(" ", cmd));
        System.out.flush();
        boolean stepOk;
        try {
          stepOk =
              DownstreamOrchestrator.runCommandLogged(
                  cmd,
                  cwd,
                  resolvedLogs.resolve(projectName + "-postverify-" + idx + ".log"),
                  stepEnv);
        } catch (IOException e) {
          System.out.println(
              "::error::Cannot run post-verify step "
                  + idx
                  + " for "
                  + projectName
                  + ": "
                  + e.getClass().getSimpleName());
          stepOk = false;
        }
        DownstreamOrchestrator.stripRemoteRepositories(resolvedRepo);
        System.out.println("::endgroup::");
        System.out.flush();
        if (!stepOk && !step.allowFailure()) {
          ok = false;
          break;
        }
      }
    }
    return ok;
  }

  public boolean runSonarProject(
      ProjectConfig cfg,
      String projectName,
      Path checkoutDir,
      Path repository,
      Path settings,
      Path logDir)
      throws IOException, InterruptedException {
    Path resolvedCheckout = checkoutDir.toAbsolutePath().normalize();
    Path resolvedRepo = repository.toAbsolutePath().normalize();
    Path resolvedSettings = settings.toAbsolutePath().normalize();
    Path resolvedLogs = logDir.toAbsolutePath().normalize();
    Files.createDirectories(resolvedLogs);

    DownstreamOrchestrator.writeSettings(resolvedSettings, List.of(resolvedCheckout));
    Map<String, String> ph =
        DownstreamOrchestrator.createPlaceholders(
            resolvedCheckout, resolvedRepo, resolvedSettings, null, null);
    Map<String, String> repoEnv = DownstreamOrchestrator.expandEnv(cfg.env(), ph);

    String envKey = System.getenv("SONAR_PROJECT_KEY");
    String projectKey = envKey != null && !envKey.isBlank() ? envKey.trim() : projectName;
    List<String> sonarArgs = new ArrayList<>();
    sonarArgs.add("--no-snapshot-updates");
    sonarArgs.add("sonar:sonar");
    sonarArgs.add("-Dsonar.projectKey=" + projectKey);
    sonarArgs.add("-Dsonar.projectName=" + projectKey);
    sonarArgs.add("-Dsonar.qualitygate.wait=true");
    sonarArgs.addAll(DownstreamOrchestrator.expandPlaceholders(cfg.sonarArgs(), ph));

    boolean ok =
        DownstreamOrchestrator.runMaven(
            resolvedCheckout,
            sonarArgs,
            resolvedRepo,
            resolvedSettings,
            resolvedLogs.resolve(projectName + "-sonar.log"),
            repoEnv);
    DownstreamOrchestrator.stripRemoteRepositories(resolvedRepo);
    return ok;
  }

  public void runUpdateBaselineCommand(
      Path catalogPath,
      Path baselinePath,
      Path planPath,
      Path compatResultsPath,
      List<String> stage3Statuses,
      Path outPath)
      throws IOException {
    Map<String, ProjectConfig> projects = downstream.loadCatalog(catalogPath);
    ObjectNode baseline = loadBaseline(baselinePath);
    JsonNode planData = mapper.readTree(Files.readString(planPath));
    Map<String, String> compatResults = new LinkedHashMap<>();
    if (compatResultsPath != null && Files.isRegularFile(compatResultsPath)) {
      JsonNode compatDoc = mapper.readTree(Files.readString(compatResultsPath));
      compatDoc
          .path("results")
          .fields()
          .forEachRemaining(e -> compatResults.put(e.getKey(), e.getValue().asText()));
    }
    Map<String, String> stage3Results = new LinkedHashMap<>();
    if (stage3Statuses != null) {
      for (String raw : stage3Statuses) {
        if (raw != null && raw.contains("=")) {
          int eq = raw.indexOf('=');
          stage3Results.put(raw.substring(0, eq).trim(), raw.substring(eq + 1).trim());
        }
      }
    }
    ObjectNode newBaseline =
        updateBaseline(projects, baseline, planData, compatResults, stage3Results, null);
    Path outParent = outPath.toAbsolutePath().normalize().getParent();
    if (outParent != null) {
      Files.createDirectories(outParent);
    }
    Files.writeString(outPath, mapper.writeValueAsString(newBaseline) + "\n");
  }

  private ObjectNode createPlanNode(
      String mode,
      boolean shouldRun,
      List<String> changed,
      List<String> unhealthyVsGreen,
      List<String> affected,
      List<List<String>> groups,
      ArrayNode matrixInclude,
      Map<String, String> headShas,
      ObjectNode commitRanges) {
    ObjectNode node = mapper.createObjectNode();
    node.put("mode", mode);
    node.put("should_run", shouldRun);
    node.set("changed", toStringArrayNode(changed));
    node.set("unhealthy_vs_green", toStringArrayNode(unhealthyVsGreen));
    node.set("affected", toStringArrayNode(affected));
    ArrayNode gArr = mapper.createArrayNode();
    for (List<String> g : groups) {
      gArr.add(toStringArrayNode(g));
    }
    node.set("groups", gArr);
    node.set("matrix_include", matrixInclude);
    ObjectNode shasNode = mapper.createObjectNode();
    headShas.forEach(shasNode::put);
    node.set("head_shas", shasNode);
    node.set("commit_ranges", commitRanges);
    return node;
  }

  private ArrayNode toStringArrayNode(Collection<String> values) {
    ArrayNode arr = mapper.createArrayNode();
    values.forEach(arr::add);
    return arr;
  }

  private static void putOrNull(ObjectNode node, String key, String value) {
    if (value != null) {
      node.put(key, value);
    } else {
      node.putNull(key);
    }
  }

  private static String truncateSha(String sha) {
    if (sha == null) {
      return "";
    }
    return sha.length() > 12 ? sha.substring(0, 12) : sha;
  }
}

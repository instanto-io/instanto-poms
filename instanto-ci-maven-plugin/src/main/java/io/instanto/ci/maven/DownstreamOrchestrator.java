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
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.BiPredicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Loads the downstream catalog, computes Tarjan SCC build groups, manages isolated checkouts and
 * Maven settings, and executes downstream compatibility builds.
 */
public final class DownstreamOrchestrator {

  public static final List<String> MAVEN_RESILIENCE_FLAGS =
      List.of(
          "-Daether.transport.http.connectTimeout=30000",
          "-Daether.transport.http.requestTimeout=120000",
          "-Daether.transport.http.retryHandler.count=8",
          "-Daether.transport.http.retryHandler.interval=10000",
          "-Daether.transport.http.retryHandler.serviceUnavailable=429,502,503,504",
          "-Dmaven.artifact.threads=2");

  private static final Pattern PROJECT_NAME_PATTERN = Pattern.compile("[a-z0-9][a-z0-9.-]*");
  private static final Pattern REPO_SLUG_PATTERN =
      Pattern.compile("(?:instanto-io|cstainton)/[a-z0-9][a-z0-9.-]*");
  private static final Pattern GITHUB_ID_PATTERN =
      Pattern.compile("<id>(github[A-Za-z0-9._-]*)</id>");

  private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
  private final HttpClient httpClient;

  public DownstreamOrchestrator() {
    this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build());
  }

  public DownstreamOrchestrator(HttpClient httpClient) {
    this.httpClient = httpClient;
  }

  public record PostVerifyStep(
      List<String> cmd,
      Map<String, String> env,
      String cwd,
      boolean ifSonar,
      boolean allowFailure) {}

  public record ProjectConfig(
      String name,
      String repository,
      List<String> dependencies,
      List<String> arguments,
      List<List<String>> checks,
      List<List<String>> fullChecks,
      List<String> verifyGoals,
      List<String> runnerLabels,
      boolean installPlaywright,
      List<String> sonarArgs,
      String nodeVersion,
      Map<String, String> env,
      List<PostVerifyStep> postVerifyCommands) {}

  public Map<String, ProjectConfig> loadCatalog(Path path) throws IOException {
    JsonNode data = mapper.readTree(Files.readString(path));
    if (data.path("schema_version").asInt(-1) != 1
        || !"instanto-io".equals(SarifMerger.textOrNull(data, "organisation"))) {
      throw new IllegalArgumentException("Unsupported downstream catalog");
    }
    JsonNode projectsNode = data.get("projects");
    if (projectsNode == null || !projectsNode.isObject()) {
      throw new IllegalArgumentException("Unsupported downstream catalog: missing projects");
    }

    Set<String> allNames = new LinkedHashSet<>();
    projectsNode.fieldNames().forEachRemaining(allNames::add);

    Map<String, ProjectConfig> result = new LinkedHashMap<>();
    for (String name : allNames) {
      if (!PROJECT_NAME_PATTERN.matcher(name).matches()) {
        throw new IllegalArgumentException("Invalid project name: " + name);
      }
      JsonNode cfg = projectsNode.get(name);
      List<String> deps = readStringArray(cfg.get("dependencies"), "dependencies of " + name, true);
      for (String dep : deps) {
        if (!allNames.contains(dep)) {
          throw new IllegalArgumentException("Unknown dependency of " + name + ": " + dep);
        }
        if (name.equals(dep)) {
          throw new IllegalArgumentException("Self dependency of " + name);
        }
      }
      String repo = SarifMerger.textOrDefault(cfg, "repository", "instanto-io/" + name);
      if (!REPO_SLUG_PATTERN.matcher(repo).matches()) {
        throw new IllegalArgumentException("Invalid repository for " + name);
      }
      List<String> arguments =
          cfg.has("arguments")
              ? readStringArray(cfg.get("arguments"), "Checks for " + name, true)
              : List.of();
      List<List<String>> checks =
          cfg.has("checks") ? readNestedStringArrays(cfg.get("checks"), name) : List.of();
      List<List<String>> fullChecks =
          cfg.has("full_checks") ? readNestedStringArrays(cfg.get("full_checks"), name) : List.of();
      List<String> verifyGoals =
          cfg.has("verify_goals")
              ? readStringArray(cfg.get("verify_goals"), "verify_goals for " + name, false)
              : List.of("clean", "verify");
      List<String> runnerLabels =
          cfg.has("runner_labels")
              ? readStringArray(cfg.get("runner_labels"), "runner_labels for " + name, false)
              : List.of("self-hosted", "instanto-container");
      boolean installPlaywright = false;
      if (cfg.has("install_playwright")) {
        JsonNode ip = cfg.get("install_playwright");
        if (!ip.isBoolean()) {
          throw new IllegalArgumentException(
              "install_playwright for " + name + " must be a boolean");
        }
        installPlaywright = ip.asBoolean();
      }
      List<String> sonarArgs =
          cfg.has("sonar_args")
              ? readStringArray(cfg.get("sonar_args"), "sonar_args for " + name, true)
              : List.of();
      String nodeVersion = "";
      if (cfg.has("node_version")) {
        JsonNode nv = cfg.get("node_version");
        if (!nv.isTextual() || nv.asText().isBlank()) {
          throw new IllegalArgumentException(
              "node_version for " + name + " must be a non-empty string");
        }
        nodeVersion = nv.asText();
      }
      Map<String, String> env =
          cfg.has("env") ? readStringMap(cfg.get("env"), "env for " + name) : Map.of();

      List<PostVerifyStep> postVerify = new ArrayList<>();
      if (cfg.has("post_verify_commands")) {
        JsonNode cmds = cfg.get("post_verify_commands");
        if (!cmds.isArray()) {
          throw new IllegalArgumentException(
              "post_verify_commands for " + name + " must be an array");
        }
        for (JsonNode step : cmds) {
          if (!step.isObject()) {
            throw new IllegalArgumentException(
                "post_verify_commands entries for " + name + " must be objects");
          }
          List<String> cmd =
              readStringArray(step.get("cmd"), "post_verify_commands cmd for " + name, false);
          Map<String, String> stepEnv =
              step.has("env")
                  ? readStringMap(step.get("env"), "post_verify_commands env for " + name)
                  : Map.of();
          String cwd = null;
          if (step.has("cwd")) {
            JsonNode cwdNode = step.get("cwd");
            if (!cwdNode.isTextual() || cwdNode.asText().isBlank()) {
              throw new IllegalArgumentException(
                  "post_verify_commands cwd for " + name + " must be a non-empty string");
            }
            cwd = cwdNode.asText();
          }
          boolean ifSonar = false;
          if (step.has("if_sonar")) {
            if (!step.get("if_sonar").isBoolean()) {
              throw new IllegalArgumentException(
                  "post_verify_commands if_sonar for " + name + " must be a boolean");
            }
            ifSonar = step.get("if_sonar").asBoolean();
          }
          boolean allowFailure = false;
          if (step.has("allow_failure")) {
            if (!step.get("allow_failure").isBoolean()) {
              throw new IllegalArgumentException(
                  "post_verify_commands allow_failure for " + name + " must be a boolean");
            }
            allowFailure = step.get("allow_failure").asBoolean();
          }
          postVerify.add(new PostVerifyStep(cmd, stepEnv, cwd, ifSonar, allowFailure));
        }
      }

      result.put(
          name,
          new ProjectConfig(
              name,
              repo,
              deps,
              arguments,
              checks,
              fullChecks,
              verifyGoals,
              runnerLabels,
              installPlaywright,
              sonarArgs,
              nodeVersion,
              env,
              List.copyOf(postVerify)));
    }
    return result;
  }

  public static Set<String> affectedProjects(Map<String, ProjectConfig> projects, String upstream) {
    if (!projects.containsKey(upstream)) {
      return Set.of();
    }
    Set<String> affected = new TreeSet<>();
    affected.add(upstream);
    while (true) {
      Set<String> expanded = new TreeSet<>(affected);
      for (Map.Entry<String, ProjectConfig> entry : projects.entrySet()) {
        for (String dep : entry.getValue().dependencies()) {
          if (affected.contains(dep)) {
            expanded.add(entry.getKey());
            break;
          }
        }
      }
      if (expanded.equals(affected)) {
        return expanded;
      }
      affected = expanded;
    }
  }

  public static List<List<String>> buildGroups(
      Map<String, ProjectConfig> projects, Collection<String> affected) {
    Set<String> affectedSet = new TreeSet<>(affected);
    Map<String, Integer> indices = new HashMap<>();
    Map<String, Integer> low = new HashMap<>();
    Deque<String> stack = new ArrayDeque<>();
    Set<String> active = new LinkedHashSet<>();
    List<List<String>> groups = new ArrayList<>();

    class Tarjan {
      void visit(String name) {
        int idx = indices.size();
        indices.put(name, idx);
        low.put(name, idx);
        stack.push(name);
        active.add(name);

        ProjectConfig cfg = projects.get(name);
        if (cfg != null) {
          Set<String> sortedDeps = new TreeSet<>(cfg.dependencies());
          for (String dep : sortedDeps) {
            if (!affectedSet.contains(dep)) {
              continue;
            }
            if (!indices.containsKey(dep)) {
              visit(dep);
              low.put(name, Math.min(low.get(name), low.get(dep)));
            } else if (active.contains(dep)) {
              low.put(name, Math.min(low.get(name), indices.get(dep)));
            }
          }
        }

        if (low.get(name).equals(indices.get(name))) {
          List<String> group = new ArrayList<>();
          while (true) {
            String member = stack.pop();
            active.remove(member);
            group.add(member);
            if (member.equals(name)) {
              break;
            }
          }
          group.sort(Comparator.naturalOrder());
          groups.add(List.copyOf(group));
        }
      }
    }

    Tarjan tarjan = new Tarjan();
    for (String name : affectedSet) {
      if (!indices.containsKey(name)) {
        tarjan.visit(name);
      }
    }
    return groups;
  }

  public JsonNode api(String path) throws IOException, InterruptedException {
    String token = System.getenv("MAVEN_TOKEN");
    HttpRequest.Builder req =
        HttpRequest.newBuilder(URI.create("https://api.github.com/" + path))
            .timeout(Duration.ofSeconds(60))
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "instanto-downstream-ci")
            .GET();
    if (token != null && !token.isBlank()) {
      req.header("Authorization", "Bearer " + token.trim());
    }
    HttpResponse<String> resp = httpClient.send(req.build(), HttpResponse.BodyHandlers.ofString());
    if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
      throw new IOException("GitHub API returned HTTP " + resp.statusCode() + " for " + path);
    }
    return mapper.readTree(resp.body());
  }

  public String checkout(String repo, Path destination, String sha)
      throws IOException, InterruptedException {
    String resolvedSha = sha;
    if (resolvedSha == null || resolvedSha.isBlank()) {
      JsonNode info = api("repos/" + repo);
      String branch = SarifMerger.textOrDefault(info, "default_branch", "main");
      resolvedSha =
          SarifMerger.textOrDefault(api("repos/" + repo + "/commits/" + branch), "sha", "");
    }
    Map<String, String> env = new HashMap<>(System.getenv());
    String token = env.getOrDefault("MAVEN_TOKEN", "");
    if (!token.isBlank()) {
      String basic =
          Base64.getEncoder()
              .encodeToString(("x-access-token:" + token).getBytes(StandardCharsets.UTF_8));
      env.put("GIT_CONFIG_COUNT", "1");
      env.put("GIT_CONFIG_KEY_0", "http.https://github.com/.extraheader");
      env.put("GIT_CONFIG_VALUE_0", "AUTHORIZATION: basic " + basic);
    }
    Files.createDirectories(destination);
    runGit(destination.getParent(), env, "git", "init", "-q", destination.toString());
    runGit(
        destination, env, "git", "remote", "add", "origin", "https://github.com/" + repo + ".git");
    runGit(destination, env, "git", "fetch", "--quiet", "--depth=1", "origin", resolvedSha);
    runGit(destination, env, "git", "checkout", "--quiet", "--detach", "FETCH_HEAD");
    return resolvedSha;
  }

  public static String isolateUpstream(Path source, Path destination)
      throws IOException, InterruptedException {
    Path resolvedSource = source.toAbsolutePath().normalize();
    Process revProc =
        new ProcessBuilder("git", "rev-parse", "HEAD").directory(resolvedSource.toFile()).start();
    String revision =
        new String(revProc.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
    if (revProc.waitFor() != 0) {
      throw new IOException("Failed to read HEAD revision from " + resolvedSource);
    }
    runGit(
        resolvedSource,
        System.getenv(),
        "git",
        "clone",
        "--quiet",
        "--no-local",
        "--no-checkout",
        resolvedSource.toString(),
        destination.toAbsolutePath().normalize().toString());
    runGit(destination, System.getenv(), "git", "checkout", "--quiet", "--detach", revision);
    return revision;
  }

  public static List<String> fastSkipFlags(Collection<String> existing, boolean skipJacoco) {
    Set<String> existingKeys = new LinkedHashSet<>();
    for (String arg : existing) {
      int eq = arg.indexOf('=');
      existingKeys.add(eq >= 0 ? arg.substring(0, eq) : arg);
    }
    List<String> candidates = new ArrayList<>();
    candidates.add("-Dspotbugs.skip=true");
    candidates.add("-Dspotless.skip=true");
    if (skipJacoco) {
      candidates.add("-Djacoco.skip=true");
    }
    List<String> flags = new ArrayList<>();
    for (String flag : candidates) {
      String key = flag.substring(0, flag.indexOf('='));
      if (!existingKeys.contains(key)) {
        flags.add(flag);
      }
    }
    return flags;
  }

  public static void stripRemoteRepositories(Path repositoryRoot) throws IOException {
    if (repositoryRoot == null || !Files.exists(repositoryRoot)) {
      return;
    }
    String cacheRoot = System.getenv("MAVEN_DOWNLOAD_CACHE");
    if (cacheRoot != null && !cacheRoot.isBlank()) {
      new MavenDownloadCache().save(repositoryRoot, Path.of(cacheRoot.trim()).resolve("central"));
    }
    List<Path> toDelete;
    try (Stream<Path> walk = Files.walk(repositoryRoot)) {
      toDelete =
          walk.filter(Files::isRegularFile)
              .filter(
                  p -> {
                    String name = p.getFileName().toString();
                    return "_remote.repositories".equals(name) || name.endsWith(".lastUpdated");
                  })
              .toList();
    }
    for (Path p : toDelete) {
      Files.deleteIfExists(p);
    }
  }

  public static void writeSettings(Path path, Collection<Path> sources) throws IOException {
    Set<String> ids = new TreeSet<>();
    ids.add("github");
    if (sources != null) {
      for (Path source : sources) {
        if (source == null || !Files.exists(source)) {
          continue;
        }
        try (Stream<Path> walk = Files.walk(source)) {
          for (Path pom :
              walk.filter(Files::isRegularFile)
                  .filter(p -> "pom.xml".equals(p.getFileName().toString()))
                  .toList()) {
            if (!SarifMerger.toPosix(pom).contains("/target/")) {
              Matcher m = GITHUB_ID_PATTERN.matcher(Files.readString(pom));
              while (m.find()) {
                ids.add(m.group(1));
              }
            }
          }
        }
      }
    }

    StringBuilder xml = new StringBuilder();
    xml.append("<settings><servers>");
    for (String id : ids) {
      xml.append("<server><id>")
          .append(id)
          .append(
              "</id><username>${env.MAVEN_ACTOR}</username><password>${env.MAVEN_TOKEN}</password></server>");
    }
    xml.append(
        "<server><id>forgejo-instanto</id><username>${env.FORGEJO_PACKAGE_USER}</username><password>${env.FORGEJO_PACKAGE_TOKEN}</password></server>");
    xml.append(
        "<server><id>artifacts</id><username>${env.ARTIFACTS_USERNAME}</username><password>${env.ARTIFACTS_TOKEN}</password></server>");
    xml.append("</servers>");

    String mirror = System.getenv("MAVEN_CENTRAL_MIRROR_URL");
    if (mirror != null && !mirror.isBlank()) {
      xml.append(
          "<mirrors><mirror><id>central</id><mirrorOf>central</mirrorOf><url>${env.MAVEN_CENTRAL_MIRROR_URL}</url></mirror></mirrors>");
    }
    xml.append("<profiles><profile><id>instanto-snapshots</id>");
    xml.append(
        "<repositories><repository><id>forgejo-instanto</id><url>https://packages.instanto.io/api/packages/instanto-io/maven</url><releases><enabled>false</enabled></releases><snapshots><enabled>true</enabled></snapshots></repository></repositories>");
    xml.append(
        "<pluginRepositories><pluginRepository><id>forgejo-instanto</id><url>https://packages.instanto.io/api/packages/instanto-io/maven</url><releases><enabled>false</enabled></releases><snapshots><enabled>true</enabled></snapshots></pluginRepository></pluginRepositories>");
    xml.append(
        "</profile></profiles><activeProfiles><activeProfile>instanto-snapshots</activeProfile></activeProfiles></settings>");

    if (path.getParent() != null) {
      Files.createDirectories(path.getParent());
    }
    Files.writeString(path, xml.toString());
    setOwnerOnlyPermissions(path);
  }

  public static void writeCentralSettings(Path path) throws IOException {
    StringBuilder xml = new StringBuilder("<settings>");
    String mirror = System.getenv("MAVEN_CENTRAL_MIRROR_URL");
    if (mirror != null && !mirror.isBlank()) {
      xml.append(
          "<mirrors><mirror><id>central</id><mirrorOf>central</mirrorOf><url>${env.MAVEN_CENTRAL_MIRROR_URL}</url></mirror></mirrors>");
    }
    xml.append("</settings>");
    if (path.getParent() != null) {
      Files.createDirectories(path.getParent());
    }
    Files.writeString(path, xml.toString());
    setOwnerOnlyPermissions(path);
  }

  public static String resolveMavenExecutable(Path source) {
    if (source != null) {
      Path wrapper = source.resolve("mvnw");
      if (Files.isRegularFile(wrapper) && Files.isExecutable(wrapper)) {
        return wrapper.toString();
      }
    }
    return "mvn";
  }

  public static List<String> expandPlaceholders(
      List<String> values, Map<String, String> placeholders) {
    List<String> expanded = new ArrayList<>(values.size());
    for (String value : values) {
      String item = value;
      for (Map.Entry<String, String> e : placeholders.entrySet()) {
        item = item.replace("{" + e.getKey() + "}", e.getValue());
      }
      expanded.add(item);
    }
    return expanded;
  }

  public static Map<String, String> expandEnv(
      Map<String, String> envMap, Map<String, String> placeholders) {
    Map<String, String> out = new LinkedHashMap<>();
    if (envMap != null) {
      for (Map.Entry<String, String> e : envMap.entrySet()) {
        out.put(e.getKey(), expandPlaceholders(List.of(e.getValue()), placeholders).get(0));
      }
    }
    return out;
  }

  public static Map<String, String> createPlaceholders(
      Path checkoutDir, Path repository, Path settings, String runnerTemp, Path centralSettings) {
    Path resolvedCheckout = checkoutDir.toAbsolutePath().normalize();
    Path resolvedRepo = repository.toAbsolutePath().normalize();
    Path resolvedSettings = settings.toAbsolutePath().normalize();
    Path resolvedCentral =
        centralSettings != null
            ? centralSettings.toAbsolutePath().normalize()
            : resolvedSettings.resolveSibling("central-settings.xml");
    String rt =
        runnerTemp != null && !runnerTemp.isBlank()
            ? runnerTemp
            : System.getenv().getOrDefault("RUNNER_TEMP", resolvedRepo.getParent().toString());
    Map<String, String> map = new LinkedHashMap<>();
    map.put("checkout", resolvedCheckout.toString());
    map.put("settings", resolvedSettings.toString());
    map.put("central_settings", resolvedCentral.toString());
    map.put("m2", resolvedRepo.toString());
    map.put("mvn", resolveMavenExecutable(resolvedCheckout));
    map.put("runner_temp", rt);
    return map;
  }

  public static boolean runMaven(
      Path source,
      List<String> arguments,
      Path repository,
      Path settings,
      Path logFile,
      Map<String, String> extraEnv)
      throws IOException, InterruptedException {
    String executable = resolveMavenExecutable(source);
    Set<String> existingKeys = new LinkedHashSet<>();
    for (String arg : arguments) {
      int eq = arg.indexOf('=');
      existingKeys.add(eq >= 0 ? arg.substring(0, eq) : arg);
    }
    List<String> command = new ArrayList<>();
    command.add(executable);
    command.add("--batch-mode");
    command.add("--no-transfer-progress");
    command.add("--errors");
    command.add("--fail-at-end");
    command.add("--settings");
    command.add(settings.toString());
    command.add("-Dmaven.repo.local=" + repository);
    command.add("-Dmaven.top-level-basedir=" + source.toAbsolutePath().normalize());
    for (String flag : MAVEN_RESILIENCE_FLAGS) {
      String key = flag.substring(0, flag.indexOf('='));
      if (!existingKeys.contains(key)) {
        command.add(flag);
      }
    }
    command.addAll(arguments);
    return runCommandLogged(command, source, logFile, extraEnv);
  }

  public static boolean runCommandLogged(
      List<String> command, Path cwd, Path logFile, Map<String, String> extraEnv)
      throws IOException, InterruptedException {
    if (logFile.getParent() != null) {
      Files.createDirectories(logFile.getParent());
    }
    ProcessBuilder pb =
        new ProcessBuilder(command).directory(cwd.toFile()).redirectErrorStream(true);
    if (extraEnv != null && !extraEnv.isEmpty()) {
      pb.environment().putAll(extraEnv);
    }
    Process process = pb.start();
    try (BufferedReader reader =
            new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        BufferedWriter writer = Files.newBufferedWriter(logFile, StandardCharsets.UTF_8)) {
      String line;
      while ((line = reader.readLine()) != null) {
        writer.write(line);
        writer.newLine();
        System.out.println(line);
        System.out.flush();
      }
    }
    return process.waitFor() == 0;
  }

  public static Map<String, String> executeGroups(
      Map<String, ProjectConfig> projects,
      List<List<String>> groups,
      String upstream,
      BiPredicate<String, String> buildFn,
      boolean seedOk) {
    Map<String, String> results = new TreeMap<>();
    results.put(upstream, seedOk ? "passed" : "failed");

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
      if (depFailed || (group.contains(upstream) && !seedOk)) {
        for (String name : group) {
          results.putIfAbsent(name, "blocked");
        }
        continue;
      }

      if (group.size() == 1) {
        String name = group.get(0);
        if (!name.equals(upstream)) {
          results.put(name, buildFn.test(name, "verify") ? "passed" : "failed");
        }
        continue;
      }

      boolean ready = true;
      for (String name : group) {
        if (name.equals(upstream)) {
          continue;
        }
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
      boolean anyNotPassed = group.stream().anyMatch(n -> !"passed".equals(results.get(n)));
      if (anyNotPassed) {
        for (String name : group) {
          if ("passed".equals(results.get(name))) {
            results.put(name, "blocked");
          }
        }
      }
    }
    return results;
  }

  public void writeDownstreamReport(
      Path outputDir,
      String upstream,
      Map<String, String> revisions,
      List<List<String>> groups,
      Map<String, String> results)
      throws IOException {
    Files.createDirectories(outputDir);
    ObjectNode json = mapper.createObjectNode();
    json.put("upstream", upstream);
    ObjectNode revNode = mapper.createObjectNode();
    revisions.forEach(revNode::put);
    json.set("revisions", revNode);
    ArrayNode groupsNode = mapper.createArrayNode();
    for (List<String> g : groups) {
      ArrayNode gArr = mapper.createArrayNode();
      g.forEach(gArr::add);
      groupsNode.add(gArr);
    }
    json.set("groups", groupsNode);
    ObjectNode resNode = mapper.createObjectNode();
    results.forEach(resNode::put);
    json.set("results", resNode);
    Files.writeString(outputDir.resolve("results.json"), mapper.writeValueAsString(json) + "\n");

    List<String> lines = new ArrayList<>();
    lines.add("## Downstream compatibility");
    lines.add("");
    lines.add("Upstream: `" + upstream + "`");
    lines.add("");
    lines.add("| Project | Revision | Result |");
    lines.add("| --- | --- | --- |");
    for (Map.Entry<String, String> entry : new TreeMap<>(results).entrySet()) {
      String sha = revisions.getOrDefault(entry.getKey(), "unavailable");
      lines.add("| " + entry.getKey() + " | `" + sha + "` | " + entry.getValue() + " |");
    }
    lines.add("");
    lines.add(
        "All builds use a job-local Maven repository; no downstream artifacts are published.");
    lines.add(
        "Repository cycles are installed once and verified again as a group. A failed group blocks its dependants.");
    String summary = String.join("\n", lines) + "\n";
    System.out.print(summary);
    Files.writeString(outputDir.resolve("summary.md"), summary);
    SonarQubeClient.appendStepSummary(summary);
  }

  public int runDownstream(
      Path catalogPath,
      String upstream,
      Path sourceDir,
      Path workspaceDir,
      Path customSettings,
      boolean planOnly,
      boolean verifyUpstream,
      boolean skipAnalysis)
      throws Exception {
    Map<String, ProjectConfig> projects = loadCatalog(catalogPath);
    Set<String> affected = affectedProjects(projects, upstream);
    List<List<String>> groups = buildGroups(projects, affected);

    ObjectNode planJson = mapper.createObjectNode();
    planJson.put("upstream", upstream);
    ArrayNode gArr = mapper.createArrayNode();
    for (List<String> g : groups) {
      ArrayNode inner = mapper.createArrayNode();
      g.forEach(inner::add);
      gArr.add(inner);
    }
    planJson.set("groups", gArr);
    System.out.println(mapper.writeValueAsString(planJson));

    if (planOnly) {
      return 0;
    }
    if (affected.isEmpty() || (affected.equals(Set.of(upstream)) && !verifyUpstream)) {
      System.out.println("No registered downstream projects.");
      return 0;
    }
    if (workspaceDir == null) {
      throw new IllegalArgumentException("--workspace is required for builds");
    }
    Path workspace = workspaceDir.toAbsolutePath().normalize();
    if (Files.exists(workspace)) {
      throw new IllegalArgumentException("Workspace already exists: " + workspace);
    }
    Files.createDirectories(workspace);
    Path output = workspace.resolve("reports");
    Files.createDirectories(output);
    Path repository = workspace.resolve("m2");
    Files.createDirectories(repository);

    Map<String, Path> sources = new LinkedHashMap<>();
    sources.put(upstream, workspace.resolve(upstream));
    Map<String, String> revisions = new LinkedHashMap<>();
    revisions.put(upstream, isolateUpstream(sourceDir, sources.get(upstream)));

    Set<String> checkoutFailed = new LinkedHashSet<>();
    for (String name : affected) {
      if (name.equals(upstream)) {
        continue;
      }
      Path dest = workspace.resolve(name);
      sources.put(name, dest);
      try {
        revisions.put(name, checkout(projects.get(name).repository(), dest, null));
      } catch (Exception e) {
        System.out.println(
            "::error::Cannot check out " + name + ": " + e.getClass().getSimpleName());
        checkoutFailed.add(name);
      }
    }

    Path settings =
        customSettings != null
            ? customSettings.toAbsolutePath().normalize()
            : workspace.resolve("settings.xml");
    if (customSettings == null) {
      writeSettings(settings, sources.values());
    }
    Path centralSettings = workspace.resolve("central-settings.xml");
    writeCentralSettings(centralSettings);

    BiPredicate<String, String> buildFn =
        (name, phase) -> {
          if (checkoutFailed.contains(name)) {
            return false;
          }
          System.out.println("::group::" + name + " " + phase + " at " + revisions.get(name));
          System.out.flush();
          ProjectConfig cfg = projects.get(name);
          Map<String, String> ph =
              createPlaceholders(sources.get(name), repository, settings, null, centralSettings);
          Map<String, String> repoEnv = expandEnv(cfg.env(), ph);
          List<String> args = new ArrayList<>();
          args.add("seed".equals(phase) ? "--update-snapshots" : "--no-snapshot-updates");
          args.addAll(expandPlaceholders(cfg.arguments(), ph));
          args.add("clean");
          args.add("install");
          boolean skipTests =
              "install-cycle".equals(phase) || ("seed".equals(phase) && !verifyUpstream);
          if (skipTests) {
            args.add("-DskipTests");
          }
          if (skipTests || skipAnalysis) {
            args.addAll(fastSkipFlags(args, skipTests || skipAnalysis));
          }
          boolean ok;
          try {
            ok =
                runMaven(
                    sources.get(name),
                    args,
                    repository,
                    settings,
                    output.resolve(name + "-" + phase + ".log"),
                    repoEnv);
            stripRemoteRepositories(repository);
          } catch (Exception e) {
            System.out.println(
                "::error::Cannot build " + name + ": " + e.getClass().getSimpleName());
            ok = false;
          }
          if (ok && !skipTests) {
            List<List<String>> checks = cfg.checks();
            for (int i = 0; i < checks.size(); i++) {
              List<String> checkArgs = new ArrayList<>();
              checkArgs.add("--no-snapshot-updates");
              checkArgs.addAll(expandPlaceholders(checks.get(i), ph));
              if (skipAnalysis) {
                checkArgs.addAll(fastSkipFlags(checkArgs, true));
              }
              try {
                ok =
                    runMaven(
                        sources.get(name),
                        checkArgs,
                        repository,
                        settings,
                        output.resolve(name + "-" + phase + "-check-" + i + ".log"),
                        repoEnv);
                stripRemoteRepositories(repository);
              } catch (Exception e) {
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

    boolean seedOk = buildFn.test(upstream, "seed");
    Map<String, String> results = executeGroups(projects, groups, upstream, buildFn, seedOk);
    writeDownstreamReport(output, upstream, revisions, groups, results);
    return results.values().stream().allMatch("passed"::equals) ? 0 : 1;
  }

  private static void runGit(Path cwd, Map<String, String> env, String... command)
      throws IOException, InterruptedException {
    ProcessBuilder pb = new ProcessBuilder(command);
    if (cwd != null) {
      pb.directory(cwd.toFile());
    }
    if (env != null) {
      pb.environment().putAll(env);
    }
    Process p = pb.start();
    if (p.waitFor() != 0) {
      throw new IOException("Git command failed with exit code " + p.exitValue());
    }
  }

  private static void setOwnerOnlyPermissions(Path path) {
    try {
      Files.setPosixFilePermissions(
          path, EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
    } catch (UnsupportedOperationException | IOException e) {
      // Ignore on non-POSIX filesystems
    }
  }

  private static List<String> readStringArray(JsonNode node, String label, boolean allowEmpty) {
    if (node == null || !node.isArray() || (!allowEmpty && node.isEmpty())) {
      throw new IllegalArgumentException(label + " must be a string array");
    }
    List<String> out = new ArrayList<>(node.size());
    for (JsonNode item : node) {
      if (!item.isTextual()) {
        throw new IllegalArgumentException(label + " must contain only strings");
      }
      out.add(item.asText());
    }
    return Collections.unmodifiableList(out);
  }

  private static List<List<String>> readNestedStringArrays(JsonNode node, String name) {
    if (node == null || !node.isArray()) {
      throw new IllegalArgumentException("Checks for " + name + " must be argument arrays");
    }
    List<List<String>> out = new ArrayList<>(node.size());
    for (JsonNode child : node) {
      out.add(readStringArray(child, "Checks for " + name, true));
    }
    return Collections.unmodifiableList(out);
  }

  private static Map<String, String> readStringMap(JsonNode node, String label) {
    if (node == null || !node.isObject()) {
      throw new IllegalArgumentException(label + " must be a string map");
    }
    Map<String, String> out = new LinkedHashMap<>();
    node.fields()
        .forEachRemaining(
            e -> {
              if (e.getKey().isEmpty() || !e.getValue().isTextual()) {
                throw new IllegalArgumentException(label + " must be a string map");
              }
              out.put(e.getKey(), e.getValue().asText());
            });
    return Collections.unmodifiableMap(out);
  }
}

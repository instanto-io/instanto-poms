package io.instanto.ci.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SonarAndReportPublisherTest {

  @Test
  void queriesSonarEndpointsAndRendersSummaryAndDiagnostics(@TempDir Path tempDir)
      throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    try {
      server.createContext(
          "/api/qualitygates/project_status",
          ex -> {
            byte[] body =
                """
                {
                  "projectStatus": {
                    "status": "ERROR",
                    "conditions": [
                      {
                        "status": "ERROR",
                        "metricKey": "new_coverage",
                        "comparator": "LT",
                        "errorThreshold": "80",
                        "actualValue": "65.0"
                      }
                    ]
                  }
                }
                """
                    .getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) {
              os.write(body);
            }
          });
      server.createContext(
          "/api/measures/component",
          ex -> {
            byte[] body =
                """
                {
                  "component": {
                    "measures": [
                      {"metric": "coverage", "value": "82.4"},
                      {"metric": "new_coverage", "value": "65.0"},
                      {"metric": "bugs", "value": "1"},
                      {"metric": "vulnerabilities", "value": "0"},
                      {"metric": "code_smells", "value": "4"}
                    ]
                  }
                }
                """
                    .getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) {
              os.write(body);
            }
          });
      server.createContext(
          "/api/issues/search",
          ex -> {
            byte[] body =
                """
                {
                  "total": 1,
                  "issues": [
                    {
                      "key": "issue-1",
                      "rule": "java:S1144",
                      "severity": "MAJOR",
                      "component": "proj:src/A.java",
                      "line": 12,
                      "message": "Remove unused method"
                    }
                  ]
                }
                """
                    .getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) {
              os.write(body);
            }
          });
      server.createContext(
          "/api/ce/task",
          ex -> {
            byte[] body =
                """
                {"task": {"status": "SUCCESS", "analysisId": "AX-123"}}
                """
                    .getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) {
              os.write(body);
            }
          });
      server.start();
      String hostUrl = "http://127.0.0.1:" + server.getAddress().getPort();

      SonarQubeClient client = new SonarQubeClient(hostUrl, "secret-token");
      String md = client.renderQualitySummary(tempDir, "test-proj");
      assertTrue(md.contains("gate error; coverage 82.4%; new-code coverage 65.0%"));
      assertTrue(md.contains("new_coverage is 65.0 (needs LT 80)"));
      assertTrue(md.contains("1 bugs, 0 vulnerabilities, 4 code smells"));
      assertTrue(md.contains("java:S1144"));

      Path reportTask = tempDir.resolve("target/sonar/report-task.txt");
      Files.createDirectories(reportTask.getParent());
      Files.writeString(reportTask, "ceTaskId=TASK-1\n");

      List<String> diag = client.diagnoseQualityGate(tempDir, "test-proj");
      assertTrue(diag.stream().anyMatch(l -> l.contains("Quality gate: ERROR analysis: AX-123")));

      QualitySummaryMojo summaryMojo = new QualitySummaryMojo();
      SarifMergerTest.setField(summaryMojo, "rootDir", tempDir.toFile());
      SarifMergerTest.setField(summaryMojo, "sonarProjectKey", "test-proj");
      SarifMergerTest.setField(summaryMojo, "sonarHostUrl", hostUrl);
      SarifMergerTest.setField(summaryMojo, "sonarToken", "secret-token");
      summaryMojo.execute();

      SonarDiagnoseMojo diagnoseMojo = new SonarDiagnoseMojo();
      SarifMergerTest.setField(diagnoseMojo, "rootDir", tempDir.toFile());
      SarifMergerTest.setField(diagnoseMojo, "sonarProjectKey", "test-proj");
      SarifMergerTest.setField(diagnoseMojo, "sonarHostUrl", hostUrl);
      SarifMergerTest.setField(diagnoseMojo, "sonarToken", "secret-token");
      diagnoseMojo.execute();
    } finally {
      server.stop(0);
    }
  }

  @Test
  void bundlesReportFilesRejectsSymlinksAndPublishesVerifiedTarball(@TempDir Path tempDir)
      throws Exception {
    Path reportsDir = tempDir.resolve("reports");
    Files.createDirectories(reportsDir);
    Files.writeString(reportsDir.resolve("build.log"), "build succeeded\n");

    AtomicReference<byte[]> uploadedBytes = new AtomicReference<>();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    try {
      server.createContext(
          "/api/packages/instanto-io/generic/",
          ex -> {
            if ("PUT".equals(ex.getRequestMethod())) {
              uploadedBytes.set(ex.getRequestBody().readAllBytes());
              ex.sendResponseHeaders(201, -1);
            } else if ("GET".equals(ex.getRequestMethod())) {
              byte[] data = uploadedBytes.get();
              ex.sendResponseHeaders(200, data.length);
              try (OutputStream os = ex.getResponseBody()) {
                os.write(data);
              }
            } else {
              ex.sendResponseHeaders(405, -1);
            }
          });
      server.start();
      String baseUrl =
          "http://127.0.0.1:" + server.getAddress().getPort() + "/api/packages/instanto-io/generic";

      ReportPublisher publisher =
          new ReportPublisher(
              baseUrl,
              HttpClient.newBuilder()
                  .connectTimeout(Duration.ofSeconds(10))
                  .followRedirects(HttpClient.Redirect.NEVER)
                  .build());
      ReportPublisher.PublishedReport published =
          publisher.publishReport(
              tempDir,
              "downstream-compatibility",
              List.of("reports/*.log"),
              "ci-user",
              "ci-token",
              "instanto-io/sarto",
              "99",
              "1");
      assertNotNull(published);
      assertTrue(published.url().endsWith("/downstream-compatibility.tar.gz"));
      assertEquals(1, published.fileCount());
      assertTrue(uploadedBytes.get() != null && uploadedBytes.get().length > 0);

      // Verify sha256Hex helper
      Path sample = reportsDir.resolve("build.log");
      String expectedSha =
          HexFormat.of()
              .formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(sample)));
      assertEquals(expectedSha, QualityReviewCollector.sha256Hex(sample));

      // Symlinks must be rejected
      Path symlinkDir = tempDir.resolve("with-symlink");
      Files.createDirectories(symlinkDir);
      Files.createSymbolicLink(symlinkDir.resolve("link.log"), sample);
      assertThrows(
          IllegalArgumentException.class,
          () -> publisher.collectFiles(tempDir, List.of("with-symlink")));
    } finally {
      server.stop(0);
    }
  }
}

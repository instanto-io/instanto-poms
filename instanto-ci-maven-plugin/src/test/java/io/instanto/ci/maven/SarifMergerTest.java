package io.instanto.ci.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SarifMergerTest {

  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void mergesMultipleModuleSarifRunsAndRemapsRuleIndices(@TempDir Path tempDir) throws Exception {
    Path modA = tempDir.resolve("mod-a/target/spotbugs");
    Path modB = tempDir.resolve("mod-b/target/spotbugs");
    Files.createDirectories(modA);
    Files.createDirectories(modB);

    Files.writeString(
        modA.resolve("spotbugsXml.sarif"),
        """
        {
          "version": "2.1.0",
          "runs": [{
            "tool": {
              "driver": {
                "name": "SpotBugs",
                "rules": [
                  {"id": "NP_ALWAYS_NULL", "shortDescription": {"text": "Null pointer"}}
                ]
              }
            },
            "results": [
              {
                "ruleId": "NP_ALWAYS_NULL",
                "ruleIndex": 0,
                "level": "error",
                "message": {"text": "Null deref in A"},
                "locations": [{
                  "physicalLocation": {
                    "artifactLocation": {"uri": "io/instanto/A.java"},
                    "region": {"startLine": 42}
                  }
                }]
              }
            ]
          }]
        }
        """);

    Files.writeString(
        modB.resolve("spotbugsXml.sarif"),
        """
        {
          "version": "2.1.0",
          "runs": [{
            "tool": {
              "driver": {
                "name": "SpotBugs",
                "rules": [
                  {"id": "DLS_DEAD_LOCAL_STORE", "shortDescription": {"text": "Dead store"}},
                  {"id": "NP_ALWAYS_NULL", "shortDescription": {"text": "Null pointer"}}
                ]
              }
            },
            "results": [
              {
                "ruleId": "DLS_DEAD_LOCAL_STORE",
                "ruleIndex": 0,
                "level": "warning",
                "message": {"text": "Dead store in B"}
              },
              {
                "ruleId": "NP_ALWAYS_NULL",
                "ruleIndex": 1,
                "level": "error",
                "message": {"text": "Null deref in B"}
              }
            ]
          }]
        }
        """);

    Path output = tempDir.resolve("target/spotbugs-reports/spotbugs.sarif");
    SarifMerger merger = new SarifMerger();
    int mergedRuns = merger.mergeSpotBugsSarif(tempDir, output);

    assertEquals(2, mergedRuns);
    assertTrue(Files.isRegularFile(output));

    JsonNode merged = mapper.readTree(Files.readString(output));
    JsonNode run = merged.path("runs").get(0);
    assertEquals(2, run.path("tool").path("driver").path("rules").size());
    assertEquals(0, run.path("results").get(0).path("ruleIndex").asInt());
    assertEquals(1, run.path("results").get(1).path("ruleIndex").asInt());
    assertEquals(0, run.path("results").get(2).path("ruleIndex").asInt());

    SarifMerger.SpotBugsSummary summary = merger.collectSummary(tempDir);
    assertEquals(3, summary.total());
    assertEquals(2, summary.high());
    assertEquals(1, summary.medium());
    assertEquals(0, summary.low());
  }

  @Test
  void collectsSpotBugsXmlWhenSarifAbsentAndExecutesMojo(@TempDir Path tempDir) throws Exception {
    Path mod = tempDir.resolve("mod-a/target");
    Files.createDirectories(mod);
    Files.writeString(
        mod.resolve("spotbugsXml.xml"),
        """
        <BugCollection>
          <BugInstance type="NP_ALWAYS_NULL" priority="1" category="CORRECTNESS" abbrev="NP">
            <LongMessage>Null pointer dereference</LongMessage>
            <Class classname="io.instanto.Sample"/>
            <SourceLine classname="io.instanto.Sample" start="19"/>
          </BugInstance>
          <BugInstance type="URF_UNREAD_FIELD" priority="3" category="PERFORMANCE" abbrev="UrF">
            <ShortMessage>Unread field</ShortMessage>
          </BugInstance>
        </BugCollection>
        """);

    SarifMerger merger = new SarifMerger();
    SarifMerger.SpotBugsSummary summary = merger.collectSummary(tempDir);
    assertEquals(1, summary.files());
    assertEquals(2, summary.total());
    assertEquals(1, summary.high());
    assertEquals(0, summary.medium());
    assertEquals(1, summary.low());

    MergeSarifMojo mojo = new MergeSarifMojo();
    setField(mojo, "rootDir", tempDir.toFile());
    setField(mojo, "sarifOutput", "target/spotbugs-reports/spotbugs.sarif");
    mojo.execute();
  }

  static void setField(Object target, String name, Object value) throws Exception {
    var field = target.getClass().getDeclaredField(name);
    field.setAccessible(true);
    field.set(target, value);
  }
}

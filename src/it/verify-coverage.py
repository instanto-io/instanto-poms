#!/usr/bin/env python3
"""Exercise inherited coverage with real forked tests, without publishing parents."""

from pathlib import Path
import os
import subprocess
import tempfile
import xml.etree.ElementTree as ET
from xml.sax.saxutils import escape


ROOT = Path(__file__).resolve().parents[2]
OUTPUT = ROOT / "target" / "coverage-inheritance"
OUTPUT.mkdir(parents=True, exist_ok=True)


def write(path, content):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")


def pom(body):
    return '<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>' + body + '</project>'


def run(fixture, name, *options):
    log = OUTPUT / (name + ".log")
    with log.open("w") as output:
        result = subprocess.run(
            ["mvn", "-B", "-ntp", "-f", str(fixture / "pom.xml"),
             "-Dmaven.repo.local=" + str(fixture / ".m2"),
             "clean", "test", "-Dspotless.skip=true", "-Dspotbugs.skip=true", *options],
            stdout=output, stderr=subprocess.STDOUT,
        )
    if result.returncode:
        raise RuntimeError(f"{name} failed: see {log}")
    for module in ("plain", "custom"):
        target = fixture / module / "target"
        tests = ET.parse(target / "surefire-reports/TEST-example.SampleTest.xml").getroot()
        assert tests.get("tests") == "1" and tests.get("failures") == "0"
        assert tests.get("errors") == "0" and tests.get("skipped") == "0"
        report = target / "site/jacoco/jacoco.xml"
        if "-Djacoco.skip=true" in options:
            assert not report.exists(), f"{module}: skipped coverage produced a report"
            assert not (target / "jacoco.exec").exists()
        else:
            counter = ET.parse(report).getroot().find("counter[@type='LINE']")
            assert counter is not None and int(counter.get("covered")) > 0
            assert int(counter.get("missed")) > 0, "Unexecuted code should remain uncovered"
    print(f"PASS: {name}", flush=True)


with tempfile.TemporaryDirectory(prefix="instanto-coverage-") as directory:
    fixture = Path(directory).resolve()
    write(fixture / "pom.xml", pom(f"""
      <parent>
        <groupId>io.instanto</groupId><artifactId>instanto-org-pom</artifactId>
        <version>0.1.0-SNAPSHOT</version>
        <relativePath>{escape(os.path.relpath(ROOT / 'pom.xml', fixture))}</relativePath>
      </parent>
      <artifactId>coverage-inheritance-fixture</artifactId><packaging>pom</packaging>
      <modules><module>plain</module><module>custom</module></modules>
      <properties><argLine>-Dcoverage.fixture=from-pom</argLine>
        <fixture.expected>from-pom</fixture.expected></properties>
      <dependencies><dependency><groupId>junit</groupId><artifactId>junit</artifactId>
        <version>4.13.2</version><scope>test</scope></dependency></dependencies>
      <build><pluginManagement><plugins><plugin>
        <groupId>org.apache.maven.plugins</groupId><artifactId>maven-surefire-plugin</artifactId>
        <configuration><systemPropertyVariables>
          <fixture.expected>${{fixture.expected}}</fixture.expected>
        </systemPropertyVariables></configuration>
      </plugin></plugins></pluginManagement></build>
    """))
    for module in ("plain", "custom"):
        config = "" if module == "plain" else """
          <build><plugins><plugin>
            <groupId>org.apache.maven.plugins</groupId><artifactId>maven-surefire-plugin</artifactId>
            <configuration>
              <argLine>@{instanto.coverage.argLine} ${argLine} -Dcustom.option=retained</argLine>
            </configuration>
          </plugin></plugins></build>
        """
        write(fixture / module / "pom.xml", pom(f"""
          <parent><groupId>io.instanto</groupId><artifactId>coverage-inheritance-fixture</artifactId>
            <version>0.1.0-SNAPSHOT</version></parent>
          <artifactId>{module}</artifactId>{config}
        """))
        write(fixture / module / "src/main/java/example/Sample.java", """
package example;
public class Sample {
  public int covered() { return 42; }
  public int uncovered() { return 0; }
}
""")
        custom_assertion = ('org.junit.Assert.assertEquals("retained", System.getProperty("custom.option"));'
                            if module == "custom" else "")
        write(fixture / module / "src/test/java/example/SampleTest.java", """
package example;
public class SampleTest {
  @org.junit.Test public void executesInForkWithOptions() {
    org.junit.Assert.assertEquals(42, new Sample().covered());
    org.junit.Assert.assertEquals(System.getProperty("fixture.expected"), System.getProperty("coverage.fixture"));
    %s
  }
}
""" % custom_assertion)
    run(fixture, "inherited-options")
    run(fixture, "command-line-options", "-DargLine=-Dcoverage.fixture=from-cli", "-Dfixture.expected=from-cli")
    run(fixture, "coverage-skipped", "-Djacoco.skip=true")

print(f"Coverage inheritance checks passed. Logs: {OUTPUT}")

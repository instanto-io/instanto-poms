#!/usr/bin/env python3
"""Prove that a consumer uses the freshly installed upstream snapshot, including API breaks."""
from pathlib import Path
import tempfile

from downstream import run_maven

POM = '''<project xmlns="http://maven.apache.org/POM/4.0.0">
<modelVersion>4.0.0</modelVersion><groupId>ci.downstream</groupId>
<artifactId>{name}</artifactId><version>1-SNAPSHOT</version>
<properties><maven.compiler.release>21</maven.compiler.release></properties>
{dependencies}
<build><plugins><plugin><groupId>org.apache.maven.plugins</groupId>
<artifactId>maven-compiler-plugin</artifactId><version>3.15.0</version>
</plugin></plugins></build></project>'''


def main():
    with tempfile.TemporaryDirectory(prefix='downstream-maven-') as directory:
        workspace = Path(directory)
        settings = workspace / 'settings.xml'
        settings.write_text('<settings/>')
        repository = workspace / 'm2'
        for name in ('upstream', 'consumer'):
            source = workspace / name / 'src/main/java/fixture'
            source.mkdir(parents=True)
            dependency = '' if name == 'upstream' else '''<dependencies><dependency>
<groupId>ci.downstream</groupId><artifactId>upstream</artifactId>
<version>1-SNAPSHOT</version></dependency></dependencies>'''
            (workspace / name / 'pom.xml').write_text(POM.format(name=name, dependencies=dependency))
        upstream = workspace / 'upstream/src/main/java/fixture/Library.java'
        upstream.write_text('package fixture; public class Library { public static int value() { return 7; } }')
        (workspace / 'consumer/src/main/java/fixture/Consumer.java').write_text(
            'package fixture; public class Consumer { public int value() { return Library.value(); } }')

        def build(name, label):
            return run_maven(workspace / name, ['--no-snapshot-updates', 'clean', 'install', '-DskipTests'],
                             repository, settings, workspace / (label + '.log'))

        if not build('upstream', 'upstream-compatible') or not build('consumer', 'consumer-compatible'):
            raise SystemExit('Compatible dependency fixture failed')
        upstream.write_text('package fixture; public class Library { public static int renamed() { return 7; } }')
        if not build('upstream', 'upstream-breaking'):
            raise SystemExit('Changed upstream fixture failed')
        if build('consumer', 'consumer-breaking'):
            raise SystemExit('Consumer unexpectedly accepted a removed upstream method')
        print('PASS: fresh upstream artifacts reach consumers and incompatible API changes fail the build.')


if __name__ == '__main__':
    main()

# Instanto Maven parents

Shared Maven parents for Instanto libraries and applications. Each POM
documents its own role, what it provides and what it requires; read the POM for
detail rather than this file.

| Parent | Role |
|---|---|
| `io.instanto:instanto-org-pom` | The build contract for every Instanto project: Java 21, pinned plugin versions, Spotless, SpotBugs, source and Javadoc archives, release profiles. |
| `io.instanto:instanto-teavm-pom` | Adds TeaVM for browser projects: one TeaVM version, managed dependencies, and source maps with the Java files needed to debug them. |

## Use a parent

```xml
<parent>
  <groupId>io.instanto</groupId>
  <artifactId>instanto-org-pom</artifactId>
  <version>0.1.0-SNAPSHOT</version>
  <relativePath/>
</parent>
```

Name `instanto-teavm-pom` instead when the project targets the browser.

Both are published as snapshots to packages.instanto.io. Maven looks for a parent
before it reads the parent's own repositories, so the registry has to be known
beforehand. The shared CI workflow writes it into Maven settings; on a developer
machine, add the same to `~/.m2/settings.xml`, with a Forgejo account that can read
the organisation's packages:

```xml
<settings>
  <servers>
    <server>
      <id>forgejo-instanto</id>
      <username>…</username>
      <password>…</password>
    </server>
  </servers>
  <profiles>
    <profile>
      <id>instanto-snapshots</id>
      <repositories>
        <repository>
          <id>forgejo-instanto</id>
          <url>https://packages.instanto.io/api/packages/instanto-io/maven</url>
          <releases><enabled>false</enabled></releases>
          <snapshots><enabled>true</enabled></snapshots>
        </repository>
      </repositories>
      <pluginRepositories>
        <pluginRepository>
          <id>forgejo-instanto</id>
          <url>https://packages.instanto.io/api/packages/instanto-io/maven</url>
          <releases><enabled>false</enabled></releases>
          <snapshots><enabled>true</enabled></snapshots>
        </pluginRepository>
      </pluginRepositories>
    </profile>
  </profiles>
  <activeProfiles>
    <activeProfile>instanto-snapshots</activeProfile>
  </activeProfiles>
</settings>
```

After that, every project below these parents inherits the snapshot destination and
the repository to resolve from, and declares neither.

A project that cannot change its parent can import `instanto-teavm-pom` into
`dependencyManagement` with `<type>pom</type><scope>import</scope>`. An import
carries the managed versions only — not the `teavm.version` property, and not
the plugin pin.

## What a project supplies itself

The parent identifies its own source repository. Each child repository overrides
that SCM address and, for fixed versions, supplies its release repository.
Snapshots need nothing: a child inherits packages.instanto.io as its destination.
This repository's CI publishes the parent snapshots after a successful build;
`mvn deploy` does the same by hand.

## Hierarchy

```text
instanto-org-pom                   Every Instanto project
└── instanto-teavm-pom             Projects that compile to the browser
    ├── sarto-org-pom              → the Sarto POM suite
    ├── sarto-library-pom
    ├── sarto-verrai-base-pom
    └── drone-poc-components
```

## Shared CI

`.github/workflows/maven-build.yml` is a reusable workflow that checks out the
repository, writes Maven settings for packages.instanto.io and any GitHub
Packages repositories the build declares, and runs Maven. Jobs run on the
self-hosted pool unless a caller says otherwise.

```yaml
jobs:
  verify:
    uses: instanto-io/instanto-poms/.github/workflows/maven-build.yml@main
    with:
      name: Verify modules
    secrets: inherit
```

`secrets: inherit` passes `FORGEJO_PACKAGE_USER` and `FORGEJO_PACKAGE_TOKEN`, which
publish and read snapshots on packages.instanto.io, and `PACKAGES_TOKEN` for GitHub
Packages.

A build that needs a platform the self-hosted pool does not have — the JavaFX
artifacts for Windows and arm64, for instance — passes hosted labels instead:

```yaml
  javafx:
    strategy:
      matrix:
        include:
          - target: windows-x64
            labels: '["windows-2022"]'
          - target: linux-arm64
            labels: '["ubuntu-24.04-arm"]'
    uses: instanto-io/instanto-poms/.github/workflows/maven-build.yml@main
    with:
      runner-labels: ${{ matrix.labels }}
      maven-args: -P javafx-${{ matrix.target }}
```

Inputs: `runner-labels`, `java-version`, `parents`, `goals`, `maven-args`,
`timeout-minutes`, `name`, `artifact-name`, `artifact-path`. Published parents resolve from packages.instanto.io, so
`parents` is empty by default; use it only to test a parent that is not published
yet:

```yaml
      parents: |
        instanto-io/sarto-poms sarto-library-pom/pom.xml
```

To hand a build output to a later workflow, such as a showcase deployed to GitHub
Pages, name it and give its path. The upload happens only when the build passes:

```yaml
    with:
      goals: clean verify
      artifact-name: showcase-pages
      artifact-path: showcase/target/site
```

## Publishing

Snapshots go to packages.instanto.io. A fixed version goes to the repository's
release destination and to Maven Central in two separate deploys.
Release candidates use fixed versions such as `0.7.0-rc.1` and follow the
same verification and publication checks as a final release. The
[release process](RELEASING.md) covers candidate branches and version changes.

```bash
mvn deploy                                  # snapshot to packages.instanto.io
mvn -P release deploy                       # fixed version to the release repository
mvn -P release,sign-release,central deploy  # fixed version to Central
```

`release` rejects snapshot versions, parents and dependencies and checks the
Java and Maven versions. `central` selects Central's deploy process in place of
Maven's normal deploy, and needs `sign-release` because Central requires
signatures. Credentials and signing keys stay in the local build environment;
the `central` server id names them in Maven settings.

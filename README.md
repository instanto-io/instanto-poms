# Instanto Maven parents

Shared Maven parents for Instanto libraries and applications. Each POM
documents its own role, what it provides and what it requires; read the POM for
detail rather than this file.

| Parent | Role |
|---|---|
| `io.instanto:instanto-org-pom` | The build contract for every Instanto project: Java 21, pinned plugin versions, Spotless, SpotBugs, source and Javadoc archives, and publication defaults. |
| `io.instanto:instanto-teavm-pom` | Adds TeaVM for projects that compile to JavaScript or WebAssembly: the `teavm.version` property, managed `org.teavm` versions, and the `teavm-maven-plugin` pin, which makes every TeaVM compile write a source map and copy the Java sources it maps to. |

## Use a parent

```xml
<parent>
  <groupId>io.instanto</groupId>
  <artifactId>instanto-org-pom</artifactId>
  <version>0.1.0-SNAPSHOT</version>
  <relativePath/>
</parent>
```

Name `instanto-teavm-pom` instead when the project targets the browser. Maven
resolves either from the local cache or the Forgejo repository declared in the
consuming project's root POM:

```xml
<repositories>
  <repository>
    <id>forgejo</id>
    <url>https://packages.instanto.io/api/packages/instanto-io/maven</url>
    <releases><enabled>false</enabled></releases>
    <snapshots><enabled>true</enabled></snapshots>
  </repository>
</repositories>
```

A project that cannot change its parent can import `instanto-teavm-pom` into
`dependencyManagement` with `<type>pom</type><scope>import</scope>`. An import
carries the managed versions only — not the `teavm.version` property, and not
the plugin pin.

## What a project supplies itself

Each repository declares its own SCM metadata. Snapshot distribution and
dependency resolution use `packages.instanto.io`; release versions use Maven
Central through the Central Publisher Portal.

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
repository and runs Maven against the published parents and dependencies. Jobs
run on the self-hosted pool unless a caller says otherwise.

```yaml
jobs:
  verify:
    uses: instanto-io/instanto-poms/.github/workflows/maven-build.yml@main
    with:
      name: Verify modules
    secrets:
      PACKAGES_TOKEN: ${{ secrets.PACKAGES_TOKEN }}
```

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
`timeout-minutes`, `name`. `parents` is empty by default; use it only to test an
unpublished parent source tree explicitly.

```yaml
      parents: instanto-io/experimental-parent pom.xml
```

## Publishing

Release candidates use fixed versions such as `0.7.0-rc.1` and follow the
same verification and publication checks as final releases. See the
[release process](RELEASING.md) for snapshot and candidate branches.

Snapshots go to `packages.instanto.io`. Release versions go to Maven Central.

```bash
mvn deploy                                  # snapshot
mvn -P release,sign-release deploy          # release
```

`release` rejects snapshot versions, parents and dependencies and checks the
Java and Maven versions. The Central publishing extension is active by default
and needs `sign-release` because Central requires signatures. Credentials and
signing keys stay in the local build environment; the `central` server id names
them in Maven settings. Snapshot deployment uses the `forgejo` server id.

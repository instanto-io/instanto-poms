# Instanto Maven parents

Shared Maven parents for Instanto libraries and applications. Each POM
documents its own role, what it provides and what it requires; read the POM for
detail rather than this file.

| Parent | Role |
|---|---|
| `io.instanto:instanto-org-pom` | The build contract for every Instanto project: Java 21, pinned plugin versions, Spotless, SpotBugs, source and Javadoc archives, release profiles. |
| `io.instanto:instanto-teavm-pom` | Adds TeaVM for projects that compile to JavaScript or WebAssembly: the `teavm.version` property, managed `org.teavm` versions, and the `teavm-maven-plugin` pin. |

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
resolves either from the local cache or a configured package repository.

A project that cannot change its parent can import `instanto-teavm-pom` into
`dependencyManagement` with `<type>pom</type><scope>import</scope>`. An import
carries the managed versions only — not the `teavm.version` property, and not
the plugin pin.

## What a project supplies itself

Neither parent declares SCM, `distributionManagement` or `repositories`. Each
repository sets its own, so a project can be released from more than one place.

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
repository, installs the shared parents and runs Maven. Jobs run on the
self-hosted pool unless a caller says otherwise.

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
`settings`, `name`. A build needing a second parent, such as the Sarto library
parent, lists it:

```yaml
      parents: |
        instanto-io/instanto-poms pom.xml
        instanto-io/sarto-poms sarto-library-pom/pom.xml
```

## Publishing

Snapshots go to the organisation's GitHub Packages. A release goes to both
Maven Central and GitHub Packages.

```bash
mvn deploy                                  # snapshot
mvn -P release,sign-release,central deploy  # release
```

`release` rejects snapshot versions, parents and dependencies and checks the
Java and Maven versions. `central` adds Maven Central on top of the
repository's own `distributionManagement` destination, and needs `sign-release`
because Central requires signatures. Credentials and signing keys stay in the
local build environment; the `central` server id names them in Maven settings.

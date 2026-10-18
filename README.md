# Instanto Maven parents

Shared Maven parents for Instanto libraries and applications. Choose the
organisation parent for a Java project or the TeaVM parent for a browser
project. Each parent POM describes its managed plugins and properties.

| Parent | Role |
|---|---|
| `io.instanto:instanto-org-pom` | The build contract for every Instanto project: Java 21, pinned plugin versions, Spotless, SpotBugs, JaCoCo coverage, source and Javadoc archives, release profiles. |
| `io.instanto:instanto-teavm-pom` | Adds TeaVM for browser projects: one TeaVM version, managed dependencies, and source maps with the Java files needed to debug them. |

## Use a parent

Set the project's Maven parent to `instanto-org-pom`, or to
`instanto-teavm-pom` for browser projects. Use the published version selected
for your project. The TeaVM parent adds managed TeaVM dependencies, the
compiler plugin and source-map defaults.

A project that cannot change parent can import `instanto-teavm-pom` into
`dependencyManagement`. An import carries managed dependency versions only;
it does not carry the TeaVM property or plugin configuration.

Each child project supplies its own name and SCM address. See the
[parent POM](pom.xml) and [TeaVM parent](instanto-teavm-pom/pom.xml)
for the current settings.

## Test coverage

The organisation parent collects coverage from forked Surefire test JVMs.
`mvn clean test` writes each tested module's report to
`target/site/jacoco/jacoco.xml`, which Sonar discovers automatically; the HTML
report is alongside it in `index.html`. Reporting runs after unit tests in the
`test` phase so consumers using `clean test` before Sonar analysis are covered.
No module-specific JaCoCo plugin declaration is needed.

Existing JVM options supplied through the `argLine` Maven property (including
`-DargLine=...`) are retained. A project overriding Surefire's **configuration**
`<argLine>` must include `@{instanto.coverage.argLine}` before its own options.
Use `-Djacoco.skip=true` to disable collection and reporting. Tests must run in
a forked JVM; `forkCount=0` cannot collect agent coverage.

This measures JVM code, including TeaVM compiler extensions. It does not measure
generated JavaScript or Wasm running in a browser, or separate integration-test
processes. Coverage percentages therefore do not describe browser test coverage.

Run `python3 src/it/verify-coverage.py` to check report generation, per-module
paths, custom JVM options and the coverage opt-out against the local parent.

## Shared CI

`.github/workflows/maven-build.yml` is a reusable workflow that checks out the
repository, sets up the required Maven repositories and runs the requested goals.
Jobs run on the self-hosted pool unless a caller says otherwise.

```yaml
jobs:
  verify:
    uses: instanto-io/instanto-poms/.github/workflows/maven-build.yml@main
    with:
      name: Verify modules
    secrets: inherit
```

Pass the credentials your build needs through GitHub Actions secrets.

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
`timeout-minutes`, `name`, `artifact-name`, `artifact-path`. `parents` is empty by default; use it only to test a parent that is not available to the job yet:

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

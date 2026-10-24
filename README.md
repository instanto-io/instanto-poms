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

## JavaScript assets

The TeaVM parent disables the compiler's minification. Keep TeaVM optimisation
enabled as appropriate; Terser handles the final JavaScript names and compression,
in a release build only. Every other build deploys readable JavaScript with its
source maps; build with `-Prelease` for a final release, which also requires
release versions. The managed `exec-maven-plugin` execution uses Terser
**5.51.2** through `npx`.
Build machines need Node.js and npm on `PATH`; the first invocation downloads
Terser into npm's cache. No global Terser installation is needed.

In a module that produces a JavaScript asset, set its output location and enable
the execution, then declare `exec-maven-plugin` **after** `teavm-maven-plugin`:

```xml
<properties>
  <instanto.terser.phase>prepare-package</instanto.terser.phase>
  <instanto.terser.directory>${project.build.directory}/teavm</instanto.terser.directory>
  <instanto.terser.file>application.js</instanto.terser.file>
</properties>
<!-- Inside build/plugins, after the TeaVM plugin and before asset staging: -->
<plugin>
  <groupId>org.codehaus.mojo</groupId>
  <artifactId>exec-maven-plugin</artifactId>
</plugin>
```

`mvn package` compiles the asset, minifies it in place, composes its existing
`.map` back to Java, and then packages or stages it. The compiler must run no
later than the selected Terser phase. Keep the inherited source-map generation
enabled: a missing or invalid input map fails the build rather than silently
losing Java debugging. Asset names, public exports and property names are retained.
ES modules are accepted, with conservative settings that do not rename top-level
bindings. These settings also work with classic scripts and UMD bundles.

Use `-Dinstanto.terser.skip=true` for an unminified development build. This does
not turn TeaVM minification back on. `instanto.terser.executable` can select an
explicit `npx` executable. Modules that do not opt in have no Terser lifecycle
execution. Additional assets need separate executions with their own arguments.

Run `python3 src/it/verify-terser.py` to compile real classic and ES-module
fixtures, execute their exports, check Java source maps and packaged contents,
and verify the skip option. The fixtures use stock TeaVM, including the
`@JSBody` name-clash reproducer.

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

### Downstream compatibility

After a successful default-branch `deploy`, the shared workflow builds the registered
transitive consumers in a separate **Downstream compatibility** job in the same run.
A failing consumer makes that run fail. A verification or publication failure prevents
the cascade from starting; publication already completed when downstream checks run.
These are source compatibility checks, not atomic publication gates.

The catalog in [`.github/downstream-projects.json`](.github/downstream-projects.json)
records repository dependencies, including parents, integration tests and optional
modules. It includes `cstainton/ports`, whose reactor contains `gsdk-widgets-emul`.
Update the catalog when adding a repository or changing an inter-repository dependency;
this is an explicit registry, not automatic discovery of every GitHub repository.
An unregistered upstream currently has no downstream work. New consumers must be added
before their compatibility can be checked. Conservative optional-module edges can
schedule more builds than a single default Maven reactor strictly needs.

Each run pins consumer default-branch commits before building and records their SHAs
in the job summary and job log. Per-build logs, JSON results and the summary are
published as a `downstream-compatibility.tar.gz` generic package on
packages.instanto.io, with a download link and SHA-256 in the job summary. The parent
coverage and Terser verification logs use the same storage. These diagnostics no
longer consume GitHub Actions artifact storage; the optional `artifact-name` output
for workflows that exchange build outputs through GitHub remains unchanged.

Packages use `ci-<repository-owner>-<repository-name>-<report-name>` and version
`<run-id>-<run-attempt>`, so reruns do not overwrite previous evidence. The publisher
downloads the uploaded archive and verifies its checksum before reporting success.
Forgejo package retention is managed on the registry; GitHub artifact retention does
not apply. Existing GitHub artifacts are not deleted by this workflow.

The cascade installs the exact upstream checkout into a fresh job-local Maven repository, then
builds consumers with remote snapshot updates disabled. Consumers run `clean install`,
including tests; catalog `checks` add the browser profiles for scene, graphics and model.
No consumer artifacts are deployed and no other workflow is dispatched, so there are
no recursive runs or cross-repository dispatch credentials.

Repository dependency cycles are grouped: each member is installed once with test
execution skipped, then every member is built again with tests against the resulting
artifacts. A bootstrap failure is a real failure; cycles that cannot bootstrap from
published snapshots need their module/repository boundary or compatibility repaired.
A failed group blocks its downstream groups while independent groups continue.
`blocked` never counts as a pass.

The caller's `PACKAGES_TOKEN` needs read access to private consumer repositories
(including `cstainton/ports`) and packages. Its job token alone normally cannot read
other private repositories. Existing Forgejo/Nexus read credentials are reused for
Maven. Publishing reports additionally requires `FORGEJO_PACKAGE_USER` and
`FORGEJO_PACKAGE_TOKEN` (or `FORGEJO_PACKAGES_TOKEN`) with package-write access to the
`instanto-io` owner. A read-only token cannot publish reports. Registry failures remain
visible failures, and the revision/result table stays in the job log and summary.
Missing access fails visibly. The job does not need dispatch or repository write access.
Runners must support the consumers' browser and native build requirements.

To check a project manually, run **Downstream compatibility** in this repository and
enter its catalog name. Manual runs also test the upstream itself. For a specialised
publication that should not repeat the cascade (such as a platform matrix), pass
`downstream-checks: false` and keep it enabled on the main publication. Verification
jobs never trigger it. The default cascade timeout is four hours.

Run the planner tests with:

```sh
python3 -m unittest discover -s .github/scripts -p 'test_*.py' -v
python3 .github/scripts/downstream.py --catalog .github/downstream-projects.json --upstream sarto-scene --plan-only
python3 .github/scripts/verify_downstream_maven.py
```

The Maven fixture installs a working upstream snapshot, verifies a consumer, then
removes the upstream method and proves that the same consumer fails compilation.

### SonarQube diagnostics

Run analysis once on the full reactor. Partial browser-profile jobs should pass
`sonar: false` to avoid replacing the project's complete analysis and coverage.
A failed gate remains a failure; the diagnostic step prints its conditions when the
token has access. An analysis-only token may not have permission to read results.
In that case provide optional `SONAR_READ_TOKEN` with Browse permission, or inspect
the project dashboard. Neither the token nor its value is written to build artifacts.

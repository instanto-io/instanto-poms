# Instanto Maven parents

`io.instanto:instanto-org-pom` supplies shared build and release settings for
Instanto projects. Each library keeps its own version, dependencies, tests and
publication destination.

The parent supplies build configuration. Libraries declare their own runtime
dependencies and dependency management.

## Use the parent

Use Java 21 and Maven 3.9.2 or later. To build against a local parent checkout,
install it first:

```sh
mvn install
```

Then add the parent to a library's root POM:

```xml
<parent>
  <groupId>io.instanto</groupId>
  <artifactId>instanto-org-pom</artifactId>
  <version>0.1.0-SNAPSHOT</version>
  <relativePath/>
</parent>

<artifactId>my-library</artifactId>
<version>0.3.0-SNAPSHOT</version>
```

These are two separate versions. The parent version selects the build settings;
the library version determines what is published and the name of its release
branch. Child modules can share their library's root version.

Use a snapshot parent for development and a released parent when releasing a
library. Maven resolves the parent from the local cache or a configured package
repository.

## Parent hierarchy

```text
instanto-org-pom
├── sarto-org-pom                  Sarto dependency and framework settings
│   └── sarto-java-pom
│       ├── sarto-framework-pom
│       ├── sarto-entity-model-pom → sarto-service-pom
│       └── sarto-app-pom → browser, Cloudflare and Tomcat parents
├── sarto-library-pom              Independent Sarto library verification
└── Independent library roots     Widgets, compatibility libraries and test tools
    └── Library modules
```

The Sarto POM suite supplies framework settings. Each repository's POM supplies
its source repository URLs and publication destination.

## Shared settings

The parent manages the standard compiler, resources, test, install, deploy,
source, Javadoc, release and signing plugins. A project can override a setting
when it needs a different Java target or plugin version.

The `release` profile rejects snapshot project versions, parents and
dependencies, checks the Java and Maven versions, and attaches source and
Javadoc archives. Add `sign-release` when the publication destination requires
signatures. Credentials and signing keys stay in the local build environment.

Spotless formatting is available by declaring the managed plugin:

```xml
<build>
  <plugins>
    <plugin>
      <groupId>com.diffplug.spotless</groupId>
      <artifactId>spotless-maven-plugin</artifactId>
    </plugin>
  </plugins>
</build>
```

This formats owned Java sources during `validate`. Generated and upstream
source directories are excluded. Projects with other source layouts can
override the include and exclude patterns.

## Release a library

Releases run from the **Instanto-io repository checkout** on local infrastructure.
Transfer refined source from the private working repository before starting a
release.

See [the release procedure](RELEASING.md) for the version branch, publication and
subsequent snapshot update.

# Releasing from Instanto-io

A release starts from tested source already transferred into the relevant
Instanto-io repository. Run the build on local infrastructure. GitHub holds the
source and published artifacts.

The root POM owns the release version. For example, a project at
`0.7.0-SNAPSHOT` releases as `0.7.0` on branch `0.7.0`. Its Instanto parent can
have a different version. A patch, minor or major increment determines the
next development version.

## Prepare the checkout

Use a clean checkout of the Instanto-io repository's `main` branch. Check its
Git remote, the POM's SCM URLs and its Maven publication destination all identify
the intended Instanto repository.

Release shared dependencies and this parent first. Replace snapshot dependency,
parent and build-plugin references with their released versions. Run the
repository's full verification, including its browser checks where applicable,
and commit the prepared inputs on main. Library-specific build profiles remain
part of that repository's verification commands.

The common `release` profile checks project versions, parents and dependencies.
Build-plugin references also need review; Maven's dependency rule does not
check plugin versions. The parent does not inspect Git remotes or enforce the
branch name at deployment time: the checkout checks above are part of the
release procedure.

## Create the release branch

For projects that declare literal versions in their root and module POMs:

```sh
git switch -c 0.7.0
mvn -B versions:set -DnewVersion=0.7.0
```

Review and commit the version changes on this branch. Main stays at its existing
snapshot until publication succeeds.

Sarto projects that use `${revision}` keep that property in their own repository.
On the new branch, use this command instead of `versions:set`:

```sh
mvn -B versions:set-property -Dproperty=revision -DnewVersion=0.7.0
```

Keep the flatten plugin enabled so published POMs contain resolved
versions. The Instanto parent's version is pinned separately and must not be
changed as part of this operation.

Git creates the branch and the Maven Versions plugin updates the POMs. The
organisation parent supplies the plugin version and settings.

## Verify and publish the branch

Run the complete verification again from the release branch, with the
repository's required profiles. The base Maven command is:

```sh
mvn -Prelease clean verify
```

Check that the build has not changed tracked source files. Commit any necessary
corrections and repeat verification before publishing. Push this branch to the
Instanto repository so the exact source being published is available:

```sh
git push origin 0.7.0
mvn -Prelease deploy
```

Use `-Prelease,sign-release` for destinations that require signing. A destination
that stages uploads for approval must complete that publication step before
proceeding.
Never overwrite artifacts that have already been published under a release
version; make another release for subsequent changes.

## Advance main after successful publication

For a minor increment following `0.7.0`:

```sh
git switch main
mvn -B versions:set -DnewVersion=0.8.0-SNAPSHOT
git diff
```

For a project using `${revision}`, use
`mvn -B versions:set-property -Dproperty=revision -DnewVersion=0.8.0-SNAPSHOT`
instead.

Review and commit the changed POMs, then push main to the Instanto repository.
For a patch increment use `0.7.1-SNAPSHOT`; for a major increment use
`1.0.0-SNAPSHOT`. Child modules follow their own repository's version, not the
organisation parent's version.

Retain the published release branch and its commits. Future transfers of
development source into Instanto must preserve these branches and must not
replace the new main version with an older working-repository snapshot. Bring
the chosen development version back into the private working repository as a
separate synchronisation step.

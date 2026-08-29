# CLAUDE.md

## Branches, and no pull requests

This repository does not use pull requests. Commit the work and push it: no review gate. This
overrides the PR-based workflow in the global `~/.claude/CLAUDE.md`: there is no `master`, and
nothing goes through a PR.

Two branches are long-lived:

- `develop` is where the work goes. It builds against the compiler's `develop`, a `0.1.0-SNAPSHOT`
  published nowhere, so the compiler is installed from a clone first.
- `main` is the state that builds against the latest released compiler, and is the default branch.
  It moves when a release is pinned: on `develop`, run `bin/set-version.sh <release>`, then
  `git merge --no-ff develop` into `main`. The four places that carry the version conflict on every
  such merge; set-version.sh puts all four right in one pass.

Working on a topic branch off `develop` is fine when it keeps a series of commits together; it is
merged back locally with `git merge --no-ff`. The exception is Dependabot, which opens PRs of its
own against `main`; those are merged on GitHub and reach `develop` by a `main` -> `develop` merge.

CI runs on pushes to `main` and on PRs against it, so it checks the released version only. It
cannot run on `develop`: the SNAPSHOT it needs resolves nowhere until the compiler is built from
source. A commit on `develop` is verified locally instead, with the commands under Building.

## Language

Everything that stays in the repository — README, commit messages, comments — is written in
English. Domain models in `.sou` use the language of the domain they model: Japanese identifiers in
`businesstrip`, `joboffer`, `member`, `ordering` and `account`, English in the CRM/SFA modules.

## Building

On `develop`, `souther.version` in the root `pom.xml` is a `-SNAPSHOT` published nowhere but `~/.m2`,
so the compiler is installed from its own repository first (`mvn -f souther/pom.xml install
-DskipTests`). On `main` it is the latest release on Maven Central and nothing has to be installed.
Either way the version is written in four places; `bin/set-version.sh <version>` moves all of them
and `bin/check-version-consistency.sh` fails if they disagree. The build plugins carry a version of
their own (`souther.plugin.version`), which moves on its own schedule and has no script: the same
check reads it.

`.sou` is compiled by `souther-maven-plugin`, and by `souther-gradle-plugin` in `issuetracker`. Only
`account` still generates through the javac annotation processor, which `souther-clj` drives, and it
is the one module left with a `package-info.java` written to give javac a source.

```sh
mvn verify                                              # every Maven module: generate → compile → smoke test
cd account      && clojure -X:gen && clojure -X:test    # Clojure, outside the Maven reactor
cd issuetracker && ./gradlew build                      # Kotlin, its own Gradle build
```

`ordering` and `issuetracker` start Spring Boot against H2, so their first build needs network to
fetch the starters; after that `mvn -o` and `./gradlew --offline` work.

The `souther` CLI, which `shippingfee/README.md` runs, is not on Central: it comes out of the same
clone, as `souther-cli/target/souther`.

Java boundary conventions for code written against generated Souther types are in
`docs/java-boundary-conventions.md`.

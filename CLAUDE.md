# CLAUDE.md

## No pull requests here

This repository does not use pull requests. Commit the work to `main` and push it. No feature
branch is required, no PR, no review gate. This overrides the PR-based workflow in the global
`~/.claude/CLAUDE.md`: there is no `develop` and no `master`, and `main` is the only long-lived
branch.

Working on a topic branch is fine when it helps to keep a series of commits together, but it is
merged into `main` locally (`git merge --no-ff`) rather than through a PR. The exception is
Dependabot, which opens PRs of its own; those are merged on GitHub.

## Language

Everything that stays in the repository — README, commit messages, comments — is written in
English. Domain models in `.sou` use the language of the domain they model: Japanese identifiers in
`businesstrip`, `joboffer`, `member`, `ordering` and `account`, English in the CRM/SFA modules.

## Building

The examples build against `souther.version` in the root `pom.xml`, `0.1.0-rc5`, released to Maven
Central — nothing has to be installed first. The version is written in four places; `bin/set-version.sh
<version>` moves all of them and `bin/check-version-consistency.sh` fails if they disagree.

```sh
mvn verify                                              # every Maven module: generate → compile → smoke test
cd account      && clojure -X:gen && clojure -X:test    # Clojure, outside the Maven reactor
cd issuetracker && ./gradlew build                      # Kotlin, its own Gradle build
```

`ordering` and `issuetracker` start Spring Boot against H2, so their first build needs network to
fetch the starters; after that `mvn -o` and `./gradlew --offline` work.

The `souther` CLI, which `shippingfee/README.md` runs, is not on Central: take it from a compiler
release or build it from a clone.

Java boundary conventions for code written against generated Souther types are in
`docs/java-boundary-conventions.md`.

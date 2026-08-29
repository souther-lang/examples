#!/usr/bin/env bash
# Point every example at a Souther version. The version is declared in four places here: the
# souther.version property of the root pom, the Clojure account example's deps.edn, the Kotlin
# issuetracker example's build.gradle.kts, and the two build-plugin snippets in the README. A
# bump has to touch all of them; doing it by hand is
# how the rc3 bump first missed this build back when it lived inside the compiler repository.
# Run from anywhere:
#
#   bin/set-version.sh 0.1.0-SNAPSHOT
#
# Then verify with bin/check-version-consistency.sh (CI runs the same check).
set -euo pipefail

if [ $# -ne 1 ]; then
  echo "usage: bin/set-version.sh <version>" >&2
  exit 1
fi
version="$1"
root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"

# 1. The Maven build: the version is a property, since the examples do not inherit from the compiler
#    reactor and cannot use ${project.version}.
mvn -q versions:set-property -Dproperty=souther.version -DnewVersion="$version" \
    -DgenerateBackupPoms=false -f pom.xml

# 2. Clojure account example (deps.edn, outside Maven): the runtime dep and the :gen compiler dep.
perl -pi -e "s{(org\.souther-lang/souther-(?:runtime|compiler) \{:mvn/version )\"[^\"]*\"}{\${1}\"$version\"}g" \
    account/deps.edn

# 3. Kotlin issuetracker example (build.gradle.kts, its own Gradle build outside Maven): what the
#    souther-gradle-plugin extension is told to compile with.
perl -pi -e "s{(^\s*southerVersion = )\"[^\"]*\"}{\${1}\"$version\"}" \
    issuetracker/build.gradle.kts

# 4. The README's two plugin snippets: <southerVersion> for Maven and southerVersion = for Gradle.
#    The closing tag is part of the match, because the README also names `<southerVersion>` in prose
#    and a pattern that stops at the next `<` eats the sentence it is written in. (${1} delimits the
#    backreference so a version starting with a digit is not read as $1<digit>.)
perl -pi -e "s{(<southerVersion>)[^<]*(</southerVersion>)}{\${1}$version\${2}}g;
             s{(^\s*southerVersion = )\"[^\"]*\"}{\${1}\"$version\"}" \
    README.md

echo "Set Souther version to $version (pom, account, issuetracker, README)."

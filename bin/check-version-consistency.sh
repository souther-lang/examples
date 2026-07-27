#!/usr/bin/env bash
# Fail if the Souther version is not identical everywhere it is declared. The souther.version
# property of the root pom is the authority; the Clojure account example's deps.edn, the Kotlin
# issuetracker example's build.gradle.kts, and the annotation-processor snippet in the README must
# match it. This is the guardrail against a bump
# landing in some files and being forgotten in others. CI runs this; run it locally after
# bin/set-version.sh.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"

# Authority: the souther.version property of the root pom, which is what the build resolves.
core="$(sed -n 's#.*<souther.version>\(.*\)</souther.version>.*#\1#p' pom.xml | head -1)"
if [ -z "$core" ]; then
  echo "could not read <souther.version> from pom.xml" >&2
  exit 2
fi

fail=0
check() { # <label> <found>
  if [ "$2" != "$core" ]; then
    echo "version drift: $1 = '$2' (souther.version is '$core')" >&2
    fail=1
  fi
}

check "account deps.edn souther-runtime" \
  "$(sed -n 's#.*souther-runtime {:mvn/version "\([^"]*\)".*#\1#p' account/deps.edn | head -1)"
check "account deps.edn souther-compiler" \
  "$(sed -n 's#.*souther-compiler {:mvn/version "\([^"]*\)".*#\1#p' account/deps.edn | head -1)"
check "issuetracker build.gradle.kts southerVersion" \
  "$(sed -n 's#.*val southerVersion = "\([^"]*\)".*#\1#p' issuetracker/build.gradle.kts | head -1)"
check "README souther-compiler snippet" \
  "$(sed -n 's#.*souther-compiler:\([^<]*\)</path>.*#\1#p' README.md | head -1)"

if [ "$fail" -ne 0 ]; then
  echo "Souther version is inconsistent. Reconcile with: bin/set-version.sh $core" >&2
  exit 1
fi
echo "Souther version consistent everywhere: $core"

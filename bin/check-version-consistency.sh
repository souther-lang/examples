#!/usr/bin/env bash
# Fail if a version declared in more than one place is not identical everywhere. Three are: the
# Souther compiler, the build plugins, and Raoh. The root pom's properties are the authority, because
# that is what the Maven build resolves; the builds outside Maven — the Clojure account example's
# deps.edn and the Kotlin issuetracker example's build.gradle.kts — and the snippets in the README
# have to agree with it. This is the guardrail against a bump landing in some files and being
# forgotten in others. CI runs this; run it locally after bin/set-version.sh.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"

property() { # <name>
  sed -n "s#.*<$1>\(.*\)</$1>.*#\1#p" pom.xml | head -1
}

core="$(property souther.version)"
plugin="$(property souther.plugin.version)"
raoh="$(property raoh.version)"
for pair in "souther.version:$core" "souther.plugin.version:$plugin" "raoh.version:$raoh"; do
  if [ -z "${pair#*:}" ]; then
    echo "could not read <${pair%%:*}> from pom.xml" >&2
    exit 2
  fi
done

fail_souther=0
fail_plugin=0
fail_raoh=0
check() { # <label> <found> <expected> <which>
  if [ "$2" != "$3" ]; then
    echo "version drift: $1 = '$2' ($4 is '$3')" >&2
    case "$4" in
      souther.version)        fail_souther=1 ;;
      souther.plugin.version) fail_plugin=1 ;;
      *)                      fail_raoh=1 ;;
    esac
  fi
}

check "account deps.edn souther-runtime" \
  "$(sed -n 's#.*souther-runtime {:mvn/version "\([^"]*\)".*#\1#p' account/deps.edn | head -1)" \
  "$core" souther.version
check "account deps.edn souther-compiler" \
  "$(sed -n 's#.*souther-compiler {:mvn/version "\([^"]*\)".*#\1#p' account/deps.edn | head -1)" \
  "$core" souther.version
check "issuetracker build.gradle.kts southerVersion" \
  "$(sed -n 's#^ *southerVersion = "\([^"]*\)".*#\1#p' issuetracker/build.gradle.kts | head -1)" \
  "$core" souther.version
check "README souther-maven-plugin snippet southerVersion" \
  "$(sed -n 's#.*<southerVersion>\([^<]*\)</southerVersion>.*#\1#p' README.md | head -1)" \
  "$core" souther.version
check "README souther-gradle-plugin snippet southerVersion" \
  "$(sed -n 's#^ *southerVersion = "\([^"]*\)".*#\1#p' README.md | head -1)" \
  "$core" souther.version

check "issuetracker build.gradle.kts plugin id" \
  "$(sed -n 's#.*id("org.souther-lang.souther") version "\([^"]*\)".*#\1#p' issuetracker/build.gradle.kts | head -1)" \
  "$plugin" souther.plugin.version
check "README souther-maven-plugin snippet version" \
  "$(sed -n '/souther-maven-plugin/{n;s#.*<version>\([^<]*\)</version>.*#\1#p;}' README.md | head -1)" \
  "$plugin" souther.plugin.version
check "README souther-gradle-plugin snippet version" \
  "$(sed -n 's#.*id("org.souther-lang.souther") version "\([^"]*\)".*#\1#p' README.md | head -1)" \
  "$plugin" souther.plugin.version

check "account deps.edn raoh" \
  "$(sed -n 's#.*net.unit8.raoh/raoh {:mvn/version "\([^"]*\)".*#\1#p' account/deps.edn | head -1)" \
  "$raoh" raoh.version
check "issuetracker build.gradle.kts raohVersion" \
  "$(sed -n 's#.*val raohVersion = "\([^"]*\)".*#\1#p' issuetracker/build.gradle.kts | head -1)" \
  "$raoh" raoh.version

if [ "$fail_souther" -ne 0 ]; then
  echo "Souther version is inconsistent. Reconcile with: bin/set-version.sh $core" >&2
fi
if [ "$fail_plugin" -ne 0 ]; then
  echo "Build-plugin version is inconsistent. <souther.plugin.version> in pom.xml is the authority;" >&2
  echo "the files that have to match it are issuetracker/build.gradle.kts and the README snippets." >&2
  echo "There is no set-version.sh for it: a plugin release is not a Souther release, and the two" >&2
  echo "move on their own schedules." >&2
fi
if [ "$fail_raoh" -ne 0 ]; then
  echo "Raoh version is inconsistent. <raoh.version> in pom.xml is the authority; the files that" >&2
  echo "have to match it are account/deps.edn and issuetracker/build.gradle.kts. There is no" >&2
  echo "set-version.sh for Raoh: a Raoh bump is not a Souther bump, and two files edited by hand is" >&2
  echo "less to keep right than a second script." >&2
fi
[ "$fail_souther" -eq 0 ] && [ "$fail_plugin" -eq 0 ] && [ "$fail_raoh" -eq 0 ] || exit 1

echo "Souther version consistent everywhere: $core"
echo "Build-plugin version consistent everywhere: $plugin"
echo "Raoh version consistent everywhere: $raoh"

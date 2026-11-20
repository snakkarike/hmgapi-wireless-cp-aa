#!/bin/sh
# Host test: the projection manager's rules (Choice) for two Android phones, two iPhones, one of
# each, switching, a start that never finishes, a restart; and DiPO's copy of Choice the same.
set -e
cd "$(dirname "$0")"
if [ -z "${ANDROID_JAR:-}" ]; then
    ANDROID_JAR=$(find "${ANDROID_HOME:?set ANDROID_HOME or ANDROID_JAR}/platforms" -name android.jar | sort | tail -1)
fi
[ -f "$ANDROID_JAR" ] || { echo "no Android platform android.jar"; exit 1; }
CP=$(mktemp -d); trap 'rm -rf "$CP"' EXIT
javac --release 8 -Xlint:-options -nowarn -cp "$ANDROID_JAR" -d "$CP" $(find stubs test -name '*.java') ../../src/hmgapi/mods/wireless/Choice.java
java -cp "$CP:$ANDROID_JAR" hmgapi.mods.wireless.ChoiceTest
body() { grep -v -e '^package ' -e 'class Choice {' -e '^ \*' -e '^/\*\*' "$1"; }
if [ "$(body ../../src/hmgapi/mods/wireless/Choice.java)" = "$(body ../../src/hmgapi/mods/wireless/dipo/Choice.java)" ]; then
    echo "ok    DiPO's copy of Choice is the same"
else
    echo "FAIL  DiPO's copy of Choice differs from hmgapi.mods.wireless.Choice"; exit 1
fi

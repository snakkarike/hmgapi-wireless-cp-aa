#!/bin/sh
# Off-car test of the mod's native recipes in memory (hmgapi.engine.MemRecipe): the Android
# Auto recipes on the real stock libraries against the file-patched builds, and the CarPlay
# recipes against lpatch's own result on the file. Needs the private stock trees
# (~/hmg-private); skipped without them. Runs with the HMG-API mod SDK (HMGAPI_SDK).
set -e
R=$(cd "$(dirname "$0")/../../recipes" && pwd)
SDK=$(cd "${HMGAPI_SDK:?set HMGAPI_SDK to the unpacked HMG-API mod SDK}" && pwd)
CP=$(mktemp -d); trap 'rm -rf "$CP"' EXIT
H=$HOME/hmg-private
# The file-patched AA builds keep the parser's old string cut; the recipes have it fixed
# (segment +0x17a, tools/aa-text). Compare against a copy of each build with the same fix.
fixcut() {
    python3 - "$1" "$2" <<'PY'
import sys
old = bytes.fromhex('6029024628bf602108460139535c03f0c003802b00d070470029f5d108467047')
new = bytes.fromhex('602901d8084670476021435c03f0c003802b01d10139f8d10846704700bf00bf')
b = open(sys.argv[1], 'rb').read()
assert b.count(old) == 1, sys.argv[1] + ': the old string cut not found exactly once'
open(sys.argv[2], 'wb').write(b.replace(old, new))
PY
}
set --
if [ -f $H/stock/ev6/lib/libautoreceiver_infobank.so ]; then
    fixcut $H/builds/2_tbt/files/libautoreceiver_infobank.so $CP/aa-48ee793a.so
    set -- "$@" $H/stock/ev6/lib/libautoreceiver_infobank.so $CP/aa-48ee793a.so $R/aa-tbt-48ee793a.recipe
fi
if [ -f $H/stock/neev-can-260206/lib/libautoreceiver_infobank.so ]; then
    fixcut $H/builds-neev_ca/2_tbt/files_neev_ca/libautoreceiver_infobank.so $CP/aa-a0bea1c4.so
    set -- "$@" $H/stock/neev-can-260206/lib/libautoreceiver_infobank.so $CP/aa-a0bea1c4.so $R/aa-tbt-a0bea1c4.recipe
fi
# the CarPlay recipes (lpatch recipes, used as they are): against lpatch's own result on the file
L=$SDK/bin/lpatch-host
for x in core:carplay-core-wifi audio:carplay-audio-formats utils:carplay-utils-converter; do
    lib=$H/stock/ev6/lib/libCarPlay$(echo ${x%%:*} | sed 's/^./\U&/').so; rc=$R/${x#*:}.recipe
    [ -f "$lib" ] && [ -x $L ] || continue
    $L apply $rc "$lib" "$CP/${x#*:}.so" >/dev/null && set -- "$@" "$lib" "$CP/${x#*:}.so" $rc
done
# US 260903's libCarPlayAudio (2b748bf8) is the Korean Ioniq 5's, byte for byte; the audio
# recipe's regions are unchanged there, so it applies as on 260507 (Core and Utils are native)
lib=$H/stock/neev_kr/lib/libCarPlayAudio.so; rc=$R/carplay-audio-formats.recipe
if [ -f "$lib" ] && [ -x $L ] && $L apply $rc "$lib" "$CP/audio-260903.so" >/dev/null; then
    set -- "$@" "$lib" "$CP/audio-260903.so" $rc
fi
# its libCarPlayCore (ef49c09b) gets only the frame wait, from its own recipe, listed first;
# on 260507's library that recipe must not match (carplay-core-wifi runs there)
lib=$H/stock/neev_kr/lib/libCarPlayCore.so; rc=$R/carplay-core-frames-ef49c09b.recipe
if [ -f "$lib" ] && [ -x $L ] && $L apply $rc "$lib" "$CP/core-260903.so" >/dev/null; then
    set -- "$@" "$lib" "$CP/core-260903.so" $rc
fi
lib=$H/stock/ev6/lib/libCarPlayCore.so
if [ -f "$lib" ] && [ -x $L ]; then
    if $L apply $rc "$lib" "$CP/core-260507.so" >/dev/null 2>&1 || [ -e "$CP/core-260507.so" ]; then
        echo "FAIL  carplay-core-frames-ef49c09b: matched 260507's libCarPlayCore"; exit 1
    fi
    echo "ok    carplay-core-frames-ef49c09b: no match on 260507's libCarPlayCore, nothing written"
fi
[ $# -gt 0 ] || { echo "no stock libraries in $H (skipped)"; exit 0; }
exec java -cp "$SDK/lib/hmgapi-engine.jar:$SDK/test/hmgapi-engine-test.jar" hmgapi.engine.MemRecipeTest "$@"

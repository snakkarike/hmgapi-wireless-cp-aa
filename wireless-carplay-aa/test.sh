#!/bin/sh
# Off-car test of the Wireless mod on the patch engine, against a car's real
# stock files (kept privately: STOCK, default ~/hmg-private/stock/ev6 with app/,
# lib/, onebin/, permissions/). Every part on: each rebuilt file must match the
# set's expect list (the engine checks), props go live, nothing touches
# build.prop; restore must give back every stock byte.
# Runs with the HMG-API mod SDK (HMGAPI_SDK, set by the mod workflow).
#   HMGAPI_SDK=<sdk> wireless-carplay-aa/test.sh [stock dir] [product] [onebin]
set -e
M=$(cd "$(dirname "$0")" && pwd)
S=${1:-$HOME/hmg-private/stock/ev6}
export HMGAPI_PRODUCT=${2:-CVEV.USA.0000.V015.012.260507}
export HMGAPI_ONEBIN=${3:-cvev_us}
PRODUCT=$HMGAPI_PRODUCT ONEBIN=$HMGAPI_ONEBIN
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
R=$T/root
. "${HMGAPI_SDK:?set HMGAPI_SDK to the unpacked HMG-API mod SDK}/test/lib.sh"
# its NEMESIS package refuses the NEMESIS packages that change what it changes (mod.json "nemesis_conflicts")
d=$(HMGAPI_NEMESIS_CONFLICTS=/dev/null python3 "$HMGAPI_SDK/bin/nemesis-meta.py" depends hmgapi.wireless-carplay-aa 'hmgapi@>=1.0' --mod "$M/mod.json")
if [ "$d" = "hmgapi@>=1.0,!wireless.projection,!projection.tbt,!enhanced.projection" ]; then echo "ok    NEMESIS package refuses wireless.projection, projection.tbt and enhanced.projection"
else echo "FAIL  NEMESIS package depends: [$d]"; exit 1; fi
python3 "$M/tools/ioniq6/integration.py"
[ -d "$S/app" ] || { echo "no stock tree at $S (skipped)"; exit 0; }

mkdir -p $R/system/app $R/system/lib $R/system/etc/permissions $R/system/onebin/$ONEBIN $R/sys/bus/sdio/devices/mmc1:0001:1 $R/data/hmgapi/mods $R/data/hmgapi/settings
cp $S/app/* $R/system/app/; cp $S/lib/* $R/system/lib/
cp $S/onebin/$ONEBIN/featurexml.cfg $R/system/onebin/$ONEBIN/
[ -d $S/permissions ] && cp $S/permissions/* $R/system/etc/permissions/
printf 'ro.build.product=%s\npersist.ium.support=none\n' "$PRODUCT" > $R/system/build.prop
printf 'ro.build.product=%s\nro.board.platform=tcc803x\nro.build.version.release=4.4.2\nro.mobis.onebindir=%s\n' "$PRODUCT" "$ONEBIN" > $R/props.txt
install_mod "$M"
BEFORE=$(tree)

eng plan
check "plan has no problems" 0 $rc
[ $rc = 0 ] || echo "$out"
eng apply
check "apply (every part)" 0 $rc
[ $rc = 0 ] || echo "$out"
check "turn-by-turn GPM is hooks now: GPM untouched" "" "$(echo "$out" | grep HKMC_GPM)"
check "AA and CarPlay libraries are patched in memory now: files untouched" "" "$(echo "$out" | grep 'libautoreceiver\|libCarPlay')"
has "audio converter added" "/system/lib/libleafiaaac.so" "$out"
has "CarPlay identity serializer added" "/system/lib/libcarplayidentity.so" "$out"
has "CarPlay log switch added" "/system/lib/libcarplaylog.so" "$out"
check "menu fix is a hook now: BtSettings untouched" "" "$(echo "$out" | grep HKMC_BtSettings)"
has "props live" "prop/persist.ium.support=carplay" "$(cat $R/settings.txt)"
has "bridge props live" "prop/persist.iap2.iden.wireless=t" "$(cat $R/settings.txt)"
check "build.prop untouched" "persist.ium.support=none" "$(grep persist.ium $R/system/build.prop)"
has "feature flag: CarPlay wireless file" '<feature name="com.hkmc.software.connectivity.carplay_wireless" />' "$(cat $R/system/etc/permissions/com.hkmc.software.connectivity.carplay_wireless.xml 2>/dev/null)"
# AA wireless: a build whose featurexml.cfg drops the stock file (the EV6) gets that line
# commented; a build with neither the line nor the file (the older Hyundai models) gets
# HMG-API's own file, as for CarPlay
if grep -q 'connectivity.androidauto_wireless.xml' $S/onebin/$ONEBIN/featurexml.cfg; then
    has "featurexml: AA wireless line commented" "#-/system/etc/permissions/com.hkmc.software.connectivity.androidauto_wireless.xml" "$(cat $R/system/onebin/$ONEBIN/featurexml.cfg)"
else
    has "feature flag: AA wireless file" '<feature name="com.hkmc.software.connectivity.androidauto_wireless" />' "$(cat $R/system/etc/permissions/com.hkmc.software.connectivity.androidauto_wireless.xml 2>/dev/null)"
fi
# the hotspot feature, asked of HMG-API ("wifi": {"projection": true}): this test's /system has
# no stock file, so HMG-API's own goes in (on a car with the stock file, only its line changes)
has "hotspot feature: HMG-API's file" '<feature name="com.hkmc.software.wifihotspot" />' "$(cat $R/system/etc/permissions/com.hkmc.software.wifihotspot.xml 2>/dev/null)"
check "featurexml: hotspot line not live" "" "$(grep -x -- '-/system/etc/permissions/com.hkmc.software.wifihotspot.xml' $R/system/onebin/$ONEBIN/featurexml.cfg || true)"
check "turn-by-turn and bridge are hooks now: DiPO untouched" "" "$(echo "$out" | grep HKMC_DiPO)"

printf '{"bridge": false}\n' > $R/data/hmgapi/settings/wireless-carplay-aa.json
eng apply
check "settings change rebuilds" 0 $rc
[ $rc = 0 ] || echo "$out"
check "no Wi-Fi client feature" no "$([ -f $R/system/etc/permissions/android.hardware.wifi.xml ] && echo yes || echo no)"
has "bridge off: its props back" "prop/persist.ium.support=carplay" "$(cat $R/settings.txt)"
check "bridge off: iap2 prop gone" "" "$(grep iap2.iden $R/settings.txt || true)"

eng restore
check "restore exit" 0 $rc
check "/system byte-identical to stock" "$BEFORE" "$(tree)"
check "props back to stock" "" "$(cat $R/settings.txt)"
exit $fail

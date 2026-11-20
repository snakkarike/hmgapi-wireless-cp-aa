#!/usr/bin/env python3
"""CN7NPE checks with the pinned SDK engine and synthetic stock; no car access."""
import fnmatch
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
from types import SimpleNamespace
import zipfile


MOD = Path(__file__).resolve().parents[2]
sys.dont_write_bytecode = True
sys.path.insert(0, str(MOD / "tools/ioniq6"))
from integration import Firewall

SDK = Path(os.environ["HMGAPI_SDK"]).resolve()
PRODUCT = "CN7NPE.USA.0000.V016.010.260903"
ONEBIN = "cn7npe_us"
FEATURES = (
    "com.hkmc.software.wifihotspot",
    "com.hkmc.software.connectivity.androidauto_wireless",
    "com.hkmc.software.connectivity.carplay_wireless",
)
STOCK = "# Synthetic fixture\n" + "".join(
    "-/system/etc/permissions/" + f + ".xml\n" for f in FEATURES
) + "+/system/etc/permissions/example.untouched.xml\n"


def write(path, text):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text)


def snapshot(root):
    return {str(p.relative_to(root)): p.read_bytes()
            for p in root.rglob("*") if p.is_file()}


def engine(root, command, product=PRODUCT, expected=0):
    env = dict(os.environ, HMGAPI_ROOT=str(root), HMGAPI_HOST="1",
               HMGAPI_PRODUCT=product, HMGAPI_ONEBIN=ONEBIN,
               HMGAPI_LPATCH=str(SDK / "bin/lpatch-host"))
    for key in ("HMGAPI_TEST_ANY_CAR", "HMGAPI_PROPS"):
        env.pop(key, None)
    result = subprocess.run(["java", "-cp", str(SDK / "lib/hmgapi-engine.jar"),
                             "hmgapi.engine.Engine", command], env=env,
                            text=True, capture_output=True, timeout=30)
    assert result.returncode == expected, result.stdout + result.stderr
    return result.stdout


with tempfile.TemporaryDirectory(prefix="cn7npe-wireless-test-") as temporary:
    work = Path(temporary)
    root = work / "unit"
    data = root / "data/hmgapi"
    shutil.copytree(MOD, data / "mods/wireless-carplay-aa",
                    ignore=shutil.ignore_patterns("tools", "test.sh"))
    cfg = root / "system/onebin" / ONEBIN / "featurexml.cfg"
    write(cfg, STOCK)
    write(root / "system/build.prop", "ro.build.product=" + PRODUCT + "\n")
    write(root / "props.txt", "ro.board.platform=tcc803x\nro.build.version.release=4.4.2\n")
    # A stock hotspot file, unlike the engine's generated file, must stay byte-identical.
    hotspot = root / "system/etc/permissions" / (FEATURES[0] + ".xml")
    write(hotspot, '<permissions><!-- synthetic stock --><feature name="'
          + FEATURES[0] + '" /></permissions>\n')
    (root / "sys/bus/sdio/devices/mmc1:0001:1").mkdir(parents=True)
    before = snapshot(root / "system")
    engine(root, "plan")
    assert snapshot(root / "system") == before
    engine(root, "apply")
    assert cfg.read_text() == STOCK.replace("-/system", "#-/system")
    assert hotspot.read_bytes() == before[str(hotspot.relative_to(root / "system"))]
    for feature in FEATURES[1:]:
        assert ('<feature name="' + feature + '" />') in (
            root / "system/etc/permissions" / (feature + ".xml")).read_text()
    settings = (root / "settings.txt").read_text()
    assert "prop/persist.ium.support=carplay" in settings
    assert "prop/persist.iap2.iden.wireless=t" in settings
    for library in ("libleafiaaac.so", "libcarplayidentity.so"):
        assert (root / "system/lib" / library).read_bytes() == (MOD / "files" / library).read_bytes()
    rules = data / "state/net.rules"
    assert not rules.exists() or not rules.read_text().strip()
    applied = snapshot(root / "system")
    engine(root, "apply")
    assert snapshot(root / "system") == applied

    # These are the stock wired CarPlay port groups from the owner's firewall report.
    ports = {4: ["5000:5001", "5020", "5353", "6000:6003", "6011", "6020", "6030", "7000"],
             6: ["5000:5001", "5020", "5353", "6000:6003", "6011", "6020", "6030", "6031", "7000"]}
    expected = {}
    for family, source in ((4, "192.168.0.0/24"), (6, "fe80::/10")):
        expected[str(family) + "/filter/HMGAPI_IN"] = {
            f"-i wlan0 -s {source} -p {proto} --{direction} {port} -j ACCEPT"
            for proto in ("tcp", "udp") for direction in ("sport", "dport")
            for port in ports[family]}
    expected["6/filter/HMGAPI_IN"].add("-i wlan0 -s fe80::/10 -p icmpv6 -j ACCEPT")
    firewall = Firewall(SimpleNamespace(root=root, data=data))
    write(data / "settings/wireless-carplay-aa.json", '{"cp_firewall": true}\n')
    plan = engine(root, "plan")
    assert "left out" not in plan and "allow_network" not in plan
    engine(root, "apply")
    assert "40515" not in rules.read_text()
    firewall.once()
    active = firewall.read()
    for family in ("4", "6"):
        assert active[family + "/filter/INPUT"][0] == "-j HMGAPI_IN"
        assert set(active[family + "/filter/HMGAPI_IN"]) == expected[family + "/filter/HMGAPI_IN"]
    for key, value in firewall.before.items():
        assert active[key] == (["-j HMGAPI_IN"] + value if key.endswith("/INPUT") else value)
    assert set(active) == set(firewall.before) | set(expected)
    firewall.once()
    assert firewall.read() == active
    firewall.ipt("-I", "INPUT", "1", "-i", "wlan0", "-j", "DROP", version=6)
    firewall.once()
    assert firewall.read()["6/filter/INPUT"][0] == "-j HMGAPI_IN"
    firewall.ipt("-D", "INPUT", "-i", "wlan0", "-j", "DROP", version=6)
    write(data / "settings/wireless-carplay-aa.json", '{"cp_firewall": false}\n')
    engine(root, "apply")
    firewall.once()
    firewall.restored()
    write(data / "settings/wireless-carplay-aa.json", '{"cp_firewall": true}\n')
    engine(root, "apply")
    firewall.once()
    (data / "mods/wireless-carplay-aa/.disabled").touch()
    engine(root, "apply")
    firewall.once()
    firewall.restored()
    assert snapshot(root / "system") == before
    (data / "mods/wireless-carplay-aa/.disabled").unlink()
    write(data / "settings/wireless-carplay-aa.json", '{"cp_firewall": false}\n')
    engine(root, "apply")
    print("ok    CN7NPE: opt-in CarPlay rules are local, restored after resets and removed on toggle/disable")

    engine(root, "restore")
    assert snapshot(root / "system") == before
    assert not (root / "settings.txt").read_text().strip()
    print("ok    CN7NPE: plan, apply, repeat apply and byte-identical restore")

    for product in (PRODUCT.replace("USA", "CAN"), PRODUCT.replace("260903", "260507"),
                    PRODUCT.replace("V016.010", "V016.011"), PRODUCT.replace("CN7NPE", "CN7N")):
        output = engine(root, "apply", product=product, expected=3)
        assert "nothing changed" in output
        assert snapshot(root / "system") == before
    print("ok    CN7NPE: other regions, firmware, builds and models refused before writes")

    (root / "sys/bus/sdio/devices/mmc1:0001:1").rmdir()
    marker = data / "public/wifi-chip"
    if marker.exists():
        marker.unlink()
    engine(root, "apply", expected=3)
    assert snapshot(root / "system") == before
    (root / "sys/bus/sdio/devices/mmc1:0001:1").mkdir()
    converter = root / "system/lib/libleafiaaac.so"
    write(converter, "another package's library\n")
    occupied = snapshot(root / "system")
    output = engine(root, "apply", expected=3)
    assert "already on the head unit" in output
    assert snapshot(root / "system") == occupied
    print("ok    CN7NPE: missing Wi-Fi and foreign converter refused without writes")

    package = work / "fixture.zip"
    with zipfile.ZipFile(package, "w") as archive:
        archive.writestr("mod.json", (MOD / "mod.json").read_bytes())
        archive.writestr("META-HMGAPI/signature", "metadata fixture, not installable")
    subprocess.run(["sh", str(SDK / "bin/mknemesismod.sh"), str(package), str(work)], check=True,
                   capture_output=True, text=True)
    version = json.loads((MOD / "mod.json").read_text())["version"]
    with zipfile.ZipFile(work / ("wireless-carplay-aa-" + version + "-nemesis-package.zip")) as archive:
        fields = dict(line.split("=", 1) for line in archive.read("NEMESIS.data").decode().splitlines()
                      if "=" in line)
        # NEMESIS before 1.16.9 takes at most 255 characters, so the SDK widens a long list (here to
        # any model per region and firmware); HMG-API's own "system" check still limits the cars.
        assert len(fields["os"].strip('"')) <= 255
        patterns = fields["os"].strip('"').split(",")
        assert any(fnmatch.fnmatchcase(PRODUCT, pattern) for pattern in patterns)
        assert not any(fnmatch.fnmatchcase(PRODUCT.replace("260903", "260101"), p) for p in patterns)
        assert not any(fnmatch.fnmatchcase(PRODUCT.replace("USA", "KOR"), p) for p in patterns)
        assert archive.read("mod/package.zip") == package.read_bytes()
    print("ok    CN7NPE: SDK-generated NEMESIS compatibility and embedded package")

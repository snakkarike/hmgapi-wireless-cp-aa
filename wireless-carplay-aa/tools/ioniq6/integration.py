#!/usr/bin/env python3
"""Offline CEEV integration checks against the SDK's real patch engine.

Only a temporary fake head unit is changed. No device, network or root access is
used. Synthetic stock exercises transactions; --stock additionally checks a
private CEEV /system tree against the shipped hashes, without copying it here.
"""

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile


MOD = Path(__file__).resolve().parents[2]
PRODUCT = "CEEV.CAN.0000.V015.011.260206"
ONEBIN = "ceev_ca"
FEATURES = (
    "com.hkmc.software.wifihotspot",
    "com.hkmc.software.connectivity.androidauto_wireless",
    "com.hkmc.software.connectivity.carplay_wireless",
)
STOCK_CFG = ("# Synthetic fixture, not manufacturer stock\n"
             + "".join("-/system/etc/permissions/" + f + ".xml\n" for f in FEATURES)
             + "+/system/etc/permissions/example.untouched.xml\n")
PATCHED_CFG = ("# Synthetic fixture, not manufacturer stock\n"
               + "".join("#-/system/etc/permissions/" + f + ".xml\n" for f in FEATURES)
               + "+/system/etc/permissions/example.untouched.xml\n")
AA_RULE = ("-i wlan0 -s 192.168.0.0/24 -d 192.168.0.0/24 "
           "-p tcp --dport 40515 -m state --state NEW -j ACCEPT")
AA_RULES = "chain 4 filter HMGAPI_IN 1\nipt 4 filter HMGAPI_IN " + AA_RULE + "\n"


def write(path, text):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


def require(ok, message, detail=""):
    if not ok:
        raise AssertionError(message + ("\n" + detail if detail else ""))
    print("ok    Ioniq 6: " + message)


def md5(data):
    return hashlib.md5(data).hexdigest()


def snapshot(root):
    return {str(p.relative_to(root)): p.read_bytes()
            for p in root.rglob("*") if p.is_file()}


def replace_cfg_hash(path, value):
    # Only the disposable mod's fixture hashes differ from the shipped mod.
    lines = [line for line in path.read_text().splitlines()
             if not line.split() or line.split()[0] != "featurexml.cfg"]
    write(path, "\n".join(lines + ["featurexml.cfg " + value]) + "\n")


class Unit:
    def __init__(self, root, sdk, product=PRODUCT, synthetic=True):
        self.root, self.sdk, self.product = root, sdk, product
        self.data = root / "data/hmgapi"
        self.mod = self.data / "mods/wireless-carplay-aa"
        shutil.copytree(MOD, self.mod, ignore=shutil.ignore_patterns("tools", "test.sh"))
        self.cfg = root / "system/onebin" / ONEBIN / "featurexml.cfg"
        write(self.cfg, STOCK_CFG)
        write(root / "system/build.prop", "ro.build.product=" + product + "\n")
        write(root / "props.txt", "ro.build.product=" + product + "\n"
              "ro.board.platform=tcc803x\nro.build.version.release=4.4.2\n"
              "ro.mobis.onebindir=" + ONEBIN + "\n")
        (root / "sys/bus/sdio/devices/mmc1:0001:1").mkdir(parents=True)
        (root / "system/etc/permissions").mkdir(parents=True)
        (self.data / "settings").mkdir(parents=True)
        if synthetic:
            for kind, text in (("stock", STOCK_CFG), ("expect", PATCHED_CFG)):
                replace_cfg_hash(self.mod / "patches/ceev_ca" / kind,
                                 md5(text.encode("utf-8")))

    def settings(self, **settings):
        write(self.data / "settings/wireless-carplay-aa.json", json.dumps(settings))

    def state(self, name):
        path = self.data / "state" / name
        return path.read_text() if path.exists() else ""

    def engine(self, command, expected=0):
        env = dict(os.environ, HMGAPI_ROOT=str(self.root), HMGAPI_HOST="1",
                   HMGAPI_PRODUCT=self.product, HMGAPI_ONEBIN=ONEBIN,
                   HMGAPI_LPATCH=str(self.sdk / "bin/lpatch-host"))
        # Do not inherit any caller's desktop map bypass or property overrides.
        env.pop("HMGAPI_TEST_ANY_CAR", None)
        env.pop("HMGAPI_PROPS", None)
        result = subprocess.run(["java", "-cp", str(self.sdk / "lib/hmgapi-engine.jar"),
                                 "hmgapi.engine.Engine", command], env=env,
                                text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                timeout=30)
        if result.returncode != expected:
            raise AssertionError(f"{self.product}: {command}: expected exit {expected}, "
                                 f"got {result.returncode}\n{result.stdout}")
        return result.stdout


class Firewall:
    """Run the actual generated keeper with only its OS command boundary faked."""

    def __init__(self, unit):
        self.unit = unit
        self.root = unit.root / "fake-firewall"
        self.bin = self.root / "bin"
        self.bin.mkdir(parents=True)
        for name in ("iptables", "ip6tables"):
            shutil.copyfile(Path(__file__).with_name("fake-iptables.py"), self.bin / name)
            (self.bin / name).chmod(0o755)
        write(self.bin / "getprop", "#!/bin/sh\necho stopped\n")
        # Unexpected route changes must be visible even if the keeper ignores
        # a command's failure. Never let it fall through to a host command.
        # HMG-API's keeper also clears its own home Wi-Fi rule (ip rule del priority 900; none here).
        write(self.bin / "ip", '#!/bin/sh\n[ "$*" = "rule del priority 900" ] && exit 2\n'
                               'echo "$*" >> "$FW/unexpected-ip"\nexit 99\n')
        for name in ("getprop", "ip"):
            (self.bin / name).chmod(0o755)
        write(self.root / "proc/net/ipv4/ip_forward", "0\n")
        self.env = dict(os.environ, FW=str(self.root),
                        HMGAPI_PROCSYS=str(self.root / "proc"),
                        PATH=str(self.bin) + os.pathsep + os.environ["PATH"])
        # Model the stock whitelist followed by DROP, plus unrelated IPv6.
        for args in (("-N", "WL_WLAN0_IN"),
                     ("-A", "WL_WLAN0_IN", "-p", "udp", "--dport", "53", "-j", "ACCEPT"),
                     ("-A", "WL_WLAN0_IN", "-j", "DROP"),
                     ("-N", "WLIST_IN"),
                     ("-A", "WLIST_IN", "-i", "wlan0", "-j", "WL_WLAN0_IN"),
                     ("-A", "INPUT", "-j", "WLIST_IN")):
            self.ipt(*args)
        self.ipt("-A", "INPUT", "-p", "icmpv6", "-j", "ACCEPT", version=6)
        self.before = self.read()

    def ipt(self, *args, version=4):
        result = subprocess.run([str(self.bin / ("iptables" if version == 4 else "ip6tables")),
                                 *args], env=self.env, text=True, capture_output=True, timeout=10)
        if result.returncode:
            raise AssertionError("firewall fixture command failed: " + " ".join(args)
                                 + "\n" + result.stderr)
        return result.stdout

    def read(self):
        """The rules, less HMG-API's own that keep mods off cellular (the HMGAPI_CELL chain, first in
        OUTPUT): the keeper adds those whatever this mod asks, so they aren't this mod's to check."""
        rules = json.loads((self.root / "rules.json").read_text())
        for family in ("4", "6"):
            rules.pop(family + "/filter/HMGAPI_CELL", None)
            out = family + "/filter/OUTPUT"
            if out in rules:
                rules[out] = [r for r in rules[out] if r != "-j HMGAPI_CELL"]
                if not rules[out] and out not in getattr(self, "before", {}):
                    del rules[out]
        return rules

    def once(self):
        result = subprocess.run(["bash", str(self.unit.data / "state/net.sh"), "once"],
                                env=self.env, text=True, capture_output=True, timeout=30)
        require(result.returncode == 0 and not result.stderr and "refused" not in result.stdout,
                "generated network keeper runs successfully", result.stdout + result.stderr)
        require(not (self.root / "unexpected-ip").exists()
                and (self.root / "proc/net/ipv4/ip_forward").read_text() == "0\n",
                "no route or forwarding changes")

    def aa_installed(self):
        actual = self.read()
        expected = json.loads(json.dumps(self.before))
        expected["4/filter/INPUT"].insert(0, "-j HMGAPI_IN")
        expected["4/filter/HMGAPI_IN"] = [AA_RULE]
        require(actual == expected,
                "one owned IPv4 AA rule precedes the intact stock whitelist; IPv6 is unchanged")

    def restored(self):
        require(self.read() == self.before, "firewall returns exactly to its original rules")
        require(not (self.unit.data / "state/net.applied").exists(), "no applied network state remains")


def transaction_and_network(temp, sdk):
    unit = Unit(temp / "accepted", sdk)
    before = snapshot(unit.root / "system")
    unit.engine("plan")
    require(snapshot(unit.root / "system") == before,
            "CAN 260206 plan passes both selectors without overrides and leaves /system unchanged")
    unit.engine("apply")
    require(unit.cfg.read_text() == PATCHED_CFG, "apply keeps unrelated feature lines intact")
    require("projection wireless-carplay-aa" in unit.state("wifi.txt").splitlines(),
            "apply requests the projection AP from HMG-API")
    require(any(service.startswith("hmgapi wifi root boot yes ")
                for service in unit.state("services.txt").splitlines()),
            "HMG-API starts its Wi-Fi keeper at boot")
    for feature in FEATURES[1:]:
        path = unit.root / "system/etc/permissions" / (feature + ".xml")
        require(f'<feature name="{feature}" />' in path.read_text(), "wireless feature is installed: " + feature)
    require("prop/persist.ium.support=carplay" in (unit.root / "settings.txt").read_text(),
            "projection property is applied")
    # The keeper itself runs anyway: HMG-API keeps mods off cellular (its own HMGAPI_CELL chain).
    require(not unit.state("net.rules"), "default settings create no firewall rule")

    unit.settings(aa_firewall=True)
    plan = unit.engine("plan")
    require("40515" in plan and "allow_network" not in plan,
            "opt-in plan needs no permission for other networks", plan)
    unit.engine("apply")
    require(unit.state("net.rules") == AA_RULES,
            "opt-in emits only hotspot IPv4 TCP 40515 NEW ingress", unit.state("net.rules"))
    fw = Firewall(unit)
    fw.once()
    fw.aa_installed()
    # The stock firewall may insert a dropping jump again after Wi-Fi restarts.
    fw.ipt("-I", "INPUT", "1", "-i", "wlan0", "-j", "DROP")
    fw.once()
    require(fw.read()["4/filter/INPUT"][0] == "-j HMGAPI_IN",
            "keeper repairs ordering when stock adds a drop before its jump")
    fw.ipt("-D", "INPUT", "-i", "wlan0", "-j", "DROP")

    unit.settings(aa_firewall=False)
    unit.engine("apply")
    require(not unit.state("net.rules") and "hmgapi net " in unit.state("services.txt"),
            "toggle off schedules removal of the previously applied rule")
    fw.once()
    fw.restored()
    unit.engine("publish-state")
    # The keeper stays (HMG-API's own cellular block); none of this mod's rules do.
    require(not unit.state("net.rules"), "no firewall rule left after cleanup")

    unit.settings(aa_firewall=True)
    unit.engine("apply")
    fw.once()
    fw.aa_installed()
    (unit.mod / ".disabled").touch()
    unit.engine("apply")
    fw.once()
    fw.restored()
    require(snapshot(unit.root / "system") == before, "disabling restores every original /system byte")
    require("projection wireless-carplay-aa" not in unit.state("wifi.txt").splitlines(),
            "disabling withdraws the projection AP request")
    require(not (unit.root / "settings.txt").read_text(), "disabling restores original properties")

    (unit.mod / ".disabled").unlink()
    unit.settings(aa_firewall=False)
    unit.engine("apply")
    unit.engine("restore")
    require(snapshot(unit.root / "system") == before, "explicit restore returns every original /system byte")
    require(not (unit.root / "settings.txt").read_text(), "explicit restore returns original properties")

    # Removing this mod's rule must leave another enabled mod's owned rule.
    unit.settings(aa_firewall=True)
    write(unit.data / "mods/other/mod.json", json.dumps({
        "id": "other", "version": "1.0.0", "network": {"rules": [{
            "chain": "input", "in": "hotspot", "ip": 4,
            "proto": "tcp", "ports": [7000], "action": "accept"}]}}))
    unit.engine("apply")
    fw.once()
    require(len(fw.read()["4/filter/HMGAPI_IN"]) == 2,
            "AA rule coexists with another mod's owned rule")
    (unit.mod / ".disabled").touch()
    unit.engine("apply")
    fw.once()
    require(fw.read()["4/filter/HMGAPI_IN"] == [
        "-i wlan0 -s 192.168.0.0/24 -p tcp --dport 7000 -j ACCEPT"],
        "disabling wireless preserves the other mod's rule")
    (unit.data / "mods/other/.disabled").touch()
    unit.engine("publish-state")
    fw.once()
    fw.restored()


def ceev_can_firmwares(temp, sdk):
    for product in (PRODUCT, "CEEV.USA.0000.V015.011.260206", "CEEV.CAN.0000.V016.010.260903"):
        unit = Unit(temp / product, sdk, product)
        before = snapshot(unit.root / "system")
        unit.engine("apply")
        require(unit.cfg.read_text() == PATCHED_CFG, "accepted and its feature lines patched: " + product)
        unit.engine("restore")
        require(snapshot(unit.root / "system") == before, "restore returns every original /system byte: " + product)


def existing_car_defaults(temp, sdk):
    for product in ("CVEV.USA.0000.V015.012.260507", "NEEV.USA.0000.V015.012.260507",
                    "NEEV23.USA.0000.V015.012.260507", "NEEV23SE.USA.0000.V015.012.260507",
                    "NEEV.USA.0000.V015.012.260903", "NEEV23.USA.0000.V015.012.260903",
                    "NEEV23SE.USA.0000.V015.012.260903", "NEEV.CAN.0000.V016.010.260903",
                    "CVEV.CAN.0000.V015.011.260206",
                    "NEEV.CAN.0000.V015.011.260206", "CEEV.USA.0000.V015.012.260507",
                    "NQ5HEV.USA.0000.V015.012.260507"):
        unit = Unit(temp / product, sdk, product)
        unit.engine("plan")
        unit.engine("publish-state")
        require(not unit.state("net.rules"),
                "existing model stays accepted with no default firewall rule: " + product)


def carplay_firewall(temp, sdk):
    unit = Unit(temp / "carplay-firewall", sdk, "CEEV.USA.0000.V015.012.260507")
    unit.settings(cp_firewall=True)
    plan = unit.engine("plan")
    require("allow_network" not in plan, "CarPlay opt-in plan needs no permission for other networks", plan)
    unit.engine("publish-state")
    rules = [line for line in unit.state("net.rules").splitlines() if line.startswith("ipt ")]
    v4 = [r for r in rules if r.startswith("ipt 4 filter HMGAPI_IN -i wlan0 -s 192.168.0.0/24 ")]
    v6 = [r for r in rules if r.startswith("ipt 6 filter HMGAPI_IN -i wlan0 -s fe80::/10 ")]
    require(len(rules) == 69 and len(v4) == 32 and len(v6) == 37,
            "CarPlay opt-in emits only hotspot ingress: 32 IPv4, 36 IPv6 and ICMPv6",
            unit.state("net.rules"))
    require(sum("--dport 7000 " in r for r in rules) == 4 and sum("--sport 7000 " in r for r in rules) == 4
            and sum("icmpv6" in r for r in rules) == 1,
            "CarPlay ports both ways over TCP and UDP, plus ICMPv6", unit.state("net.rules"))
    fw = Firewall(unit)
    fw.once()
    require(fw.read()["4/filter/INPUT"][0] == "-j HMGAPI_IN" and len(fw.read()["4/filter/HMGAPI_IN"]) == 32
            and len(fw.read()["6/filter/HMGAPI_IN"]) == 37,
            "CarPlay rules precede the stock whitelist")
    unit.settings(cp_firewall=False)
    unit.engine("publish-state")
    fw.once()
    fw.restored()


def rejected_units(temp, sdk):
    for product in ("CEEV.USA.0000.V015.011.250818", "CEEV.CAN.0000.V015.011.250818",
                    "CEEV.CAN.0000.V015.012.260206", "CEEV.CAN.0000.V015.011.260903"):
        unit = Unit(temp / product, sdk, product)
        before = snapshot(unit.root / "system")
        output = unit.engine("apply", expected=3)
        require("nothing changed" in output and snapshot(unit.root / "system") == before,
                "unsupported product is refused before writes: " + product, output)

    unit = Unit(temp / "different-stock", sdk)
    write(unit.cfg, STOCK_CFG + "# unexpected firmware content\n")
    before = snapshot(unit.root / "system")
    output = unit.engine("apply", expected=3)
    require("not the expected result" in output and snapshot(unit.root / "system") == before,
            "unexpected feature configuration fails its result hash without writing /system", output)


def check_private_stock(temp, sdk, stock):
    if (stock / "system").is_dir():
        stock = stock / "system"
    for line in (MOD / "patches/ceev_ca/stock").read_text().splitlines():
        if not line.strip() or line.startswith("#"):
            continue
        name, expected = line.split()
        candidates = [stock / "app" / name, stock / "lib" / name,
                      stock / "onebin" / ONEBIN / name]
        found = [p for p in candidates if p.is_file()]
        require(len(found) == 1, "private stock contains " + name,
                "Expected one file in app/, lib/, or onebin/ceev_ca/.")
        require(md5(found[0].read_bytes()) == expected,
                "private stock matches shipped fingerprint: " + name)
    unit = Unit(temp / "private-stock", sdk, synthetic=False)
    original = (stock / "onebin" / ONEBIN / "featurexml.cfg").read_bytes()
    unit.cfg.write_bytes(original)
    before = snapshot(unit.root / "system")
    unit.engine("plan")
    unit.engine("apply")
    require(unit.cfg.read_bytes() != original, "private feature configuration passes shipped result hash")
    unit.engine("restore")
    require(snapshot(unit.root / "system") == before,
            "private feature configuration restores byte for byte")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--stock", type=Path, default=os.environ.get("IONIQ6_STOCK"),
                        help="optional private CEEV /system tree (or IONIQ6_STOCK)")
    args = parser.parse_args()
    sdk = Path(os.environ["HMGAPI_SDK"]).resolve()
    require((sdk / "lib/hmgapi-engine.jar").is_file(), "SDK engine is available")
    with tempfile.TemporaryDirectory(prefix="ioniq6-wireless-test-") as temp:
        temp = Path(temp)
        transaction_and_network(temp, sdk)
        ceev_can_firmwares(temp, sdk)
        existing_car_defaults(temp, sdk)
        carplay_firewall(temp, sdk)
        rejected_units(temp, sdk)
        if args.stock:
            check_private_stock(temp, sdk, args.stock)
        else:
            print("skip  Ioniq 6: private stock hashes (use --stock or IONIQ6_STOCK)")


if __name__ == "__main__":
    main()

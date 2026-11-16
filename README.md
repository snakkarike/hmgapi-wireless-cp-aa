# Wireless CarPlay + Android Auto for HMG-API

A mod for [HMG-API](https://github.com/Open-HMG/HMG-API), the mod manager for Hyundai,
Kia and Genesis Gen5W head units. It turns on wireless CarPlay and wireless Android Auto
over the head unit's own hotspot, with turn-by-turn directions on the cluster and HUD.

Wireless Android Auto and Google Maps directions on the dash were confirmed on
Canadian firmware `CEEV.CAN.0000.V015.011.260206` on November 9, 2026.

It moved here from HMG-API's `mods/wireless-carplay-aa`, with its history. HMG-API's
releases up to 1.0.2 carry it as `wireless-carplay-aa-2.1.0.zip`; later versions are
released from this repository.

## What it does

Each part is a setting in the HMG-API app (pick the mod in its list for its settings).
The firewall workaround is off by default; the other settings below are on:

| Setting | What |
|---|---|
| Turn-by-turn on the cluster and HUD | CarPlay directions; Android Auto directions only with a verified native recipe |
| Wireless CarPlay | The Bluetooth bridge and Wi-Fi sessions CarPlay needs |
| CarPlay music and Siri over Wi-Fi | AAC and Opus audio (`libleafiaaac.so`, using the head unit's own codecs) |
| Settings > Phone Projection opens Device Connections | A hook in the Settings app (US/Canada) |
| USB drives and hubs don't start wired Android Auto | A hook in the Android Auto app: a USB stick no longer blocks wireless Android Auto |
| Allow wireless Android Auto through the hotspot firewall | **Off by default.** Experimental Ioniq 6 workaround: new IPv4 TCP connections on hotspot port 40515 |

HMG-API backs up modified files and restores their original bytes when the mod is
removed. Native recipes verify the bytes at each patch site. The Ioniq 6 feature
configuration also has a required result checksum. Most changes are hooks and
in-memory patches; disk changes appear in the app's plan.

## Supported cars

| Car | Region | Head unit software |
|---|---|---|
| Kia EV6 (`CVEV`) | USA | 260507 |
| Hyundai Ioniq 5 (`NEEV`, `NEEV23`) | USA | 260507 |
| Hyundai Ioniq 5 (`NEEV`, `NEEV23`) | USA | 260903 |
| Kia EV6 (`CVEV`) | Canada | 260206 |
| Hyundai Ioniq 5 (`NEEV`) | Canada | 260206 |
| Hyundai Ioniq 6 (`CEEV`) — owner confirmed wireless AA and dash TBT | Canada | CEEV.CAN.0000.V015.011.260206 |
| Hyundai Creta (`SU2IFL`) — owner confirmed wireless CarPlay | India | SU2IFL.IND.0000.V010.001.260430 |

The Ioniq 6 addition requires the matching HMG-API hook map. On November 9, 2026,
the owner confirmed wireless Android Auto and Google Maps directions on the dash
with private build `2.2.0-beta.101`. This is one owner report on the exact firmware;
CarPlay, HUD/AR behavior and every maneuver/lifecycle case are not separately
validated.

The list is `system` in [`wireless-carplay-aa/mod.json`](wireless-carplay-aa/mod.json).
It needs the head unit's Wi-Fi hardware and a hook map for the car in HMG-API
([Supported cars](https://github.com/Open-HMG/HMG-API/wiki/Supported-cars)). Only each
region's newest software is supported: on older software, update the head unit first.
Other regions wait for now. Your car isn't listed? Open an "Add my car" issue.

## Installing

Install HMG-API **1.1.0-beta.1 or newer** first ([Installing HMG-API](https://github.com/Open-HMG/HMG-API/wiki/Installing-HMG-API)).

- **HMG-API USB installation:** leave the ZIP unopened in `HMGAPI/updates/`, then
  use HMG-API → Check USB updates. Confirm version `2.2.0-beta.101`, enable the
  hotspot firewall option and TBT, Apply successfully and restart while parked.
  Leave the alternative AR HUD format off unless separately testing it. Remove
  the USB before connecting AA.
- **Signing transition:** the original CI test and upstream package use different
  signing keys. Moving from either to this private release requires normal removal
  of only the wireless module, Apply/restart, then installation. Keep HMG-API and
  the checked map installed. The retained private signer is
  `c2318db4-4435-830e-9ef0-297ce6a863fc`; later private updates must use that key.
- **NEMESIS alternative:** the release includes the matching `-nemesis.zip` USB
  layout and `-nemesis-package.zip`. Their numeric version is `2.1.98.101` and their
  compatibility list includes CEEV CAN 260206. Do not substitute an old upstream ZIP.

[Setup and rollback](docs/ioniq6-can-260206.md) covers limits
and the core beta's existing Apply-error behavior.

Routine CI artifacts retain the upstream source version and use disposable keys;
use the release asset for the car. [Private packaging and publication](docs/private-test-packages.md)
documents the retained-key process. Original [upstream releases](https://github.com/Open-HMG/hmgapi-wireless-cp-aa/releases)
use author key `7251ce71-a146-8a1e-958b-dbffc6af7359` and are separate from this
private Ioniq 6 package.

## Hook points live in HMG-API

The mod hooks the car's apps only through HMG-API's hook points (`hooks.points` in
`mod.json`). Points, and their maps for every car, are in HMG-API, not here. A change
that needs a new or changed point:

1. A pull request to [HMG-API](https://github.com/Open-HMG/HMG-API): the point in
   `core/hookpoints/`, mapped on every car in `maps/` (`tools/map-car.py`,
   `tools/test-hooks.py`), and `docs/hook-points.md`.
2. A HMG-API release that carries it.
3. A pull request here using it, with `"hmgapi": ">=<that release>"` in `mod.json` and
   `hmgapi-sdk.json` (and the workflows' pin) moved to that release.

This repository's pull request can be open, as a draft, during 1 and 2; its CI fails
until the SDK has the point. See [HMG-API's docs/mod-repos.md](https://github.com/Open-HMG/HMG-API/blob/main/docs/mod-repos.md), section 4.

## Building and testing

CI runs HMG-API's mod workflow, with the released 1.1.0-beta.1 SDK and its SHA-256
pinned in `hmgapi-sdk.json`. On your computer, with that
SDK unpacked in `$HMGAPI_SDK` and the
Android SDK in `ANDROID_HOME`:

```
$HMGAPI_SDK/bin/build-mod.sh wireless-carplay-aa out/build/wireless-carplay-aa
```
```
HMGAPI_SDK=$HMGAPI_SDK sh wireless-carplay-aa/test.sh
```
```
HMGAPI_SDK=$HMGAPI_SDK sh wireless-carplay-aa/tools/aaturns/test.sh
```
```
HMGAPI_SDK=$HMGAPI_SDK sh wireless-carplay-aa/tools/cpturns/test.sh
```
```
HMGAPI_SDK=$HMGAPI_SDK sh wireless-carplay-aa/tools/recipes/test.sh
```
```
$HMGAPI_SDK/bin/check-scripts.sh wireless-carplay-aa
```

`test.sh` and `tools/recipes/test.sh` also check the mod on a car's real stock files,
which aren't in any repository (maintainers keep them privately, in `~/hmg-private`);
without them those parts say "skipped" and pass. `native/leafiaaac/build.sh` rebuilds
`files/libleafiaaac.so` (clang + lld).

Upstream releases use `v<mod.json version>` and the inherited upstream signing
workflow (see [CONTRIBUTING.md](CONTRIBUTING.md)). Private Ioniq 6 releases use the
separate [publication procedure](docs/private-test-packages.md) and preserve the
tested retained-key artifact; do not trigger the upstream workflow with a private tag.

## License

AGPL-3.0, see [LICENSE](LICENSE). The repository holds no manufacturer apps or
libraries: only hooks, hash-only recipes, delta patch sets, checksums and our own code.

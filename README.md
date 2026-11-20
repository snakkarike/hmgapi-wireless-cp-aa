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
The firewall workarounds are off by default; the other settings below are on:

| Setting | What |
|---|---|
| Turn-by-turn on the cluster and HUD | CarPlay directions; Android Auto directions only with a verified native recipe |
| Wireless CarPlay | The Bluetooth bridge and Wi-Fi sessions CarPlay needs |
| Shared CarPlay identity | A typed API other mods use to contribute vehicle identity without competing native patches |
| CarPlay music and Siri over Wi-Fi | AAC and Opus audio (`libleafiaaac.so`, using the head unit's own codecs) |
| Song, artist and album art on wireless CarPlay | A hook in DiPO: asks the phone for Now Playing again once wireless CarPlay's session is up, for the cluster and the split screen |
| Settings > Phone Projection opens Device Connections | A hook in the Settings app (US/Canada) |
| USB drives and hubs don't start wired Android Auto | A hook in the Android Auto app: a USB stick no longer blocks wireless Android Auto |
| End wireless CarPlay and Android Auto when you park and leave | HMG-API's car events: when the car is parked (by the owner's rule in HMG-API's Settings > Vehicle; by default off, in P, once you get out and close the door) and before the head unit shuts down, the wireless session ends the way CarPlay and Android Auto end one (Android Auto's ByeBye, DiPO's disconnect), so the phone lets go at once. Switched on again (or back from a QuickBoot suspend), the phone is asked back. USB sessions are never ended |
| Allow wireless Android Auto through the hotspot firewall | **Off by default.** Experimental Ioniq 6 workaround: new IPv4 TCP connections on hotspot port 40515 |
| Allow wireless CarPlay through the hotspot firewall | **Off by default.** Needed on the US Ioniq 6 260507 and the Elantra N (`CN7NPE`) 260903: CarPlay's ports (TCP and UDP, both ways), mDNS and ICMPv6 from the hotspot |

HMG-API backs up modified files and restores their original bytes when the mod is
removed. Native recipes verify the bytes at each patch site. The Ioniq 6 feature
configuration also has a required result checksum. Most changes are hooks and
in-memory patches; disk changes appear in the app's plan.

## Supported cars

| Car | Region | Head unit software |
|---|---|---|
| Kia EV6 (`CVEV`) | USA | 260507 |
| Hyundai Ioniq 5 (`NEEV`, `NEEV23`, `NEEV23SE`) | USA | 260507 |
| Hyundai Ioniq 5 (`NEEV`, `NEEV23`, `NEEV23SE`) | USA | 260903 |
| Kia EV6 (`CVEV`) | Canada | 260206 |
| Hyundai Ioniq 5 (`NEEV`) | Canada | 260206 |
| Hyundai Ioniq 5 (`NEEV`), same files as the US 260903 build | Canada | 260903 |
| Hyundai Elantra, Sonata, Santa Fe, Kona, Kona Electric 2020-21, Ioniq, Venue 2020-22, Veloster N, Palisade 2020-22 (and their hybrids) — not yet tested on a car; turns on the stock wireless projection and the hotspot | USA and Canada | 260507 (USA), 260206 (Canada), 260903 |
| Hyundai Ioniq 6 (`CEEV`) | USA | 260507 |
| Hyundai Ioniq 6 (`CEEV`) — owner confirmed wireless AA and dash TBT | Canada | 260206 |
| Hyundai Ioniq 6 (`CEEV`) — owner confirmed wireless CarPlay with the CarPlay firewall setting on | USA | 260903 |
| Hyundai Ioniq 6 (`CEEV`) — owner confirmed wireless CarPlay and turn-by-turn (test build) | Canada | 260903 |
| Kia Sportage Hybrid (`NQ5HEV`) — not yet tested on the car | USA | 260507 |
| Hyundai Elantra N (`CN7NPE`) — owner confirmed wireless CarPlay and drive checks | USA | 260903 |
| Kia K5, Sorento, Stinger, Soul, Seltos 2021, Niro 2020-22, Cadenza, Telluride 2020-21 (and their hybrids) — not yet confirmed on a car (a US K5 is testing); turns on the stock wireless projection and the hotspot | USA | 260507 |
| Kia K5, Sorento, Stinger, Soul, Seltos 2021, Niro 2020-21, Telluride 2020-21 (and their hybrids) — not yet tested on a car | Canada | 260206 |
| Kia K5, Sorento, Stinger, Seltos, Soul, Niro, Mohave, K7, Morning, Ray (and their hybrids) — not yet tested on a car; HMG-API turns on the hotspot feature these units lack | Korea | 260902 |
| Hyundai Ioniq 6 (`CEEV`) — not yet tested on the car; same software as the Canadian 260206 build; turn on the hotspot firewall settings if the phone finds the car but doesn't connect | USA | 260206 |
| Hyundai Elantra (`CN7PE`) — not yet tested on the car; turn on "Allow wireless CarPlay through the hotspot firewall" if CarPlay finds the car but doesn't connect | USA | 260507 |
| Kia Niro (EV, Hybrid, Plug-in Hybrid), Sportage (and Hybrid, Plug-in Hybrid), Carnival, Telluride, Forte, Soul, Seltos 2024 — not yet tested on a car | Canada | 260206 |

The CN7NPE owner confirmed wireless CarPlay connection/UI on November 16, 2026,
and audio, Siri, calls, reconnect, navigation/cluster directions and stability
checks during a drive on November 17, on a test build of this mod. This needs an
enabled HMG-API hook map and the CarPlay firewall option enabled and saved.
Android Auto and on-car rollback are not confirmed.
See [CN7NPE setup](docs/cn7npe-usa-260903.md).

The Canadian Ioniq 6 needs HMG-API's hook map for its build. On November 9, 2026, an
owner confirmed wireless Android Auto and Google Maps directions on the dash there,
on a test build of this mod. That's one owner report on that firmware: CarPlay, the
HUD and every maneuver aren't separately confirmed
([Ioniq 6 Canada notes](docs/ioniq6-can-260206.md)).

The US Ioniq 6 on 260507 (`CEEV.USA.0000.V015.012.260507`) was tested on a 2023 car
with a fork of 2.1.0: wireless CarPlay, music, calls and Now Playing on the cluster.
Its stock firewall allows the CarPlay ports only on the wired link, so turn on **Allow
wireless CarPlay through the hotspot firewall** there.
It needs HMG-API's hook map for that build (HMG-API #264), which comes with the
HMG-API release after 1.1.0-rc.3.

The list is `system` in [`wireless-carplay-aa/mod.json`](wireless-carplay-aa/mod.json).
It needs the head unit's Wi-Fi hardware and a hook map for the car in HMG-API
([Supported cars](https://github.com/Open-HMG/HMG-API/wiki/Supported-cars)). Only each
region's newest software is supported: on older software, update the head unit first.
Other regions wait for now. Your car isn't listed? Open an "Add my car" issue.

## Installing

Install HMG-API first ([Installing HMG-API](https://github.com/Open-HMG/HMG-API/wiki/Installing-HMG-API)).
Then, from this repository's [releases](https://github.com/Open-HMG/hmgapi-wireless-cp-aa/releases):

- **USB stick**: put `wireless-carplay-aa-<version>.zip` in `HMGAPI/updates/` on the
  stick, then HMG-API app → Check USB updates → the mod → Apply changes.
- **NEMESIS**: unzip `wireless-carplay-aa-<version>-nemesis.zip` onto the top level of a
  USB stick and confirm the install in NEMESIS Manager.
- **adb**: `adb push` the zip, then `hmgapid engine install <zip>`, `plan`, `apply`.

[Installing and removing mods](https://github.com/Open-HMG/HMG-API/wiki/Installing-and-removing-mods)
has the steps. Updates must be signed with the same author key as the installed version.
Every release here is signed with author key `7251ce71-a146-8a1e-958b-dbffc6af7359` (VysseCodes).
A test build signed with another key installs only after the mod is removed, and the
releases here can't update it.

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

## CarPlay identification API

Mods that declare `wireless-carplay-aa >=2.2.0-beta.3 <3.0.0` in `requires` can use the
exported `hmgapi.mods.wireless.api` package. Wireless owns the verified native serializer;
each consumer owns only the semantic facts it contributes. Vehicle identity and theme
identity have independent owners and are composed without exposing raw iAP2 parameters.

```java
CarPlayIdentification identity = (CarPlayIdentification) mod.use(
    "wireless-carplay-aa", CarPlayIdentification.SERVICE);
identity.registerTheme(mod.id,
    new CarPlayIdentification.ThemeIdentity("EXAMPLE", 1, "19.0"));
```

Call `removeTheme(mod.id)` when disabling that contribution. A second mod cannot replace
an existing theme owner; it receives an error instead. The API does not provide display
code, theme assets, or CarPlay session behavior—those remain the consumer's responsibility.

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
sh wireless-carplay-aa/tools/reattach/test.sh
```
```
ANDROID_HOME=$ANDROID_HOME sh wireless-carplay-aa/tools/projection/test.sh
```
```
HMGAPI_SDK=$HMGAPI_SDK sh wireless-carplay-aa/tools/recipes/test.sh

CLANG=clang LLD=ld.lld QEMU=qemu-system-arm OBJCOPY=llvm-objcopy \
  python3 wireless-carplay-aa/native/identity/test_equivalence.py
```
```
$HMGAPI_SDK/bin/check-scripts.sh wireless-carplay-aa
```

`test.sh` and `tools/recipes/test.sh` also check the mod on a car's real stock files,
which aren't in any repository (maintainers keep them privately, in `~/hmg-private`);
without them those parts say "skipped" and pass. `native/leafiaaac/build.sh` rebuilds
`files/libleafiaaac.so` (clang + lld). `native/identity/build.sh` rebuilds the
generic `files/libcarplayidentity.so`; its equivalence test executes the ARM serializer
and compares its output with the former EV Routing implementation.

Releases use `v<mod.json version>` tags and the signing workflow (see
[CONTRIBUTING.md](CONTRIBUTING.md)).

## License

AGPL-3.0, see [LICENSE](LICENSE). The repository holds no manufacturer apps or
libraries: only hooks, hash-only recipes, delta patch sets, checksums and our own code.

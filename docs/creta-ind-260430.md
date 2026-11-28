# Creta IND 260430 support

This document covers support for the 2024 Hyundai Creta Facelift (India) on
`SU2IFL.IND.0000.V010.001.260430` (`tcc803x`, Android 4.4.2, onebin `su2ifl_in`).

## Compatibility

`mod.json` declares `SU2IFL` (`IND`, `260430`) with the `hmgapi.wifi` feature
requirement. The patch selector maps `SU2IFL.IND.0000.V010.001.260430` to
`patches/su2ifl_in/`, which records the four stock odex fingerprints from the
November 20, 2026 Car Report (`car-report-su2ifl-ind-260430-20261120-1123.json`).
No manufacturer app files are replaced.

In `mod.json`, `btsettings.projection_helper.open_wireless_page` is omitted from
`hooks.points` because `com.android.settings2.bluetooth.iP` on this firmware
does not match the US/Canada projection helper class, and `phoneProjectionMenu`
in `Wireless.java` guards on `Car.region()` (`REGION_US` / `REGION_CANADA`)
before hooking.

## Head Unit Requirements

- **HMG-API `1.1.0-rc.8` or newer** (or `1.1.0-rc.7` with **Try unchecked maps** enabled).
- Because `260430` uses a locally probed map until marked verified in HMG-API,
  enable **Try unchecked maps** in HMG-API **Settings** on the head unit so
  `replace` hooks (such as `dipo.carplay.disable_bluetooth`) are loaded.
- Keep **Phone Projection settings open Device Connections** (`menu`) **OFF** if
  using an unpatched stock `hooks.jar`, or use a build compiled with the
  `Wireless.java` region guard.

# Hyundai Creta Facelift India 260430: setup and validation

This change targets `SU2IFL.IND.0000.V010.001.260430` (`su2ifl_in`, tcc803x).
On November 16, 2026, the vehicle owner confirmed **wireless CarPlay operation**
on a 2024 Hyundai Creta Facelift (India).

## Prerequisites

- HMG-API 1.0.2 or newer.
- Core hook map for `SU2IFL.IND.0000.V010.001.260430`. The car report confirms 339 of 343
  points active on this firmware.
- The North America-only hook point `btsettings.projection_helper.open_wireless_page` is
  omitted for Indian firmware, as `com.android.settings2.bluetooth.iP` does not exist in
  the stock Indian Bluetooth Settings app (`HKMC_BtSettings.odex`). `Wireless.java`
  guards this hook to US and Canada regions.

## Projection and Wi-Fi Hotspot

The system selector accepts `SU2IFL` on India `260430`. The patch set selector maps
`SU2IFL.IND.` to `su2ifl_in`.

The stock Indian firmware restricts the Wi-Fi hotspot by default via
`-/system/etc/permissions/com.hkmc.software.wifihotspot.xml` in `/system/onebin/su2ifl_in/featurexml.cfg`.
Applying this mod comments out the exclusion line, enabling the head unit hotspot required
for wireless CarPlay communication.

Stock properties enabled:
- `persist.ium.support=carplay`
- `persist.sys.iap2.wifi=t`
- `persist.iap2.iden.wireless=t`
- `/system/lib/libleafiaaac.so` installed for AAC/Opus audio support

## CarPlay Shared Library Compatibility

The Indian Creta firmware ships `libCarPlayCore.so` with hash `d3a47296437df56fb1ffc7ac3dfca4cb`.
This binary is byte-identical to the standard Gen5W CarPlay Core library used on US EV6 and
Ioniq 5 builds.

The upstream in-memory recipe `recipes/carplay-core-wifi.recipe` matches this hash and
applies the necessary patches in `HKMC_DiPO` process memory:
- Bypasses the transport check in `AirPlayReceiverSessionStart` so Wi-Fi sessions are accepted.
- Extends the `ProcessFrames` video frame timeout from 200 ms to 10 seconds to withstand
  transient automotive Wi-Fi packet jitter.

## Validation Status

- **Wireless CarPlay**: Confirmed working. Bluetooth discovery, Wi-Fi handover, display
  projection, touchscreen control, and Wi-Fi audio stream are functional.
- **Turn-by-Turn (TBT) Cluster Navigation**: Under initial road testing.

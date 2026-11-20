# CN7NPE USA 260903 support

The owner confirmed wireless CarPlay connection/UI on November 16, 2026, and
reported the remaining CarPlay drive checks passed without issues on November 17:
audio and controls, Siri, incoming/outgoing calls, setting persistence, reconnect,
navigation/cluster directions, guidance cleanup, source switching and sustained use.
Testing used a test build of this mod on `CN7NPE.USA.0000.V016.010.260903`, with
HMG-API `1.1.0-rc.3` and an enabled local hook map.

This is one owner's confirmation on this exact firmware. Android Auto, on-car
uninstall/rollback and long-term stability remain unconfirmed. HUD behavior is
not claimed for hardware that was not identified as equipped.

## Compatibility

Both `mod.json` and the generated NEMESIS descriptor accept CN7NPE USA 260903.
The patch selector is limited to `CN7NPE.USA.0000.V016.010.260903`; other CN7NPE
builds, regions and firmware are not included. The head unit needs Wi-Fi hardware
and an enabled HMG-API hook map for this firmware. This mod does not install one.

The owner's report contains all 40 declared hook points and a successful
351-point local-map verification. The CN7NPE patch set records four app
fingerprints from that report; no manufacturer app is replaced. Feature flags
and the hotspot use HMG-API's existing reversible feature-line merges and backups.
Native recipes and their stock-byte checks are unchanged.

## CarPlay hotspot firewall

Before the workaround, the iPhone discovered CarPlay, joined Hyundai Wi-Fi and
connected Bluetooth iAP2 on component 4, but no CarPlay session started. The car's
firewall allowed CarPlay on wired `ncm0`; `wlan0` input allowed only DNS/mDNS/DHCP,
and IPv6 input allowed only the wired interface.

**Allow wireless CarPlay through the hotspot firewall** (`cp_firewall`) is off
by default. It requests TCP/UDP source/destination port allowances for 5000–5001,
5020, 5353, 6000–6003, 6011, 6020, 6030 and 7000 on IPv4 and IPv6, plus 6031 on
IPv6 and ICMPv6 for neighbor discovery. These follow the report's wired CarPlay
port groups, including discovery traffic.

HMG-API restricts these rules to `wlan0` from `192.168.0.0/24` or `fe80::/10`.
Discovery multicast destinations remain reachable. Other interfaces, forwarding,
NAT, routes and sysctls are unchanged. HMG-API keeps the rules ahead of the stock
firewall and removes them when the setting or mod is disabled. The separate
Android Auto firewall option is not required for CarPlay.

## Setup

1. Install a release of this mod from this repository, with HMG-API or its NEMESIS
   wrapper. Keep the matching HMG-API hook map enabled.
2. Enable the whole mod first and wait for its settings form to reload. Enable
   **Allow wireless CarPlay through the hotspot firewall**.
3. Scroll below the settings and press **Save settings**. Wait for **Saved. Apply
   changes to update the head unit.** Leave/reopen the form and confirm the toggle
   stays on. Toggling the whole mod reloads the form and discards unsaved changes;
   **Apply changes alone does not save the switches**.
4. Apply changes and restart while parked if prompted. A saved firewall setting
   publishes immediately, so Apply can correctly report no file changes. Remove
   the installation USB before connecting the phone wirelessly.

With only the CarPlay option enabled, the report should show 69 HMG-API rules;
the separate AA option adds one and other mods can add more. If the toggle is
saved but no rules appear, collect the Car Report and check the saved settings,
`state/net.rules`, `state/services.txt` and `logs/hmgapi/net.log` under HMG-API's
data directory. The owner's failed attempt had zero rules because
the setting had not been retained; connection succeeded after saving it.

The owner's logs show the CarPlay Core and Audio recipes applying, Utils
reporting built-in support, and the AA `dbfe7bd2` recipe applying. An applied AA
recipe is not an Android Auto vehicle test. Unknown AA libraries still leave
this mod's AA dash bridge off; byte-check failures do not justify native offsets
or verification bypasses.

## Off-car verification

```
HMGAPI_SDK=/path/to/sdk sh wireless-carplay-aa/tools/cn7npe/test.sh
```

The test checks compatibility without overrides, Apply/repeated Apply and exact
restoration, rejection of other builds/missing Wi-Fi/a foreign converter,
SDK-generated NEMESIS metadata, and the generated network keeper against a fake
firewall. Network checks cover exact local IPv4/IPv6 rules, stock preservation,
repair after an inserted dropping jump, and cleanup on setting/module disable.

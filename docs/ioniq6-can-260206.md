# Ioniq 6 Canada 260206: setup and validation

This change targets `CEEV.CAN.0000.V015.011.260206` (`ceev_ca`, tcc803x).
On November 9, 2026, the owner confirmed **wireless Android Auto and Google Maps
directions on the dash**, on a test build of this mod with the native turn-by-turn
port below. An earlier test build had confirmed projection and the UI with the
firewall setting on, with AA turn-by-turn turned off.

This is one owner report on one firmware, not independent coverage of every feature: CarPlay, HUD/AR, detailed audio, all maneuver types, route cancellation and stock-navigation restoration have not
been separately confirmed. The head unit was not connected for an additional
post-success log capture.

## Prerequisites

- HMG-API 1.1.0-beta.1 or newer, which provides managed network rules.
- The validated core hook map for this exact firmware. The Ioniq 6 map is a
  separate HMG-API change; this module does not supply or install it. The owner's
  on-car map check passed all 351 points. No additional map points are needed by
  this native TBT port.
- No competing wireless projection/turn-by-turn mod. The existing NEMESIS conflict
  declarations remain intact.

## Projection and firewall

The system selector accepts CEEV Canada 260206; the patch-set selector accepts
only the full product string above. The `ceev_ca` manifests contain hashes only.
The engine must produce the known featurexml result, which comments the three
wireless/hotspot exclusion lines; an unexpected final configuration is refused.
The stock app/library hashes document the inspected firmware. Listing their
hashes is not an install-time verification of every app or library.

The **Allow wireless Android Auto through the hotspot firewall** setting is off
by default. The successful owner test used it enabled. It asks HMG-API to accept
new IPv4 TCP connections to port 40515 on `wlan0`, with source and destination
restricted to `192.168.0.0/24`. Existing established/related rules handle replies.
It adds no forwarding, NAT, output, IPv6, routing or Ethernet changes and requires
no broad network override. It is opt-in because the network declaration cannot
be conditioned on the car model; another supported car also receives this rule
if its owner enables the setting.

HMG-API owns the rule, reinstates it after stock firewall resets, and removes this
mod's request when the setting or mod is disabled. Other mods' network requests
remain independent. Network requests can be published before an Apply succeeds;
they are not proof that the projection patch applied successfully. No legacy
NEMESIS firewall/Ethernet plugin is needed for this setup.

## Android Auto directions

The first test safely rejected the Ioniq 6 AA library because neither upstream
native recipe matched it. The new recipe is for the exact stock fingerprint
`dbfe7bd2e36152a50f97fed612cf6326`. It ports the existing navigation-message bridge
to this build's callback thunks, string functions and message-handler continuation.
The AA library remains stock on disk; HMG-API applies the recipe in GPM's process
memory after the library loads, verifying patch sites and relevant ABI regions.

The recipe advertises protocol 1.6 while preserving message ID 1 and major 1,
allows navigation status 3, and decodes the extended navigation messages into the
callbacks consumed by `AaTurns`. The existing Java hooks advertise ENUM cluster
directions instead of IMAGE and forward turn, distance, destination and lane data
through HMG-API's Cluster API. They do not send custom CAN frames or enable GPM's
stock TBT manager by changing `stepofspec`.

Stock protocol 1.4 already handles basic turn/status/distance messages. The 1.6
advertisement and extended parser follow the upstream recipes; the exact phone-side
version gating has not been established by these off-car tests.

Unknown/unreadable AA libraries still skip the native request and dependent Java
hooks, leaving USB handling and CarPlay independent. Exact disk compatibility does
not guarantee a successful in-memory patch: the API is asynchronous and provides
no result to the mod. A conflict, allocation or write failure can still prevent
application. Visible dash guidance is now owner-confirmed. The native-application log was not
captured after that success; future diagnosis should check it rather than inferring
activation from the disk fingerprint alone. Stock-navigation arbitration still
requires a separately reported end-of-guidance test.

## Setup and additional validation

For a new installation or more testing, once parked:

1. Install a release from this repository (README, Installing). A test build signed
   with another key has to be removed first: releases here can't update it.
2. Confirm `aa_firewall=true` and `tbt=true`. Keep `tbt_standard=false` for the first
   test; the alternative AR HUD format has only been checked on a US Ioniq 5.
3. Let the main-screen status refresh finish, Apply once, and restart only after
   success. If the earlier `.xml.tmp` rename error recurs, stop and capture it;
   the core's concurrent-preparation race is separate from these module changes.
4. Remove installation media, connect wireless AA and start a Google Maps route.
   Note whether a turn arrow/distance appears on the dash and whether projection
   and audio remain functional. No driving is required to test route activation.
5. Cancel guidance and disconnect the phone; check that stale arrows clear.
   Start stock navigation separately and confirm its directions still work.

With authorization, collect saved runtime logs and current state after this test.
The expected native log contains `aa-tbt-dbfe7bd2: applied`; the Java log says
`navigation callback received; cluster bridge created`. The latter confirms that
callbacks reached the bridge, not that the physical display succeeded. If necessary,
a read-only cluster-service dump can distinguish missing callbacks from display
output problems. Logs may contain navigation details and must remain private.

For a TBT regression, turn off **Turn-by-turn on the cluster and HUD**, Apply and
restart. Keep the firewall option enabled to retain wireless projection. If the UI
cannot recover, use the normal module removal/restoration flow and reinstall the
known-working artifact; do not manually rewrite stock libraries or flush the
firewall. CarPlay needs its own iPhone test before it can be called validated.

No user logs, identifiers, APK/ODEX files, manufacturer libraries or signing keys
are included in this repository.

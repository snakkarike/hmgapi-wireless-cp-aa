# CEEV Canadian Android Auto navigation recipe

`aa-tbt-dbfe7bd2.recipe` targets only the stock Android Auto library with MD5
`dbfe7bd2e36152a50f97fed612cf6326`, observed on
`CEEV.CAN.0000.V015.011.260206`. It patches process memory, not the library on disk.
On November 9, 2026, the owner confirmed wireless AA and Google Maps directions on
the dash, on a test build of this mod. This is a reported result on one car;
the off-car checks alone do not prove phone negotiation or physical display behavior.

## Derivation

The parser is the existing `aa-tbt-48ee793a.recipe` payload, byte-for-byte except
for its five relative import-table words. Its original 5,546 bytes had SHA256
`82cbb8050483009b1913e9079768c5181d2760a68b9d9e1205eb7be797612ac6`; with the string cut
at +0x17a fixed in all three recipes (`tools/aa-text`), they have SHA256
`c2c4be0939099d55183d268f1ba776afb47597942aff2a7f3d987dffe51b253f`.
The CEEV port does not change the protobuf parser or cluster event encoding.

Dynamic symbols and local disassembly in the private stock copy establish:

| Address | Role and evidence |
| --- | --- |
| `0xb3e9a` | Minor-version immediate in `Controller::sendVersionRequest`; changes the six-byte request from `00 01 00 01 00 04` to `00 01 00 01 00 06`. Message ID and major version are unchanged. |
| `0xb7f38` | Unknown-message fallback in `NavigationStatusEndpoint::routeMessage`. The endpoint is in r4, message type in r2, protobuf data in r6, length in r5. The inherited shim preserves the surrounding function's registers and returns through the stock canary check and epilogue at `0xb7f3c`. |
| `0x144eba` | `NavigationStatus_NavigationStatusEnum_IsValid`, accepts rerouting status 3. The compiler folded this function with the validators for RdsType, VideoFocusReason and HdAcquisitionState; widening their bound is also inherited from the existing recipes. |
| `0xa7211` | Thumb entry of the next-turn callback's this-adjusting thunk. Subtracts 16 before entering the callback implementation. |
| `0xa715b` | Equivalent distance-callback thunk. |
| `0xacfd5` | Thumb entry of `std::string::_M_assign`; matches the existing STLport string layout. |
| `0xa5e69` | String destructor helper used by the stock phone-status JNI method. |
| `0xb7f3d` | Thumb return address after the fallback instruction. |

The endpoint's callback interface remains at offset `0x2c`; its two callback thunks
must be used instead of the direct implementations. The string's end and begin
pointers remain at offsets `0x10` and `0x14`. These were independently reviewed.
The added segment keeps the existing preferred offset `0x1c4120`; the runtime can
place it elsewhere and relocate its imports and branch.

Eight hash guards cover the three write sites plus the callback, string and route
ABI regions. The Java support gate also checks the exact whole-file MD5. There are
no manufacturer bytes, binaries, dumps or personal logs in this directory.

## Reproduce off-car checks

The default check needs only Python 3 and does not require stock files:

```sh
sh wireless-carplay-aa/tools/ceev-tbt/test.sh
```

For the private integration tests, point `CEEV_STOCK_LIBRARY` at a read-only copy
of the exact stock library and `HMGAPI_SDK` at the released 1.1.0-beta.1 SDK. The
Python environment also needs `pyelftools` and `unicorn`; Java needs a JDK. No ADB
commands are used and the stock file is not modified.

```sh
CEEV_STOCK_LIBRARY=/private/path/libautoreceiver_infobank.so \
  sh wireless-carplay-aa/tools/ceev-tbt/test.sh
```

The test first uses the actual SDK `MemRecipe` implementation to check all guards,
forward/backward relocation, the narrow library write footprint, out-of-range
allocation and failure before branch activation. It then runs the real stock
Thumb route handler, string functions and callback thunks in Unicorn, with JNI
callback bodies, libc imports and platform atomic helpers mocked.

The bounded ARM corpus covers the exact protocol packet, active/inactive/rerouting
statuses, unchanged legacy turns/distances, modern right turns and destinations,
a long road name (bounded to 96 bytes by the inherited parser), road names ending in
multi-byte UTF-8 (kept whole; the inherited cut damaged their last character), modern distance and
remaining trip, unknown message types, four code placements, truncated messages,
varint/length overflows and invalid declared buffer lengths. It checks r4–r11 and
stack preservation on every call. Input reads are bounded, with an unmapped page
after the buffer; the host memcpy/strlen stubs enforce the same bounds.

Modern synthetic field layouts were cross-checked against the primary reverse
engineering definitions in [NavigationNotificationMessage.proto](https://github.com/mrmees/open-android-auto/blob/main/oaa/navigation/NavigationNotificationMessage.proto)
and [NavigationTurnEventMessage.proto](https://github.com/mrmees/open-android-auto/blob/main/oaa/navigation/NavigationTurnEventMessage.proto).
These fixtures are invented messages, not captured user navigation.

This is a targeted compatibility test, not an exhaustive parser security audit.
Timing, interaction with other modules and detailed maneuver/lifecycle coverage
remain outside the bounded owner report. For a future diagnosis, a runtime log naming
`aa-tbt-dbfe7bd2: applied` is required before treating the native patch as active.

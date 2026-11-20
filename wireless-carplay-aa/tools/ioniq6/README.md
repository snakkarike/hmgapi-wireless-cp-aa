# Ioniq 6 offline integration tests

`wireless-carplay-aa/test.sh` runs `integration.py` before its optional private-stock
checks, so CI always exercises this suite. It needs Python 3, Bash, Java and the
HMG-API 1.1.0-beta.1 mod SDK; it does not use ADB, a head unit, root, or the host's
firewall.

```sh
HMGAPI_SDK=/path/to/sdk python3 wireless-carplay-aa/tools/ioniq6/integration.py
```

The suite runs the SDK's actual engine and its generated network keeper against
a disposable fake head unit. Only iptables, ip6tables, getprop and ip are replaced
at the OS boundary. It covers:

- Both compatibility selectors accepting the exact Canadian CEEV product, and
  refusing a different region, firmware or build before writing `/system`.
- Feature changes, failed result-checksum refusal, properties, disabling, and
  byte-for-byte restoration.
- No firewall change by default on CEEV or the previously supported models.
- Opting in to exactly one IPv4 TCP 40515 NEW ingress rule, limited to `wlan0`
  and the hotspot subnet, without `overrides.allow_network`.
- Opting in to the CarPlay rules on the US CEEV 260507: the CarPlay ports over TCP and
  UDP, both ways, on IPv4 and IPv6 from the hotspot, plus ICMPv6 (57 rules), again
  without `overrides.allow_network`, and their removal on toggle-off.
- Insertion before the stock whitelist, repair after a stock drop moves ahead,
  removal on toggle-off or disable, and preservation of another mod's rule.
- No IPv6, routing or forwarding changes.

The synthetic fixture substitutes only its `featurexml.cfg` stock/result hashes
in a temporary copy of the mod. It retains the shipped compatibility selectors,
settings, changes and network declaration. It does not pass `--force`,
`--any-car`, `ignore_system` or `HMGAPI_TEST_ANY_CAR`.

For an additional check of the shipped fingerprints and exact featurexml result,
provide a private stock tree with `app/`, `lib/` and
`onebin/ceev_ca/featurexml.cfg`. A parent containing `system/` also works:

```sh
HMGAPI_SDK=/path/to/sdk python3 wireless-carplay-aa/tools/ioniq6/integration.py \
  --stock /private/path/to/ceev/system
```

`IONIQ6_STOCK` is the equivalent environment variable, including when invoking
the main `test.sh`. All files listed in `patches/ceev_ca/stock` must be present.
Stock files remain outside the repository and are only read; the feature file is
copied into the temporary fake head unit for apply/restore.

These tests cover file and firewall behavior. They do not load Android hooks or
prove that Bluetooth, Wi-Fi, wireless projection or native recipes work on a car.
The synthetic unit intentionally has no hook map; the engine's hooks-off message
does not bypass either compatibility selector. `restore` restores files and
properties; the suite turns the firewall setting off or disables the mod before
checking network cleanup, as the network keeper follows enabled declarations.

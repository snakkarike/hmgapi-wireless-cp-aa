#!/bin/sh
# Host test of the Wireless mod's CarPlay-channel iAP2 re-attach (dipo/Reattach: fake
# failures, connects, ready signals and session ends on a fake clock), its sender
# (dipo/CpTx: real threads, a fake native send that blocks across a re-attach), and the
# route guidance asks + watchdog (dipo/RgWatch, alone and with Reattach, fake clock).
set -e
cd "$(dirname "$0")"
CP=$(mktemp -d); trap 'rm -rf "$CP"' EXIT
javac --release 8 -Xlint:-options -nowarn -d "$CP" $(find test -name '*.java') ../../src/hmgapi/mods/wireless/dipo/Reattach.java ../../src/hmgapi/mods/wireless/dipo/CpTx.java ../../src/hmgapi/mods/wireless/dipo/RgWatch.java
java -cp "$CP" hmgapi.mods.wireless.dipo.ReattachTest
java -cp "$CP" hmgapi.mods.wireless.dipo.RgWatchTest

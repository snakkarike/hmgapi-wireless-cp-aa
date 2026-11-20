#!/bin/sh
# Host-only compatibility, Apply/restore and NEMESIS descriptor regression.
set -e
exec python3 "$(dirname "$0")/test.py"

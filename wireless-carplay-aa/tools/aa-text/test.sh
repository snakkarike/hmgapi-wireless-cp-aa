#!/bin/sh
# Off-car check of the Android Auto recipes' string cut (check.py): no stock files needed.
set -e
python3 "$(dirname "$0")/check.py"

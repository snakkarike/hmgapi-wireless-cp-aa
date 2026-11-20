#!/bin/sh
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT
${CC:-clang} -O2 -Wall -Wextra -Werror "$HERE/leafiaaac_test.c" -lpthread -o "$T/leafiaaac_test"
"$T/leafiaaac_test"

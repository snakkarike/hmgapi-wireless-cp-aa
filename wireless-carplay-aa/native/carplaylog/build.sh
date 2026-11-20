#!/bin/sh
# Build libcarplaylog.so: CarPlay's own log switch (LogControl in the head unit's
# libCarPlayCoreUtils.so), for the "carplay_log" setting.
#   native/carplaylog/build.sh [output]   (default: ../../files/libcarplaylog.so)
# No libc or NDK: it only imports dlopen and dlsym, so a tiny stub libdl.so is linked in
# to record the dependency. Any recent clang + lld.
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
OUT=${1:-$HERE/../../files/libcarplaylog.so}
case "$OUT" in /*) ;; *) OUT=$(pwd)/$OUT ;; esac
CLANG=${CLANG:-clang}
T="--target=armv7a-linux-androideabi -mthumb -march=armv7-a -mfloat-abi=soft"
W=$(mktemp -d)
trap 'rm -rf "$W"' EXIT
echo 'void *dlopen(const char *f, int m) { return 0; } void *dlsym(void *h, const char *s) { return 0; }' > "$W/dl.c"
"$CLANG" $T -shared -nostdlib -fuse-ld=lld -Wl,-soname,libdl.so -o "$W/libdl.so" "$W/dl.c"
cp "$HERE/carplaylog.c" "$W/"
(cd "$W" && "$CLANG" $T -Os -fPIC -ffreestanding -fno-builtin -fvisibility=hidden -Wall -Wextra -Werror \
    -fno-exceptions -fno-asynchronous-unwind-tables \
    -shared -nostdlib -fuse-ld=lld -Wl,-z,now -Wl,--no-rosegment -Wl,--hash-style=sysv -Wl,--build-id=none \
    -Wl,-soname,libcarplaylog.so -o "$OUT" carplaylog.c libdl.so)
md5sum "$OUT"

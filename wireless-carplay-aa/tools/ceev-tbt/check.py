#!/usr/bin/env python3
"""Public, dependency-free checks; no manufacturer bytes are needed."""
from pathlib import Path
import hashlib
import struct

root = Path(__file__).resolve().parents[2]
def lines(name):
    return [x.split() for x in (root / 'recipes' / name).read_text().splitlines()
            if x and not x.startswith('#')]
new = lines('aa-tbt-dbfe7bd2.recipe')
old = lines('aa-tbt-48ee793a.recipe')
code = bytearray.fromhex(next(x[2] for x in new if x[0] == 'segment'))
reference = bytearray.fromhex(next(x[2] for x in old if x[0] == 'segment'))
# 82cbb805... as inherited; c2c4be09... with the string cut at +0x17a fixed (tools/aa-text)
assert hashlib.sha256(reference).hexdigest() == 'c2c4be0939099d55183d268f1ba776afb47597942aff2a7f3d987dffe51b253f'
assert len(code) == len(reference) == 5546
assert code[:0x40] == reference[:0x40] and code[0x54:] == reference[0x54:]
imports = [0xa7211, 0xa715b, 0xacfd5, 0xa5e69, 0xb7f3d]
assert [(int(x[1], 0), int(x[2], 0), int(x[3], 0)) for x in new if x[0] == 'rel32'] == [(0x40 + 4*k, 0x40, target) for k, target in enumerate(imports)]
for k, target in enumerate(imports):
    assert struct.unpack_from('<i', code, 0x40 + k*4)[0] + 0x1c4160 == target
assert [x for x in new if x[0] == 'patch'] == [['patch', '0xb3e9a', '06'], ['patch', '0x144eba', '03']]
assert [x for x in new if x[0] == 'branch'] == [['branch', '0xb7f38', '0x0']]
assert len([x for x in new if x[0] == 'verify']) == 8
for x in new:
    if x[0] in ('patch', 'branch'):
        off = int(x[1], 0)
        size = 4 if x[0] == 'branch' else len(bytes.fromhex(x[2]))
        assert any(int(v[1], 0) <= off and off+size <= int(v[1], 0)+int(v[2], 0) for v in new if v[0] == 'verify')
print('CEEV recipe: inherited parser, five relocated imports, three guarded writes verified')

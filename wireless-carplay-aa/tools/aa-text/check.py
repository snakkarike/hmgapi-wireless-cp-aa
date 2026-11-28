#!/usr/bin/env python3
"""The Android Auto recipes' string cut, run from the recipes' own bytes. No stock files.

The turn-by-turn recipes' NavigationState parser (aa-tbt-*.recipe, segment +0x17a) cuts
every string it hands to the next-turn callback (road, destination, the turns ahead): at
most 96 bytes, then back to a whole UTF-8 character. The version inherited from NEMESIS's
projection.tbt ran the back-off on every string and kept the cut character's lead byte, so
a name ending in a multi-byte character ("무령로", "Hôtel-Dieu Rd é", "Road 🚗") reached
the cluster with its last character damaged. ch0iiih/HMG_Phone_Projection_TBT 0.1.51 saw
it on Korean names ("Android Auto 끝글자 손상").

A small Thumb interpreter (only the instructions this helper uses) runs the 32 bytes from
each recipe on every case. It runs the old bytes too, to show the check catches the bug.
"""
from pathlib import Path
import struct

RECIPES = Path(__file__).resolve().parents[2] / 'recipes'
NAMES = ['aa-tbt-48ee793a.recipe', 'aa-tbt-a0bea1c4.recipe', 'aa-tbt-dbfe7bd2.recipe']
AT, SIZE, CAP = 0x17a, 32, 96
OLD = bytes.fromhex('6029024628bf602108460139535c03f0c003802b00d070470029f5d108467047')


def segment(name):
    for line in (RECIPES / name).read_text().splitlines():
        if line.startswith('segment '):
            return bytes.fromhex(line.split()[2])
    raise AssertionError(name + ': no segment')


def run(code, data):
    """r0 = the string, r1 = its length; returns r0 (the length kept)."""
    base = 0x1000
    mem = {base + k: b for k, b in enumerate(data)}
    r = [0] * 16
    r[0], r[1] = base, len(data)
    n = z = c = v = False
    pc, it = 0, None
    for _ in range(10000):
        h = struct.unpack_from('<H', code, pc)[0]
        cond_ok, predicated = True, it is not None
        if predicated:
            cond_ok, it = holds(it, n, z, c, v), None
        nxt = pc + 2
        if h & 0xf800 == 0x2800:                     # cmp rN, #imm8
            a, b = r[(h >> 8) & 7], h & 0xff
            if cond_ok:
                n, z, c, v = flags_sub(a, b)
        elif h & 0xf800 == 0x2000:                   # movs rD, #imm8 (no flags inside IT)
            if cond_ok:
                r[(h >> 8) & 7] = h & 0xff
                if not predicated:
                    n, z = False, h & 0xff == 0
        elif h & 0xf800 == 0x3800:                   # subs rDN, #imm8
            d = (h >> 8) & 7
            if cond_ok:
                a, b = r[d], h & 0xff
                n, z, c, v = flags_sub(a, b)
                r[d] = (a - b) & 0xffffffff
        elif h & 0xff00 == 0x4600:                   # mov rD, rM
            if cond_ok:
                r[((h >> 4) & 8) | (h & 7)] = r[(h >> 3) & 15]
        elif h & 0xfe00 == 0x5c00:                   # ldrb rT, [rN, rM]
            if cond_ok:
                at = (r[(h >> 3) & 7] + r[(h >> 6) & 7]) & 0xffffffff
                assert at in mem, ('read outside the string', hex(at), len(data))
                r[h & 7] = mem[at]
        elif h == 0xbf00:                            # nop
            pass
        elif h & 0xff00 == 0xbf00:                   # it <cond>, one instruction only
            assert h & 0xf == 8, hex(h)
            it = (h >> 4) & 0xf
        elif h & 0xf000 == 0xd000:                   # b<cond> label
            off = (h & 0xff) - (0x100 if h & 0x80 else 0)
            if holds((h >> 8) & 0xf, n, z, c, v):
                nxt = pc + 4 + off * 2
        elif h == 0x4770:                            # bx lr
            return r[0]
        elif h & 0xfbe0 == 0xf000:                   # and.w rD, rN, #imm8 (plain imm8 only)
            h2 = struct.unpack_from('<H', code, pc + 2)[0]
            assert h & 0x0400 == 0 and h2 & 0x7000 == 0, 'unsupported immediate'
            if cond_ok:
                r[(h2 >> 8) & 15] = r[h & 15] & (h2 & 0xff)
            nxt = pc + 4
        else:
            raise AssertionError('instruction not modelled: %04x at +%x' % (h, AT + pc))
        assert 0 <= nxt < len(code), 'left the helper'
        pc = nxt
    raise AssertionError('no return')


def flags_sub(a, b):
    res = (a - b) & 0xffffffff
    return bool(res >> 31), res == 0, a >= b, bool(((a ^ b) & (a ^ res)) >> 31)


def holds(cond, n, z, c, v):
    return {0: z, 1: not z, 2: c, 3: not c, 8: c and not z, 9: not c or z}[cond]


def expected(data):
    """At most CAP bytes, never ending inside a character."""
    if len(data) <= CAP:
        return len(data)
    k = CAP
    while k > 0 and data[k] & 0xc0 == 0x80:
        k -= 1
    return k


def cases():
    words = ['Main St', 'Straße', 'Champs-Élysées', 'Allée', 'Rue Saint-André', 'Calle José',
             '무령로', '강남대로 123번길', 'улица Ленина', 'Οδός Αθηνάς', '東京駅', 'Road 🚗', 'é', 'a']
    out = [w.encode() for w in words]
    for tail in ['', 'é', '가', '🚗']:
        for pad in range(92, 100):
            out.append(b'A' * pad + tail.encode())
    out.append(('가' * 40).encode())
    out.append(('A' * 10 + '가' * 40).encode())
    out.append(b'A' * 512)
    return out


def main():
    checks = 0
    helpers = []
    for name in NAMES:
        seg = segment(name)
        helper = seg[AT:AT + SIZE]
        assert OLD not in seg, name + ': still has the old cut'
        helpers.append(helper)
        for s in cases():
            got = run(helper, s)
            assert got == expected(s), (name, s, got, expected(s))
            s[:got].decode('utf-8')          # what reaches the callback is whole UTF-8
            checks += 1
    assert helpers[0] == helpers[1] == helpers[2], 'the three recipes differ at +0x17a'
    # the old bytes, to show the check sees the bug
    damaged = [s for s in cases() if run(OLD, s) != expected(s)]
    assert '무령로'.encode() in damaged and 'Road 🚗'.encode() in damaged and b'Main St' not in damaged
    print('ok    AA recipes cut road and destination text on whole characters (%d cases; the old cut fails %d)'
          % (checks, len(damaged)))


if __name__ == '__main__':
    main()

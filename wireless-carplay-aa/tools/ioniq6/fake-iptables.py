#!/usr/bin/env python3
"""Minimal iptables boundary for integration.py; never calls the host firewall."""

import json
import os
from pathlib import Path
import sys


path = Path(os.environ["FW"]) / "rules.json"
rules = json.loads(path.read_text()) if path.exists() else {}
version = "6" if Path(sys.argv[0]).name == "ip6tables" else "4"
args = sys.argv[1:]
table = "filter"
if args[:1] == ["-t"]:
    table, args = args[1], args[2:]
op, chain, *rest = args
key = version + "/" + table + "/" + chain
builtin = chain in ("INPUT", "OUTPUT", "FORWARD", "PREROUTING", "POSTROUTING")

if op == "-S":
    if key not in rules and not builtin:
        sys.exit(1)
    print(("-P " + chain + " DROP") if builtin else ("-N " + chain))
    for rule in rules.get(key, []):
        print("-A " + chain + " " + rule)
    sys.exit(0)
if op == "-N":
    if key in rules:
        sys.exit(1)
    rules[key] = []
elif key not in rules and not builtin:
    sys.exit(1)
elif op == "-X":
    if any(" -j " + chain + " " in " " + rule + " "
           for rule_key, values in rules.items() if rule_key.startswith(version + "/" + table + "/")
           for rule in values):
        sys.exit(1)
    del rules[key]
elif op == "-F":
    rules[key] = []
elif op == "-A":
    rules.setdefault(key, []).append(" ".join(rest))
elif op == "-I":
    position = int(rest.pop(0)) - 1
    rules.setdefault(key, []).insert(position, " ".join(rest))
elif op == "-D":
    try:
        rules[key].remove(" ".join(rest))
    except (KeyError, ValueError):
        sys.exit(1)
else:
    sys.exit("unsupported fake iptables operation: " + op)
path.write_text(json.dumps(rules, sort_keys=True))

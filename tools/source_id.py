#!/usr/bin/env python3
"""Compute Sandpaper's build identity: a digest of its shipped sources.

Usage:  python source_id.py [<sandpaper root>]
"""
import hashlib
import json
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# The source roots that make up this digest.
DIGESTED = (("src", "main", "java"), ("src", "main", "resources"))

# The jar entry sandpaper/build.py writes and landnav/build.py reads.
RECORD = "sandpaper-build.json"


def props(path):
    out = {}
    if not os.path.exists(path):
        return out
    for line in open(path, encoding="utf-8"):
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            k, v = line.split("=", 1)
            out[k.strip()] = v.strip()
    return out


def source_id(root=ROOT):
    """sha256 over Sandpaper's shipped sources: every path, then every byte."""
    h = hashlib.sha256()
    found = []
    for parts in DIGESTED:
        base = os.path.join(root, *parts)
        for d, _, fs in os.walk(base):
            for f in fs:
                full = os.path.join(d, f)
                rel = os.path.relpath(full, root).replace(chr(92), chr(47))
                found.append((rel, full))
    if not found:
        raise SystemExit("no Sandpaper sources under %s; check the path" % root)
    for rel, full in sorted(found):
        data = open(full, "rb").read()
        h.update(("%s%s%d%s" % (rel, chr(0), len(data), chr(0))).encode("utf-8"))
        h.update(data)
    return h.hexdigest()


def jar_name(root=ROOT):
    """The jar Sandpaper's build writes, named from Sandpaper's own gradle.properties."""
    p = props(os.path.join(root, "gradle.properties"))
    return "%s-%s+%s.jar" % (p.get("mod_id", "sandpaper"),
                             p.get("mod_version", "0.0.0"),
                             p.get("minecraft_version", "unknown"))


def identity(root=ROOT):
    """What every artifact records about the Sandpaper it was built from."""
    p = props(os.path.join(root, "gradle.properties"))
    return {
        "mod_id": p.get("mod_id", "sandpaper"),
        "mod_version": p.get("mod_version", "0.0.0"),
        "minecraft_version": p.get("minecraft_version", "unknown"),
        "source_sha256": source_id(root),
    }


def recorded(jar_path):
    """The identity a built jar carries, or None if it carries none."""
    import zipfile
    if not os.path.exists(jar_path):
        return None
    with zipfile.ZipFile(jar_path) as z:
        if RECORD not in z.namelist():
            return None
        return json.loads(z.read(RECORD).decode("utf-8"))


if __name__ == "__main__":
    root = sys.argv[1] if len(sys.argv) > 1 else ROOT
    print(json.dumps(identity(root), indent=2, sort_keys=True))

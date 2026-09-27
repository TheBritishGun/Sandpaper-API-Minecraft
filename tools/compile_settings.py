import glob
import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def version_key(name):
    parts, digits = [], ""
    for ch in name:
        if ch in "0123456789":
            digits += ch
        elif digits:
            parts.append(int(digits))
            digits = ""
    if digits:
        parts.append(int(digits))
    return (parts, name)


def jars(libs):
    return sorted((path.replace("\\", "/") for path in glob.glob(os.path.join(libs, "*.jar"))),
                  key=lambda path: version_key(os.path.basename(path)), reverse=True)


def java_version():
    path = os.path.join(ROOT, "gradle.properties")
    if not os.path.isfile(path):
        sys.exit(f"no gradle.properties at {path}.")
    values = {}
    with open(path, encoding="utf-8") as source:
        for line in source:
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                key, value = line.split("=", 1)
                values[key.strip()] = value.strip()
    raw = values.get("java_version")
    if raw is None or not raw.isdigit():
        sys.exit(f"{path} has no numeric java_version.")
    return int(raw)


def declared_java_floor():
    path = os.path.join(ROOT, "src", "main", "resources", "fabric.mod.json")
    if not os.path.isfile(path):
        sys.exit(f"no fabric.mod.json at {path}.")
    with open(path, encoding="utf-8") as source:
        raw = json.load(source).get("depends", {}).get("java")
    if raw is None:
        sys.exit(f'{path} has no "java" key under "depends".')
    match = re.match(r"^>=\s*(\d+)$", raw.strip())
    if not match:
        sys.exit(f'{path} declares "java": {raw!r}, not ">=N".')
    return int(match.group(1))


def class_major_version(path):
    with open(path, "rb") as source:
        header = source.read(8)
    if len(header) < 8 or header[:4] != b"\xca\xfe\xba\xbe":
        sys.exit(f"{path} does not open with the class file magic number (CAFEBABE).")
    return int.from_bytes(header[6:8], "big")


def check_java_floor(classes_dir, label):
    release = java_version()
    floor = declared_java_floor()
    if release != floor:
        sys.exit(f"gradle.properties declares java_version={release}, but fabric.mod.json promises Java {floor}.")
    classes = [os.path.join(directory, name) for directory, _, names in os.walk(classes_dir)
               for name in names if name.endswith(".class")]
    if not classes:
        sys.exit(f"no .class files under {classes_dir} for the {label} compile.")
    bad = [(path, class_major_version(path), class_major_version(path) - 44) for path in classes]
    bad = [(path, major, produced) for path, major, produced in bad if produced != floor]
    if bad:
        print(f"JAVA FLOOR VIOLATION: the {label} compile needs Java {floor}; "
              f"{len(bad)} of {len(classes)} class files differ:")
        for path, major, produced in sorted(bad)[:10]:
            print(f"  major={major} (Java {produced})  {os.path.relpath(path, classes_dir)}")
        if len(bad) > 10:
            print(f"  ... and {len(bad) - 10} more")
        sys.exit(f"{label} compile produced bytecode different from the manifest's Java floor")
    print(f"java floor: {len(classes)} {label} class files exactly Java {floor} (major {floor + 44})")

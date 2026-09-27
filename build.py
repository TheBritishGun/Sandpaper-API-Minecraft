#!/usr/bin/env python3
"""Build Sandpaper without Gradle: compile libs/ jars into a mod jar with javac and jar.

Usage:  python build.py [--jdk <path>] [--clean]
"""
import argparse, importlib.util, json, os, shutil, subprocess, sys, zipfile

ROOT = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(ROOT, "tools"))
import source_id  # noqa: E402
import deliver  # noqa: E402
_compile_settings_spec = importlib.util.spec_from_file_location(
    "compile_settings", os.path.join(ROOT, "tools", "compile_settings.py"))
compile_settings = importlib.util.module_from_spec(_compile_settings_spec)
_compile_settings_spec.loader.exec_module(compile_settings)

LIBS = os.path.join(ROOT, "libs")
SRC_MAIN = os.path.join(ROOT, "src", "main", "java")
RES_MAIN = os.path.join(ROOT, "src", "main", "resources")
BUILD = os.path.join(ROOT, "build")
CLASSES = os.path.join(BUILD, "classes")
RESOURCES = os.path.join(BUILD, "resources")

# Packages shipped in the engine jar, not the mod jar.
ENGINE_PACKAGES = ("dev/sandpaper/core/",)
ENGINE_ROOT_CLASSES = ()

# Types the engine jar must not name.
ENGINE_FORBIDDEN = (b"net/minecraft", b"org/slf4j", b"com/google/gson")

# The engine jar's own directory, apart from the mod jar.
ENGINE_DIR = os.path.join(BUILD, "engine")


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


def version_key(name):
    """Sort key that compares each run of digits in name as a number, not text."""
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


def find_jdk(explicit):
    for cand in filter(None, [explicit, os.environ.get("JAVA_HOME")]):
        if os.path.exists(os.path.join(cand, "bin", "javac.exe")) or \
           os.path.exists(os.path.join(cand, "bin", "javac")):
            return cand
    tools = os.path.join(os.path.expanduser("~"), "tools")
    if os.path.isdir(tools):
        names = [n for n in os.listdir(tools) if n.startswith("jdk-25")]
        if names:
            return os.path.join(tools, max(names, key=compile_settings.version_key))
    sys.exit("No JDK 25 found. Pass --jdk <path> or set JAVA_HOME.")


def tool(jdk, name):
    exe = os.path.join(jdk, "bin", name + ".exe")
    return exe if os.path.exists(exe) else os.path.join(jdk, "bin", name)


def sources(root):
    return [os.path.join(d, f).replace("\\", "/")
            for d, _, fs in os.walk(root) for f in fs if f.endswith(".java")]


def clear_outputs(*dirs):
    """Empty the compile outputs before every run."""
    for d in dirs:
        if os.path.isdir(d):
            shutil.rmtree(d)
        os.makedirs(d, exist_ok=True)


def refuse_stuck_version(out_jar, ident, root=ROOT):
    """Exit if the sources changed but mod_version did not move; allow a jar with no record."""
    was = source_id.recorded(out_jar)
    if was is not None and was.get("source_sha256") != ident["source_sha256"]:
        sys.exit("Sandpaper sources changed, but mod_version did not move.\n"
                 "  jar     : %s\n"
                 "  records : %s\n"
                 "  sources : %s\n"
                 "  Increase mod_version in %s, then run this build again."
                 % (out_jar, was.get("source_sha256", "?"), ident["source_sha256"],
                    os.path.join(root, "gradle.properties")))


def engine_class_files(classes_dir):
    """Every compiled class under the engine packages, found by walking classes_dir."""
    found = []
    for d, _, fs in os.walk(classes_dir):
        for fn in fs:
            if not fn.endswith(".class"):
                continue
            full = os.path.join(d, fn)
            rel = os.path.relpath(full, classes_dir).replace("\\", "/")
            stem = rel[:-len(".class")].split("$", 1)[0]
            if rel.startswith(ENGINE_PACKAGES) or stem in ENGINE_ROOT_CLASSES:
                found.append((full, rel))
    return sorted(found, key=lambda pair: pair[1])


def refuse_third_party(found):
    """Exit if any engine class names the game or a third-party library, by its bytes."""
    named = []
    for full, rel in found:
        data = open(full, "rb").read()
        for bad in ENGINE_FORBIDDEN:
            if bad in data:
                named.append("%s  names  %s" % (rel, bad.decode()))
    if named:
        sys.exit("The engine jar links against nothing. These classes break that:\n"
                 + "".join("  %s\n" % n for n in named)
                 + "  Route the call through dev.sandpaper.core.CoreLog, or move the\n"
                 "  class to the mod jar.")


def write_engine_jar(ident, jdk):
    """Pack the engine classes and the build record into their own jar, no resources."""
    found = engine_class_files(CLASSES)
    if not found:
        sys.exit("No engine classes under %s. ENGINE_PACKAGES may be wrong." % CLASSES)
    refuse_third_party(found)
    os.makedirs(ENGINE_DIR, exist_ok=True)
    # The engine jar name carries no minecraft version.
    out = os.path.join(ENGINE_DIR, "%s-engine-%s.jar" % (ident["mod_id"],
                                                         ident["mod_version"]))
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
        for full, rel in found:
            z.write(full, rel)
        record = dict(ident, packages=[q.strip("/").split("/")[-1]
                                       for q in ENGINE_PACKAGES])
        z.writestr(source_id.RECORD,
                   json.dumps(record, indent=2, sort_keys=True) + chr(10))
    print("engine   : %s  (%s KB, %d classes)"
          % (out, round(os.path.getsize(out) / 1024, 1), len(found)))
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--jdk")
    ap.add_argument("--clean", action="store_true")
    ap.add_argument("--deliver", action="store_true",
                    help="publish the finished jar after all delivery checks pass")
    args = ap.parse_args()

    jdk = find_jdk(args.jdk)
    p = props(os.path.join(ROOT, "gradle.properties"))

    # Sandpaper's identity, computed by tools/source_id.py.
    ident = source_id.identity(ROOT)
    mod_id = ident["mod_id"]
    version = f"{ident['mod_version']}+{ident['minecraft_version']}"
    out_jar = os.path.join(BUILD, f"{mod_id}-{version}.jar")

    refuse_stuck_version(out_jar, ident)

    if args.clean and os.path.isdir(BUILD):
        shutil.rmtree(BUILD)

    clear_outputs(CLASSES, RESOURCES)

    jars = compile_settings.jars(LIBS)
    if not jars:
        sys.exit(f"No jars in {LIBS}. Run: python tools/fetch_deps.py.")
    cp = ";".join(jars) if os.name == "nt" else ":".join(jars)

    srcs = sources(SRC_MAIN)
    argfile = os.path.join(BUILD, "javac.args")
    with open(argfile, "w", encoding="utf-8") as f:
        f.write("--release %d\n" % compile_settings.java_version())
        f.write('-d "%s"\n' % CLASSES.replace("\\", "/"))
        f.write('-cp "%s"\n' % cp)
        f.write("-Xlint:all\n")
        for s in srcs:
            f.write('"%s"\n' % s)

    print(f"jdk      : {jdk}")
    print(f"sources  : {len(srcs)}")
    print(f"classpath: {len(jars)} jars")
    r = subprocess.run([tool(jdk, "javac"), "@" + argfile.replace("\\", "/")])
    if r.returncode != 0:
        sys.exit("compile failed")
    print("compile  : OK")
    compile_settings.check_java_floor(CLASSES, "main")


    subs = {
        "version": version,
        "minecraft_version": p.get("minecraft_version", ""),
        "loader_version": p.get("loader_version", ""),
    }
    for d, _, fs in os.walk(RES_MAIN):
        for fn in fs:
            src = os.path.join(d, fn)
            rel = os.path.relpath(src, RES_MAIN)
            dst = os.path.join(RESOURCES, rel)
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            if fn == "fabric.mod.json":
                text = open(src, encoding="utf-8").read()
                for k, v in subs.items():
                    text = text.replace("${%s}" % k, v)
                open(dst, "w", encoding="utf-8").write(text)
            else:
                shutil.copy2(src, dst)

    with zipfile.ZipFile(out_jar, "w", zipfile.ZIP_DEFLATED) as z:
        for base in (CLASSES, RESOURCES):
            for d, _, fs in os.walk(base):
                for fn in fs:
                    full = os.path.join(d, fn)
                    z.write(full, os.path.relpath(full, base).replace("\\", "/"))
        # Written straight into the archive, not staged under resources/.
        z.writestr(source_id.RECORD,
                   json.dumps(ident, indent=2, sort_keys=True) + chr(10))
    print(f"source   : {ident['source_sha256']}")
    print(f"jar      : {out_jar}  ({round(os.path.getsize(out_jar)/1024,1)} KB)")
    write_engine_jar(ident, jdk)
    deliver.publish_or_plan(out_jar, mod_id, args.deliver)


if __name__ == "__main__":
    main()

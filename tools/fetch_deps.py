#!/usr/bin/env python3
"""Populate libs/ with the Minecraft-side jars this tool can source.

Does not fetch libs/junit-console.jar, fabric-api or modmenu; add those yourself.
Checks every jar against its publisher's digest.

Usage:  python fetch_deps.py [--recheck]
  --recheck  hash every cached jar instead of trusting its saved digest
"""
import hashlib, json, os, re, shutil, sys, urllib.error, urllib.request, zipfile, zlib

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LIBS = os.path.join(ROOT, "libs")
UA = {"User-Agent": "landnav-fetch-deps/1.0"}

WANT = ("jspecify", "guava", "gson", "commons", "fastutil", "joml", "icu4j",
        "netty", "slf4j", "authlib", "brigadier", "datafixerupper", "logging")

# gson, slf4j and brigadier are required to compile and test. The rest are
# Mojang-only: not required by this repo; the manifest declares them.


# True when --recheck is passed: hash every cached jar instead of trusting its stamp.
RECHECK = False

def props(path):
    out = {}
    for line in open(path, encoding="utf-8"):
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            k, v = line.split("=", 1)
            out[k.strip()] = v.strip()
    return out


def loader_common_libraries(loader_path):
    try:
        with zipfile.ZipFile(loader_path) as loader:
            with loader.open("fabric-installer.json") as f:
                installer = json.load(f)
    except FileNotFoundError:
        sys.exit(f"Cannot determine sponge-mixin: loader jar is missing: {loader_path}")
    except KeyError:
        sys.exit(f"Cannot determine sponge-mixin: {loader_path} has no fabric-installer.json")
    except zipfile.BadZipFile as e:
        sys.exit(f"Cannot determine sponge-mixin: {loader_path} is not a valid ZIP file: {e}")
    except UnicodeDecodeError as e:
        sys.exit(f"Cannot determine sponge-mixin: {loader_path} fabric-installer.json is not valid UTF-8: {e}")
    except zlib.error as e:
        sys.exit(f"Cannot determine sponge-mixin: {loader_path} contains a corrupt deflate stream: {e}")
    except (OSError, json.JSONDecodeError) as e:
        sys.exit(f"Cannot determine sponge-mixin from {loader_path}: {e}")

    if not isinstance(installer, dict):
        sys.exit(f"Cannot determine sponge-mixin: {loader_path} fabric-installer.json is not a JSON object")
    libraries = installer.get("libraries")
    if isinstance(libraries, dict) and isinstance(libraries.get("common"), list):
        return libraries["common"]
    return []


def loader_sponge_mixin(loader_path):
    for library in loader_common_libraries(loader_path):
        if not isinstance(library, dict):
            continue
        coordinate = library.get("name")
        if (isinstance(coordinate, str)
                and re.fullmatch(r"net\.fabricmc:sponge-mixin:[^:]+", coordinate)):
            sha1 = library.get("sha1")
            return coordinate, sha1 if isinstance(sha1, str) else None
    sys.exit("Cannot determine sponge-mixin: fabric-installer.json has no "
             "net.fabricmc:sponge-mixin entry")


def loader_library(library):
    if not isinstance(library, dict):
        sys.exit("Cannot determine loader library: common entry is not an object")
    coordinate, base, sha1 = library.get("name"), library.get("url"), library.get("sha1")
    if not isinstance(coordinate, str) or not isinstance(base, str):
        sys.exit("Cannot determine loader library: common entry has no name or url")
    parts = coordinate.split(":")
    if len(parts) not in {3, 4} or not all(parts):
        sys.exit(f"Cannot determine loader library: invalid coordinate {coordinate!r}")
    if not isinstance(sha1, str) or not re.fullmatch(r"[0-9a-fA-F]{40}", sha1):
        sys.exit(f"Cannot determine loader library: {coordinate} has no SHA-1")
    group, artifact, version = parts[:3]
    name = f"{artifact}-{version}" + (f"-{parts[3]}" if len(parts) == 4 else "") + ".jar"
    path = "/".join(group.split(".") + [artifact, version, name])
    size = library.get("size")
    return coordinate, artifact, name, base.rstrip("/") + "/" + path, sha1, \
        size if isinstance(size, int) else None


def get_json(url):
    return json.load(urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=60))


def scratch(dst):
    """The hidden directory beside dst that holds its staging file and its stamp."""
    return os.path.join(os.path.dirname(dst), ".fetch")


def sha1_of(path):
    h = hashlib.sha1()
    with open(path, "rb") as f:
        for c in iter(lambda: f.read(1 << 20), b""):
            h.update(c)
    return h.hexdigest()


def stamp_read(dst):
    """The (digest, size, mtime_ns) this tool last verified for dst, or None."""
    try:
        with open(os.path.join(scratch(dst), os.path.basename(dst) + ".sha1"),
                  encoding="utf-8") as f:
            digest, size, mtime = f.read().split()
        return digest.lower(), int(size), int(mtime)
    except (OSError, ValueError):
        return None


def stamp_write(dst, sha1):
    """Record that dst's bytes hashed to sha1."""
    try:
        os.makedirs(scratch(dst), exist_ok=True)
        st = os.stat(dst)
        with open(os.path.join(scratch(dst), os.path.basename(dst) + ".sha1"),
                  "w", encoding="utf-8") as f:
            f.write(f"{sha1} {st.st_size} {st.st_mtime_ns}\n")
    except OSError:
        pass


def stamp_remove(dst):
    try:
        os.remove(os.path.join(scratch(dst), os.path.basename(dst) + ".sha1"))
        return True
    except OSError:
        return False


def published_sha1(url):
    """The SHA-1 the repository publishes beside the artifact itself."""
    side = url + ".sha1"
    try:
        with urllib.request.urlopen(urllib.request.Request(side, headers=UA),
                                    timeout=60) as r:
            body = r.read().decode()
    except OSError as e:
        sys.exit(f"Cannot read the published SHA-1 at {side}: {e}.")
    m = re.match(r"\s*([0-9a-fA-F]{40})\b", body)
    if not m:
        sys.exit(f"{side} is not a SHA-1 sidecar (got {body[:60]!r})")
    return m.group(1).lower()


def trusted(dst, sha1, size):
    """True only when dst is known to hold exactly the bytes `sha1` names."""
    if not os.path.exists(dst):
        return False
    st = os.stat(dst)
    if size is not None and st.st_size != size:
        print(f"  {os.path.basename(dst)}: cached copy is {st.st_size} bytes, "
              f"publisher says {size}; refetching")
        return False
    if not RECHECK and stamp_read(dst) == (sha1, st.st_size, st.st_mtime_ns):
        return True
    if sha1_of(dst) != sha1:
        print(f"  {os.path.basename(dst)}: cached copy fails its SHA-1; refetching")
        return False
    stamp_write(dst, sha1)
    return True


def fetch(url, dst, sha1):
    """Stream url into a staging file and move it onto dst only once it verifies."""
    os.makedirs(scratch(dst), exist_ok=True)
    part = os.path.join(scratch(dst), os.path.basename(dst) + ".part")
    try:
        with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=900) as r, \
             open(part, "wb") as f:
            shutil.copyfileobj(r, f, 1 << 20)
        got = sha1_of(part)
        if got != sha1:
            sys.exit(f"SHA-1 mismatch for {url}\n"
                     f"  published {sha1}\n"
                     f"  got       {got}")
        os.replace(part, dst)
    finally:
        # Removes the staging file if it still exists.
        try:
            os.remove(part)
        except OSError:
            pass
    stamp_write(dst, sha1)


def download(url, dst, sha1=None, size=None):
    """Put the bytes url publishes at dst; true if fetched, false if the cache matched."""
    if sha1 is None:
        sha1 = published_sha1(url)
    sha1 = sha1.lower()
    if trusted(dst, sha1, size):
        return False
    fetch(url, dst, sha1)
    return True


def latest_maven(group_path, artifact):
    """The newest released version string Maven Central lists for group_path/artifact."""
    meta = urllib.request.urlopen(urllib.request.Request(
        f"https://repo1.maven.org/maven2/{group_path}/{artifact}/maven-metadata.xml",
        headers=UA), timeout=60).read().decode()
    versions = [v for v in re.findall(r"<version>([^<]+)</version>", meta)
                if re.fullmatch(r"[\d.]+", v)]
    return versions[-1]


def verdict(fresh):
    return "fetched" if fresh else "cached, digest verified"


def lettered_client_jar(name):
    match = re.fullmatch(r"mc(.+)-client\.jar", name)
    return match is not None and re.search(r"[A-Za-z]", match.group(1)) is not None


def prune_superseded(pattern, keep):
    """Delete any jar in libs/ that matches pattern but is not in keep."""
    if isinstance(keep, str):
        keep = {keep}
    for name in os.listdir(LIBS):
        if name not in keep and pattern.fullmatch(name) and not lettered_client_jar(name):
            os.remove(os.path.join(LIBS, name))
            stamp_remove(os.path.join(LIBS, name))
            print(f"  removed superseded {name}")


def prune_stamped_unowned(keep):
    for name in os.listdir(LIBS):
        dst = os.path.join(LIBS, name)
        stamp = os.path.join(scratch(dst), name + ".sha1")
        if (name not in keep and name.endswith(".jar") and os.path.exists(stamp)
                and not lettered_client_jar(name)):
            os.remove(dst)
            stamp_removed = stamp_remove(dst)
            stamp_outcome = "with its stamp" if stamp_removed else "but its stamp stayed"
            print(f"  removed stamped unowned {name} {stamp_outcome}")


def main():
    global RECHECK
    RECHECK = "--recheck" in sys.argv[1:]
    if RECHECK:
        print("--recheck: every cached jar is hashed, libs/.fetch/ stamps ignored")
    p = props(os.path.join(ROOT, "gradle.properties"))
    mc_version = p["minecraft_version"]
    loader_version = p["loader_version"]
    os.makedirs(LIBS, exist_ok=True)

    manifest = get_json("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json")
    entry = next((v for v in manifest["versions"] if v["id"] == mc_version), None)
    if entry is None:
        sys.exit(f"Minecraft {mc_version} is not in the version manifest")
    meta = get_json(entry["url"])

    client = meta["downloads"]["client"]
    print(f"minecraft {mc_version} client ({round(client['size']/1024/1024,1)} MB)",
          end="", flush=True)
    client_name = f"mc{mc_version}-client.jar"
    print(" - " + verdict(download(client["url"], os.path.join(LIBS, client_name),
                                   client["sha1"], client["size"])))
    version_dst = os.path.join(LIBS, "version_manifest.json")
    os.makedirs(scratch(version_dst), exist_ok=True)
    version_part = os.path.join(scratch(version_dst),
                                os.path.basename(version_dst) + ".part")
    try:
        with open(version_part, "w", encoding="utf-8") as f:
            json.dump(meta, f)
        os.replace(version_part, version_dst)
    finally:
        try:
            os.remove(version_part)
        except OSError:
            pass
    prune_superseded(re.compile(r"mc[\d.]+-client\.jar"), client_name)

    count = fetched = 0
    kept = {}
    managed = {client_name}
    for lib in meta.get("libraries", []):
        art = (lib.get("downloads") or {}).get("artifact")
        if not art or not any(w in lib.get("name", "").lower() for w in WANT):
            continue
        fname = art["url"].rsplit("/", 1)[-1]
        managed.add(fname)
        # The Maven coordinate: group:artifactId:version[:classifier].
        gav = lib.get("name", "").split(":")
        if len(gav) >= 2:
            kept.setdefault(gav[1], set()).add(fname)
        if download(art["url"], os.path.join(LIBS, fname),
                    art.get("sha1"), art.get("size")):
            fetched += 1
        count += 1
    print(f"minecraft libraries: {count} - {fetched} fetched, "
          f"{count - fetched} verified from cache")
    for artifact_id, names in kept.items():
        prune_superseded(re.compile(re.escape(artifact_id) + r"-\d.*\.jar"), names)

    loader = (f"https://maven.fabricmc.net/net/fabricmc/fabric-loader/{loader_version}/"
              f"fabric-loader-{loader_version}.jar")
    loader_name = f"fabric-loader-{loader_version}.jar"
    managed.add(loader_name)
    print(f"fabric-loader {loader_version} - " + verdict(download(
        loader, os.path.join(LIBS, loader_name))))
    prune_superseded(re.compile(r"fabric-loader-[\d.]+\.jar"), loader_name)

    loader_path = os.path.join(LIBS, loader_name)
    loader_sponge_mixin(loader_path)
    loader_count = loader_fetched = 0
    loader_kept = {}
    for library in loader_common_libraries(loader_path):
        coordinate, artifact, name, url, sha1, size = loader_library(library)
        fresh = download(url, os.path.join(LIBS, name), sha1, size)
        managed.add(name)
        print(f"{coordinate} - " + verdict(fresh))
        loader_kept.setdefault(artifact, set()).add(name)
        loader_count += 1
        if fresh:
            loader_fetched += 1
    print(f"loader common libraries: {loader_count} - {loader_fetched} fetched, "
          f"{loader_count - loader_fetched} verified from cache")
    for artifact, names in loader_kept.items():
        prune_superseded(re.compile(re.escape(artifact) + r"-\d.*\.jar"), names)

    prune_stamped_unowned(managed)

    print(f"\nlibs/ now has {len([f for f in os.listdir(LIBS) if f.endswith('.jar')])} jars")


if __name__ == "__main__":
    main()

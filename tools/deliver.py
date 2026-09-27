#!/usr/bin/env python3
"""Plan or deliver a built mod jar to both destinations."""
import argparse
import datetime as dt
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile


USERPROFILE = Path(os.environ.get("USERPROFILE", Path.home()))
DEFAULT_INSTALLER_ROOT = USERPROFILE / "Desktop" / "Open-Map AIO installers"
DEFAULT_PROFILE_MODS = (USERPROFILE / "AppData" / "Roaming" / "ModrinthApp" /
                        "profiles" / "Fabric-26.2-migrated" / "mods")


class Refusal(Exception):
    """A preflight failure that leaves all destination files unchanged."""


def sha256(path):
    """Return the sha256 of a regular file without loading it all at once."""
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def destination_paths(jar, mod, installer_root, profile_mods):
    """Return the installer and profile filenames for this exact built jar name."""
    jar = Path(jar)
    return (installer_root / "Mods" / mod / jar.name,
            profile_mods / jar.name)


def replaced_jars(mod, destinations):
    """Find existing jars for this mod that delivery will archive before replacing."""
    found = []
    prefix = mod + "-"
    for destination in destinations:
        for candidate in sorted(destination.parent.glob(prefix + "*.jar")):
            if candidate.is_file():
                found.append(candidate)
    return found


def game_is_running():
    """Return true only when tasklist reports Minecraft's javaw.exe process."""
    result = subprocess.run(
        ["tasklist", "/FI", "IMAGENAME eq javaw.exe"],
        capture_output=True, text=True, check=False)
    return "javaw.exe" in result.stdout.lower()


def preflight(jar, mod, installer_root, profile_mods, archive_root,
              expected_sha256=None, assume_game_running=False):
    """Validate every delivery precondition before archive or copy work begins."""
    jar = Path(jar)
    if not jar.is_file():
        raise Refusal("built jar is missing: %s" % jar)
    destinations = destination_paths(jar, mod, installer_root, profile_mods)
    labels = ("installer destination", "profile destination")
    for label, destination in zip(labels, destinations):
        if not destination.parent.is_dir():
            raise Refusal("%s is not a directory: %s" % (label, destination.parent))
    if not archive_root.is_dir():
        raise Refusal("archive root is not a directory: %s" % archive_root)
    archive = archive_root / ("superseded-" + dt.date.today().isoformat())
    if archive.exists() and not archive.is_dir():
        raise Refusal("archive destination is not a directory: %s" % archive)
    for candidate in replaced_jars(mod, destinations):
        archived_name = candidate.parent.name + "__" + candidate.name
        if (archive / archived_name).exists():
            raise Refusal("archive target already exists: %s" %
                          (archive / archived_name))
    actual_sha256 = sha256(jar)
    if expected_sha256 and actual_sha256.lower() != expected_sha256.lower():
        raise Refusal("built jar digest does not match expected digest:\n"
                      "  expected: %s\n  actual  : %s" %
                      (expected_sha256, actual_sha256))
    if assume_game_running or game_is_running():
        raise Refusal("Minecraft is running (javaw.exe); delivery was not started")
    return destinations, replaced_jars(mod, destinations), actual_sha256


def plan(jar, mod, installer_root, profile_mods, archive_root):
    """Print the destinations and current replacement candidates without writing."""
    destinations = destination_paths(jar, mod, installer_root, profile_mods)
    print("Delivery plan only. Pass --deliver to publish.")
    print("  jar      : %s" % jar)
    print("  installer: %s" % destinations[0])
    print("  profile  : %s" % destinations[1])
    print("  archive  : %s" % archive_root)
    candidates = replaced_jars(mod, destinations)
    if candidates:
        print("  archive existing:")
        for candidate in candidates:
            print("    %s (%d bytes)" % (candidate, candidate.stat().st_size))
    else:
        print("  archive existing: none")


def copy_atomically(source, destination):
    """Copy source beside destination and replace only after its digest matches."""
    descriptor, temporary_name = tempfile.mkstemp(
        prefix=destination.name + ".", suffix=".new", dir=destination.parent)
    os.close(descriptor)
    temporary = Path(temporary_name)
    try:
        shutil.copy2(source, temporary)
        if sha256(source) != sha256(temporary):
            raise RuntimeError("staged copy digest differs: %s" % destination)
        os.replace(temporary, destination)
    finally:
        if temporary.exists():
            temporary.unlink()


def deliver(jar, mod, installer_root, profile_mods, archive_root,
            expected_sha256=None, assume_game_running=False):
    """Archive replacements, copy to both destinations, and prove matching digests."""
    destinations, candidates, source_digest = preflight(
        jar, mod, installer_root, profile_mods, archive_root, expected_sha256,
        assume_game_running)
    archive = archive_root / ("superseded-" + dt.date.today().isoformat())
    archive.mkdir(parents=False, exist_ok=True)
    archived = []
    for candidate in candidates:
        archived_name = candidate.parent.name + "__" + candidate.name
        archived_path = archive / archived_name
        shutil.copy2(candidate, archived_path)
        if sha256(candidate) != sha256(archived_path):
            raise RuntimeError("archive digest differs: %s" % candidate)
        archived.append((candidate, archived_path))
    archived_by_source = {source: archived_path for source, archived_path in archived}
    copied = []
    try:
        for destination in destinations:
            copy_atomically(jar, destination)
            copied.append(destination)
        digests = [sha256(path) for path in (jar,) + destinations]
        if any(value != source_digest for value in digests):
            raise RuntimeError("delivery digest mismatch:\n  " + "\n  ".join(
                "%s: %s" % (path, digest)
                for path, digest in zip((jar,) + destinations, digests)))
    except Exception as error:
        restore_errors = []
        for destination in reversed(copied):
            try:
                if destination in archived_by_source:
                    copy_atomically(archived_by_source[destination], destination)
                elif destination.exists():
                    destination.unlink()
            except Exception as restore_error:
                restore_errors.append("%s: %s" % (destination, restore_error))
        if restore_errors:
            raise RuntimeError("delivery failed and restoration also failed: %s" %
                               "; ".join(restore_errors)) from error
        raise RuntimeError("delivery failed after preflight; changed destinations "
                           "were restored: %s" % error) from error
    for candidate, _ in archived:
        if candidate not in destinations:
            candidate.unlink()
    print("Delivered with matching sha256:")
    for path, digest in zip((jar,) + destinations, digests):
        print("  %s: %s" % (path, digest))


def publish_or_plan(jar, mod, should_deliver):
    """Use the default paths for a build result; publish only when asked."""
    archive_root = DEFAULT_INSTALLER_ROOT / "Old"
    if should_deliver:
        deliver(jar, mod, DEFAULT_INSTALLER_ROOT, DEFAULT_PROFILE_MODS, archive_root)
    else:
        plan(jar, mod, DEFAULT_INSTALLER_ROOT, DEFAULT_PROFILE_MODS, archive_root)


def main():
    """Provide a direct interface for delivery and isolated preflight probes."""
    parser = argparse.ArgumentParser()
    parser.add_argument("--jar", required=True, type=Path)
    parser.add_argument("--mod", required=True)
    parser.add_argument("--installer-root", type=Path, default=DEFAULT_INSTALLER_ROOT)
    parser.add_argument("--profile-mods", type=Path, default=DEFAULT_PROFILE_MODS)
    parser.add_argument("--archive-root", type=Path)
    parser.add_argument("--deliver", action="store_true")
    parser.add_argument("--expected-sha256")
    parser.add_argument("--assume-game-running", action="store_true",
                        help="probe only: exercise the game-running refusal")
    args = parser.parse_args()
    archive_root = args.archive_root or args.installer_root / "Old"
    try:
        if args.deliver:
            deliver(args.jar, args.mod, args.installer_root, args.profile_mods,
                    archive_root, args.expected_sha256, args.assume_game_running)
        else:
            plan(args.jar, args.mod, args.installer_root, args.profile_mods,
                 archive_root)
    except Refusal as error:
        print("REFUSED: %s" % error, file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())

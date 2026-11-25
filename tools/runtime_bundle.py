#!/usr/bin/env python3

import argparse
import hashlib
import re
import shutil
import struct
import sys
import tempfile
from pathlib import Path
from zipfile import ZipFile


ROOT = Path(__file__).resolve().parents[1]
PROFILE = ROOT / "corevision-android/src/main/assets/corevision/profile.sha256"
FACE_PROFILE = PROFILE.with_name("profile-face.sha256")
COMPARISON_PROFILE = PROFILE.with_name("profile-face-comparison.sha256")
MANIFEST = "assets/corevision/runtime.sha256"
LOADER = "libcorevision_musl.so"
BRIDGES = {"libcorevision_probe.so", "libcorevision_worker.so"}
LIBRARY = re.compile(r"lib[A-Za-z0-9_.+-]+\.so")
LINE = re.compile(r"([0-9a-f]{64})  (lib/lib[A-Za-z0-9_.+-]+\.so|models/[A-Za-z0-9_.-]+\.(?:ms|bin))")


def checksum(stream):
    digest = hashlib.sha256()
    for chunk in iter(lambda: stream.read(1024 * 1024), b""):
        digest.update(chunk)
    return digest.hexdigest()


def parse_manifest(contents):
    if len(contents) > 65_536:
        raise ValueError("Runtime hash manifest exceeds 64 KiB")
    result = {}
    for line in contents.decode("ascii").splitlines():
        match = LINE.fullmatch(line)
        if match is None or match[2] in result:
            raise ValueError("Malformed runtime hash manifest")
        result[match[2]] = match[1]
    return result


def verify_pin_hashes(entries):
    required = parse_manifest(PROFILE.read_bytes())
    face = parse_manifest(FACE_PROFILE.read_bytes())
    comparison = parse_manifest(COMPARISON_PROFILE.read_bytes())
    if comparison.keys() & entries.keys():
        required.update(comparison)
        required.update(face)
    elif face.keys() & entries.keys():
        required.update(face)
    for path, expected in required.items():
        if entries.get(path) != expected:
            raise ValueError(f"Unsupported or missing runtime file {path}")


def bundle_path(bundle, relative):
    kind, name = relative.split("/", 1)
    return bundle / (f"jniLibs/arm64-v8a/{name}" if kind == "lib" else f"assets/corevision/{relative}")


def assert_arm64_elf(stream, name):
    header = stream.read(64)
    if len(header) != 64 or header[:6] != b"\x7fELF\x02\x01" or struct.unpack_from("<HH", header, 16) != (3, 183):
        raise ValueError(f"Expected an ARM64 ELF ET_DYN file for {name}")
    return header


def assert_bridge_elf(stream, name):
    header = assert_arm64_elf(stream, name)
    program_offset = struct.unpack_from("<Q", header, 32)[0]
    entry_size, entry_count = struct.unpack_from("<HH", header, 54)
    entry = struct.Struct("<IIQQQQQQ")
    if program_offset < len(header) or entry_size != entry.size:
        raise ValueError(f"Invalid ELF program header table for {name}")

    stream.seek(program_offset)
    load_count = 0
    for entry_index in range(entry_count):
        data = stream.read(entry_size)
        if len(data) != entry_size:
            raise ValueError(f"Truncated ELF program header {entry_index} for {name}")
        kind, _, offset, address, _, _, _, alignment = entry.unpack(data)
        if kind != 1:
            continue
        load_count += 1
        if alignment < 16 * 1024 or alignment & (alignment - 1) or (address - offset) % alignment:
            raise ValueError(f"Expected a 16 KiB-aligned ELF LOAD segment for {name}")
    if load_count == 0:
        raise ValueError(f"No ELF LOAD segments for {name}")


def prepare(args):
    output = args.output.resolve()
    if output == ROOT or ROOT in output.parents:
        raise ValueError("Place the host runtime bundle outside the source tree")
    if output.exists():
        raise ValueError("Choose a new output directory")

    sources = {f"lib/{LOADER}": args.loader}
    for path in args.libs.iterdir():
        if not path.is_file() or not LIBRARY.fullmatch(path.name) or path.name == "libc.so" or path.name.startswith("libcorevision_"):
            raise ValueError(f"Unexpected entry in the flat runtime library directory: {path.name}")
        sources[f"lib/{path.name}"] = path
    profiles = (PROFILE, FACE_PROFILE) if args.include_face or args.face_comparison_model else (PROFILE,)
    for relative in (path for profile in profiles for path in parse_manifest(profile.read_bytes())):
        if relative.startswith("models/"):
            sources[relative] = args.models / relative.split("/", 1)[1]
    if args.face_comparison_model is not None:
        for relative in parse_manifest(COMPARISON_PROFILE.read_bytes()):
            sources[relative] = args.face_comparison_model

    with tempfile.TemporaryDirectory(prefix=".corevision-runtime-", dir=output.parent) as temporary:
        draft = Path(temporary) / "bundle"
        checksums = {}
        for relative, origin in sorted(sources.items()):
            destination = bundle_path(draft, relative)
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(origin, destination)
            with destination.open("rb") as content:
                if relative.startswith("lib/"):
                    assert_arm64_elf(content, relative)
                    content.seek(0)
                checksums[relative] = checksum(content)
        verify_pin_hashes(checksums)
        (draft / MANIFEST).write_text("".join(f"{digest}  {path}\n" for path, digest in checksums.items()), encoding="ascii")
        draft.rename(output)
    print(f"Prepared {len(checksums)} verified host runtime files in {output}")


def verify(bundle):
    entries = parse_manifest((bundle / MANIFEST).read_bytes())
    verify_pin_hashes(entries)
    expected = {bundle_path(bundle, relative) for relative in entries}
    actual = {path for path in bundle.rglob("*") if path.is_file()}
    if actual != expected | {bundle / MANIFEST}:
        raise ValueError("Runtime bundle files differ from the SHA-256 manifest")
    for relative, digest in entries.items():
        with bundle_path(bundle, relative).open("rb") as content:
            if relative.startswith("lib/"):
                assert_arm64_elf(content, relative)
                content.seek(0)
            if checksum(content) != digest:
                raise ValueError(f"Runtime SHA-256 mismatch for {relative}")
    return entries


def verify_apk(bundle, apk):
    entries = verify(bundle)
    with ZipFile(apk) as archive:
        if len(archive.namelist()) != len(set(archive.namelist())):
            raise ValueError("APK contains duplicate entries")
        if archive.read(MANIFEST) != (bundle / MANIFEST).read_bytes():
            raise ValueError("APK runtime hash manifest differs from the verified bundle")
        for relative, digest in entries.items():
            kind, name = relative.split("/", 1)
            path = f"lib/arm64-v8a/{name}" if kind == "lib" else f"assets/corevision/{relative}"
            with archive.open(path) as content:
                if checksum(content) != digest:
                    raise ValueError(f"APK SHA-256 mismatch for {path}")
        for basename in BRIDGES:
            with archive.open(f"lib/arm64-v8a/{basename}") as content:
                assert_bridge_elf(content, basename)
    print(f"Verified both 16 KiB-aligned bridges and all {len(entries)} original runtime files in the APK")


def verify_aar(aar):
    with ZipFile(aar) as archive:
        names = {name for name in archive.namelist() if not name.endswith("/")}
        profiles = {f"assets/corevision/{profile.name}": profile
                    for profile in (PROFILE, FACE_PROFILE, COMPARISON_PROFILE)}
        required = {"AndroidManifest.xml", "classes.jar", *profiles}
        expected = {f"jni/arm64-v8a/{name}" for name in BRIDGES}
        if not required <= names or names & {MANIFEST} or {name for name in names if name.startswith("jni/")} != expected:
            raise ValueError("Public AAR must contain just the self-developed ARM64 bridges and the ABI profile")
        if {name for name in names if name.startswith("assets/")} != profiles.keys():
            raise ValueError("Public AAR contains an unexpected asset")
        for name, profile in profiles.items():
            if archive.read(name) != profile.read_bytes():
                raise ValueError(f"Public AAR contains an unexpected profile {name}")
        for name in expected:
            with archive.open(name) as content:
                assert_bridge_elf(content, name)
    print("Verified public AAR contents and 16 KiB-aligned ARM64 ELF segments")


def main():
    parser = argparse.ArgumentParser(description="Prepare and verify a locally authorized Core Vision runtime bundle")
    commands = parser.add_subparsers(dest="command", required=True)
    create = commands.add_parser("prepare")
    for argument in ("loader", "libs", "models", "output"):
        create.add_argument(f"--{argument}", required=True, type=Path)
    create.add_argument("--include-face", action="store_true", help="also include the verified face detection models")
    create.add_argument("--face-comparison-model", type=Path, help="include the original face comparison model and face detection")
    commands.add_parser("verify").add_argument("bundle", type=Path)
    apk = commands.add_parser("verify-apk")
    apk.add_argument("bundle", type=Path)
    apk.add_argument("apk", type=Path)
    commands.add_parser("verify-aar").add_argument("aar", type=Path)
    args = parser.parse_args()
    try:
        if args.command == "prepare":
            prepare(args)
        elif args.command == "verify":
            print(f"Verified {len(verify(args.bundle))} host runtime files")
        elif args.command == "verify-apk":
            verify_apk(args.bundle, args.apk)
        else:
            verify_aar(args.aar)
    except (OSError, ValueError, KeyError) as error:
        print(f"Core Vision runtime verification failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())

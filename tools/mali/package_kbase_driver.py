#!/usr/bin/env python3
"""Package an AArch64 glibc PanVK-Kbase build for DroidDeck Mali-first.

Schema 4 adds per-file SHA-256 payload hashes and release/profile provenance. The app still treats
frontend/UAPI/product-id matching as the compatibility authority; a filename is never enough to
claim that an ICD can drive a particular Mali.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import struct
import sys
import zipfile


def parse_int(value: str) -> int:
    return int(value, 0)


def sha256(path: pathlib.Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def check_aarch64_elf(path: pathlib.Path) -> bytes:
    data = path.read_bytes()
    if len(data) < 20 or data[:4] != b"\x7fELF" or data[4] != 2 or data[5] != 1:
        raise SystemExit(f"{path}: not a 64-bit little-endian ELF")
    machine = struct.unpack_from("<H", data, 18)[0]
    if machine != 0xB7:
        raise SystemExit(f"{path}: ELF machine is 0x{machine:x}, expected AArch64 (0xb7)")
    return data


def check_aarch64_glibc_icd(path: pathlib.Path) -> None:
    data = check_aarch64_elf(path)
    if b"libc.so.6" not in data:
        raise SystemExit(f"{path}: does not look like a glibc ICD (libc.so.6 missing)")


def check_helper(path: pathlib.Path) -> None:
    data = check_aarch64_elf(path)
    # A helper need not depend on libc directly. Reject it only when it explicitly names Android's
    # libc and never names glibc, which is strong evidence that a Bionic helper was mixed in.
    if b"libc.so\x00" in data and b"libc.so.6" not in data:
        raise SystemExit(f"{path}: helper appears to target Android/Bionic rather than glibc")


def canonical_product_id(value: int) -> int:
    if value <= 0:
        return 0
    if value <= 0xFFFF:
        return value
    return (value >> 16) & 0xFFFF


def uapi(value: str) -> tuple[int, int]:
    try:
        major, minor = value.split(".", 1)
        return int(major), int(minor)
    except Exception as exc:
        raise argparse.ArgumentTypeError("UAPI must look like 1.21 or 11.0") from exc


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--lib", required=True, type=pathlib.Path, help="glibc libvulkan_*.so")
    ap.add_argument("--out", required=True, type=pathlib.Path, help="output .zip")
    ap.add_argument("--name", required=True, help="display name")
    ap.add_argument("--version", default="", help="driver/release version")
    ap.add_argument("--profile", default="", help="profile id, for example g615-v11-csf")
    ap.add_argument("--profile-file", type=pathlib.Path, help="DroidDeck profile JSON; fills frontend/UAPI/product ids/provenance")
    ap.add_argument("--pan-arch", type=int, help="Pan architecture generation, for example 11")
    ap.add_argument("--frontend", choices=("csf", "jm"))
    ap.add_argument("--uapi", type=uapi, metavar="MAJOR.MINOR")
    ap.add_argument("--min-uapi-minor", type=int, help="override minimum compatible UAPI minor")
    ap.add_argument("--max-uapi-minor", type=int, help="override maximum compatible UAPI minor")
    ap.add_argument("--product-id", action="append", default=[], type=parse_int,
                    help="Kbase GPU product ID, repeatable (for example 0xb8a3)")
    ap.add_argument("--helper", action="append", default=[], type=pathlib.Path,
                    help="helper .so placed beside the ICD, repeatable")
    ap.add_argument("--api-version", default="1.4.0", help="ICD manifest API version")
    ap.add_argument("--min-glibc", default="", help="minimum glibc version if known")
    ap.add_argument("--source-repo", default="", help="source repository provenance")
    ap.add_argument("--source-tag", default="", help="immutable source/release tag")
    ap.add_argument("--qualification", default="", choices=("", "dev", "alpha", "beta", "rc", "stable", "release"))
    ap.add_argument("--tested-device", default="", help="device on which this exact profile was validated")
    args = ap.parse_args()

    if not args.lib.is_file():
        raise SystemExit(f"missing ICD: {args.lib}")
    if not args.lib.name.startswith("libvulkan_") or not args.lib.name.endswith(".so"):
        raise SystemExit("ICD basename must be libvulkan_*.so")
    check_aarch64_glibc_icd(args.lib)
    for helper in args.helper:
        if not helper.is_file():
            raise SystemExit(f"missing helper: {helper}")
        check_helper(helper)

    profile_data = {}
    if args.profile_file:
        if not args.profile_file.is_file():
            raise SystemExit(f"missing profile JSON: {args.profile_file}")
        profile_data = json.loads(args.profile_file.read_text())
        args.profile = args.profile or str(profile_data.get("id", ""))
        args.pan_arch = args.pan_arch if args.pan_arch is not None else profile_data.get("panArch")
        args.frontend = args.frontend or profile_data.get("frontend")
        if args.uapi is None and profile_data.get("uapiMajor") is not None:
            # The exact probe value is not a package requirement; use the profile minimum as the
            # seed and preserve the explicit min/max range below.
            args.uapi = (int(profile_data["uapiMajor"]), int(profile_data.get("minUapiMinor", 0)))
        if not args.product_id:
            args.product_id = [parse_int(str(x)) for x in profile_data.get("productIds", [])]
        args.tested_device = args.tested_device or str(profile_data.get("referenceDevice", ""))
        args.source_repo = args.source_repo or str(profile_data.get("releaseSource", ""))
        if args.min_uapi_minor is None and profile_data.get("minUapiMinor") is not None:
            args.min_uapi_minor = int(profile_data["minUapiMinor"])
        if args.max_uapi_minor is None and profile_data.get("maxUapiMinor") is not None:
            args.max_uapi_minor = int(profile_data["maxUapiMinor"])

    if args.frontend not in ("csf", "jm"):
        raise SystemExit("--frontend is required unless --profile-file supplies it")
    if args.uapi is None:
        raise SystemExit("--uapi is required unless --profile-file supplies UAPI metadata")

    files = [args.lib, *args.helper]
    basenames = [f.name for f in files]
    if len(set(basenames)) != len(basenames):
        raise SystemExit("duplicate basename in package")

    major, minor = args.uapi
    min_minor = minor if args.min_uapi_minor is None else args.min_uapi_minor
    max_minor = minor if args.max_uapi_minor is None else args.max_uapi_minor
    if min_minor < 0 or max_minor < min_minor:
        raise SystemExit("invalid UAPI minor range")

    meta = {
        "schemaVersion": 4,
        "kind": "linux-vulkan-icd",
        "name": args.name,
        "driverVersion": args.version,
        "apiVersion": args.api_version,
        "abi": "linux-aarch64-glibc",
        "libc": "glibc",
        "minGlibc": args.min_glibc,
        "libraryName": args.lib.name,
        "gpuFamily": "mali",
        "guestBackend": "mali-kbase",
        "maliKbaseFrontend": args.frontend,
        "maliKbaseUapiMajor": major,
        "maliKbaseMinUapiMinor": min_minor,
        "maliKbaseMaxUapiMinor": max_minor,
        "filesSha256": {f.name: sha256(f) for f in files},
    }
    if args.product_id:
        canonical_ids = [canonical_product_id(x) for x in args.product_id]
        canonical_ids = [x for x in dict.fromkeys(canonical_ids) if x]
        meta["maliProductIds"] = [f"0x{x:04x}" for x in canonical_ids]
    if args.profile:
        meta["maliProfile"] = args.profile
    if args.pan_arch is not None:
        meta["maliPanArch"] = args.pan_arch
    if args.source_repo:
        meta["sourceRepo"] = args.source_repo
    if args.source_tag:
        meta["sourceTag"] = args.source_tag
    if args.qualification:
        meta["qualification"] = args.qualification
    if args.tested_device:
        meta["testedDevice"] = args.tested_device

    args.out.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(args.out, "w", compression=zipfile.ZIP_DEFLATED) as zf:
        zf.writestr("meta.json", json.dumps(meta, indent=2) + "\n")
        for file in files:
            zf.write(file, file.name)

    print(f"wrote {args.out}")
    print(json.dumps(meta, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())

#!/usr/bin/env python3
"""Read-only preflight for removing dimensions from a stopped Java 26.3 dedicated-server copy."""

import argparse
from io import BytesIO
import json
from pathlib import Path
import re
import stat
import struct
import sys
import zlib

DATA_VERSION = 5023
MAX_NBT = 8 * 1024 * 1024
MAX_DEPTH = 128
DIMENSION = re.compile(r"[a-z0-9_.-]+:[a-z0-9/._-]+")
PLAYER_FILE = re.compile(r"([a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12})\.dat(_old)?")
FIXED = {1: 1, 2: 2, 3: 4, 4: 8, 5: 4, 6: 8}
ARRAY = {7: 1, 11: 4, 12: 8}


class InvalidInput(ValueError):
    """An input could not be verified."""


class _ArgumentParser(argparse.ArgumentParser):
    def error(self, message):
        # argparse's default writes unknown switches/operands verbatim.
        raise InvalidInput("invalid arguments; use --help") from None


def _mutf8(data):
    """Strictly decode Java's modified UTF-8 (UTF-16 code units, no four-byte encoding)."""
    if data.isascii() and b"\x00" not in data:
        return data.decode("ascii")
    units = bytearray()
    i = 0
    while i < len(data):
        first = data[i]
        if 1 <= first <= 0x7f:
            n, value = 1, first
        elif 0xc0 <= first <= 0xdf:
            n, value = 2, first & 0x1f
        elif 0xe0 <= first <= 0xef:
            n, value = 3, first & 0x0f
        else:
            raise InvalidInput("invalid modified UTF-8")
        if i + n > len(data):
            raise InvalidInput("truncated modified UTF-8")
        for byte in data[i + 1:i + n]:
            if byte & 0xc0 != 0x80:
                raise InvalidInput("invalid modified UTF-8 continuation")
            value = (value << 6) | (byte & 0x3f)
        if (n == 2 and 0 < value < 0x80) or (n == 3 and value < 0x800):
            raise InvalidInput("noncanonical modified UTF-8")
        units.extend(value.to_bytes(2, "big"))
        i += n
    return units.decode("utf-16-be", errors="surrogatepass")


class _NBT:
    """Structure-only NBT cursor; retain only requested primitive/compound fields."""

    def __init__(self, data):
        self.data = BytesIO(data)

    def read(self, n):
        if n < 0 or n > MAX_NBT:
            raise InvalidInput("invalid NBT length")
        data = self.data.read(n)
        if len(data) != n:
            raise InvalidInput("truncated NBT")
        return data

    def value(self, fmt):
        return struct.unpack(">" + fmt, self.read(struct.calcsize(">" + fmt)))[0]

    def string(self):
        return _mutf8(self.read(self.value("H")))

    def root(self, fields):
        if self.value("B") != 10:
            raise InvalidInput("NBT root must be a compound")
        self.string()
        result = self.payload(10, 0, fields)
        if self.data.read(1):
            raise InvalidInput("trailing bytes after NBT root")
        return result

    def payload(self, tag, depth, wanted=None):
        if depth > MAX_DEPTH:
            raise InvalidInput("NBT nesting limit exceeded")
        if tag in FIXED:
            data = self.read(FIXED[tag])
            if wanted == 3:
                return struct.unpack(">i", data)[0]
        elif tag in ARRAY:
            self.read(self.value("i") * ARRAY[tag])
        elif tag == 8:
            value = self.string()
            if wanted == 8:
                return value
        elif tag == 9:
            item, count = self.value("B"), self.value("i")
            if item > 12 or count < 0 or (item == 0 and count != 0):
                raise InvalidInput("invalid NBT list")
            if item in FIXED:
                self.read(count * FIXED[item])
            else:
                # Every nonnumeric NBT payload consumes at least one byte.
                if count > MAX_NBT:
                    raise InvalidInput("NBT list length exceeds limit")
                for _ in range(count):
                    self.payload(item, depth + 1)
        elif tag == 10:
            result, names = {}, set()
            while child := self.value("B"):
                name = self.string()
                if name in names:
                    raise InvalidInput("duplicate NBT field")
                names.add(name)
                expectation = wanted.get(name) if isinstance(wanted, dict) else None
                if expectation is not None:
                    expected_tag = 10 if isinstance(expectation, dict) else expectation
                    if expected_tag and child != expected_tag:
                        raise InvalidInput("wrong NBT field type")
                value = self.payload(child, depth + 1, expectation)
                if expectation is not None:
                    result[name] = value
            return result if isinstance(wanted, dict) else None
        else:
            raise InvalidInput("unknown NBT tag")
        return None


def _plain(path, check):
    info = path.lstat()
    if not check(info.st_mode) or getattr(path, "is_junction", lambda: False)():
        raise InvalidInput("expected ordinary file or directory (no links)")
    return info


def _nbt(path, fields):
    if _plain(path, stat.S_ISREG).st_size > MAX_NBT:
        raise InvalidInput("compressed NBT size exceeds limit")
    with path.open("rb") as source:
        encoded = source.read(MAX_NBT + 1)
    if len(encoded) > MAX_NBT:
        raise InvalidInput("compressed NBT size exceeds limit")
    # zlib validates the optional gzip header CRC as well as the trailer.
    # Accept one complete member; do not clear an ambiguous or truncated file.
    inflater = zlib.decompressobj(16 + zlib.MAX_WBITS)
    data = inflater.decompress(encoded, MAX_NBT + 1)
    if len(data) > MAX_NBT:
        raise InvalidInput("expanded NBT size exceeds limit")
    if not inflater.eof or inflater.unused_data or inflater.unconsumed_tail:
        raise InvalidInput("expected one complete gzip member")
    return _NBT(data).root(fields)


def _inventory(path):
    if _plain(path, stat.S_ISREG).st_size > 1024 * 1024:
        raise InvalidInput("dimension inventory size exceeds limit")
    values = json.loads(path.read_text(encoding="utf-8"))
    if (not isinstance(values, list) or not all(isinstance(v, str) and DIMENSION.fullmatch(v) for v in values)
            or len(set(values)) != len(values) or "minecraft:overworld" not in values):
        raise InvalidInput("inventory must be unique explicit dimension IDs including minecraft:overworld")
    return set(values)


def scan(world, target_inventory, show_files=False, show_dimensions=False):
    """Classify references without changing the copy. Inputs are assumed stopped and stable."""
    target = _inventory(target_inventory)
    _plain(world, stat.S_ISDIR)
    for legacy in (world / "playerdata", world / "players" / "playerdata"):
        if legacy.exists() or legacy.is_symlink():
            raise InvalidInput("legacy player-data directory present; cannot verify all saves")
    level = _nbt(world / "level.dat", {"Data": {"DataVersion": 3, "Player": 0}}).get("Data", {})
    if level.get("DataVersion") != DATA_VERSION or "Player" in level:
        raise InvalidInput("expected Java 26.3 dedicated-server level.dat, not an older or single-player save")
    _plain(world / "players", stat.S_ISDIR)
    playerdir = world / "players" / "data"
    _plain(playerdir, stat.S_ISDIR)

    grouped = {}
    findings = []
    for path in sorted(playerdir.iterdir()):
        match = PLAYER_FILE.fullmatch(path.name)
        if match:
            grouped.setdefault(match[1], {})[bool(match[2])] = path
        elif path.name.lower().endswith((".dat", ".dat_old")):
            findings.append({"status": "UNVERIFIED_FILENAME", **({"file": path.name} if show_files else {})})

    for idx, files in enumerate(grouped.values(), start=1):
        primary, backup = files.get(False), files.get(True)
        result = {"record": idx, "backup_present": backup is not None}
        if show_files:
            result["file"] = (primary or backup).name
        if primary is None:
            result["status"] = "MISSING_PRIMARY"
        else:
            try:
                root = _nbt(primary, {"DataVersion": 3, "Dimension": 8})
                if root.get("DataVersion") != DATA_VERSION:
                    result["status"] = "UNVERIFIED_VERSION"
                elif not DIMENSION.fullmatch(root.get("Dimension", "")):
                    result["status"] = "UNVERIFIED_DIMENSION"
                elif root["Dimension"] not in target:
                    result["status"] = "DIMENSION_MISSING"
                    if show_dimensions:
                        result["dimension"] = root["Dimension"]
                else:
                    result["status"] = "REFERENCE_PRESENT"
            except (OSError, ValueError, EOFError, zlib.error):
                result["status"] = "UNREADABLE_PRIMARY"
        findings.append(result)
    return findings


def main(argv=None):
    parser = _ArgumentParser(description=__doc__)
    parser.add_argument("world", type=Path, help="stopped dedicated-server world COPY root")
    parser.add_argument("target_inventory", type=Path, help="JSON array of effective post-change dimension IDs")
    parser.add_argument("--show-files", action="store_true", help="locally disclose player filenames (UUIDs)")
    parser.add_argument("--show-dimensions", action="store_true", help="locally disclose missing dimension IDs")
    try:
        args = parser.parse_args(argv)
        findings = scan(args.world, args.target_inventory, args.show_files, args.show_dimensions)
    except (OSError, ValueError, EOFError, RecursionError, zlib.error) as exc:
        # Avoid printing paths, UUIDs, or file contents by default.
        reason = str(exc) if isinstance(exc, InvalidInput) else type(exc).__name__
        print(json.dumps({"status": "INPUT_UNVERIFIED", "reason": reason}))
        return 2
    blocked = any(f["status"] != "REFERENCE_PRESENT" for f in findings)
    print(json.dumps({"status": "BLOCK" if blocked else "NO_MISSING_REFERENCES_FOUND", "records": findings}))
    return 1 if blocked else 0


if __name__ == "__main__":
    sys.exit(main())

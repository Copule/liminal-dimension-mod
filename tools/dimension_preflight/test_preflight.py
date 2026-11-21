import contextlib
import gzip
import hashlib
from io import StringIO
import json
from pathlib import Path
import struct
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import zlib

import preflight as p

PLAYER = "01234567-1234-1234-1234-0123456789ab"
OTHER = "12345678-1234-1234-1234-0123456789ab"
OVERWORLD = "minecraft:overworld"
PROBE = "liminal_dimension_poc:probe"


def _string(text):
    if isinstance(text, str):
        text = text.encode("ascii")
    return struct.pack(">H", len(text)) + text


def _field(tag, name, payload):
    return bytes([tag]) + _string(name) + payload


def _version(version=5023):
    return _field(3, "DataVersion", struct.pack(">i", version))


def _dimension(value):
    return _field(8, "Dimension", _string(value))


def _root(*fields):
    return b"\x0a\x00\x00" + b"".join(fields) + b"\x00"


class PreflightTest(unittest.TestCase):
    def setUp(self):
        self.root = Path(self.enterContext(tempfile.TemporaryDirectory()))
        self.world = self.root / "world-copy"
        self.data = self.world / "players" / "data"
        self.data.mkdir(parents=True)
        self.inventory = self.root / "target.json"
        self.set_inventory([OVERWORLD])
        self.level()

    def set_inventory(self, content):
        self.inventory.write_text(json.dumps(content), encoding="utf-8")

    def level(self, *fields, version=5023):
        tag = _field(10, "Data", _version(version) + b"".join(fields) + b"\x00")
        (self.world / "level.dat").write_bytes(gzip.compress(_root(tag)))

    def put(self, nbt, filename=PLAYER + ".dat"):
        file = self.data / filename
        file.write_bytes(gzip.compress(nbt))
        return file

    def result(self, *args):
        printed = StringIO()
        with contextlib.redirect_stdout(printed):
            code = p.main([str(self.world), str(self.inventory), *args])
        return code, json.loads(printed.getvalue())

    def single(self, status):
        code, report = self.result()
        self.assertEqual(code, 0 if status == "REFERENCE_PRESENT" else 1)
        self.assertEqual(report["records"][0]["status"], status)
        self.assertNotIn(PLAYER, json.dumps(report))

    def test_valid_primary_and_explicit_registry(self):
        self.put(_root(_version(), _dimension(PROBE)))
        self.set_inventory([OVERWORLD, PROBE])
        self.single("REFERENCE_PRESENT")

    def test_active_player_in_removed_dimension_blocks_even_with_safe_old(self):
        self.put(_root(_version(), _dimension(PROBE)))
        self.put(_root(_version(), _dimension(OVERWORLD)), PLAYER + ".dat_old")
        # A retained dimension directory is not evidence of future availability.
        (self.world / "dimensions" / "liminal_dimension_poc" / "probe").mkdir(parents=True)
        self.single("DIMENSION_MISSING")

    def test_valid_primary_wins_over_older_missing_reference(self):
        self.put(_root(_version(), _dimension(OVERWORLD)))
        self.put(_root(_version(), _dimension(PROBE)), PLAYER + ".dat_old")
        self.single("REFERENCE_PRESENT")

    def test_backup_does_not_clear_missing_primary(self):
        self.put(_root(_version(), _dimension(OVERWORLD)), PLAYER + ".dat_old")
        self.single("MISSING_PRIMARY")

    def test_backup_does_not_clear_corrupt_primary(self):
        (self.data / (PLAYER + ".dat")).write_bytes(b"not gzip")
        self.put(_root(_version(), _dimension(OVERWORLD)), PLAYER + ".dat_old")
        self.single("UNREADABLE_PRIMARY")

    def test_gzip_crc_corruption_blocks(self):
        file = self.put(_root(_version(), _dimension(OVERWORLD)))
        damaged = bytearray(file.read_bytes())
        damaged[-8] ^= 1
        file.write_bytes(damaged)
        self.single("UNREADABLE_PRIMARY")

    def test_optional_gzip_header_crc_must_match(self):
        file = self.put(_root(_version(), _dimension(OVERWORLD)))
        self.put(_root(_version(), _dimension(PROBE)), PLAYER + ".dat_old")
        encoded = file.read_bytes()
        header = bytearray(encoded[:10])
        header[3] |= 2  # FHCRC
        valid_crc = zlib.crc32(header) & 0xffff
        file.write_bytes(header + struct.pack("<H", valid_crc) + encoded[10:])
        self.single("REFERENCE_PRESENT")
        file.write_bytes(header + struct.pack("<H", valid_crc ^ 1) + encoded[10:])
        self.single("UNREADABLE_PRIMARY")

    def test_extra_gzip_member_or_incomplete_trailer_blocks(self):
        file = self.put(_root(_version(), _dimension(OVERWORLD)))
        encoded = file.read_bytes()
        for altered in (encoded + gzip.compress(b""), encoded + b"\x00", encoded[:-1]):
            file.write_bytes(altered)
            self.single("UNREADABLE_PRIMARY")

    def test_truncated_nbt_inside_valid_gzip_blocks(self):
        self.put(_root(_version(), _dimension(OVERWORLD))[:-1])
        self.single("UNREADABLE_PRIMARY")

    def test_duplicate_dimension_does_not_hide_bad_value(self):
        self.put(_root(_version(), _dimension(PROBE), _dimension(OVERWORLD)))
        self.single("UNREADABLE_PRIMARY")

    def test_nested_dimension_cannot_stand_in_for_primary_reference(self):
        self.put(_root(_version(), _field(10, "respawn", _dimension(OVERWORLD) + b"\x00")))
        self.single("UNVERIFIED_DIMENSION")

    def test_wrong_data_version_blocks(self):
        self.put(_root(_version(5024), _dimension(OVERWORLD)))
        self.single("UNVERIFIED_VERSION")

    def test_missing_data_version_blocks(self):
        self.put(_root(_dimension(OVERWORLD)))
        self.single("UNVERIFIED_VERSION")

    def test_non_namespaced_or_wrong_type_dimension_never_clears(self):
        self.put(_root(_version(), _dimension("unknown")))
        self.single("UNVERIFIED_DIMENSION")
        self.put(_root(_version(), _field(3, "Dimension", struct.pack(">i", 0))))
        self.single("UNREADABLE_PRIMARY")

    def test_complex_tags_with_mutf8_do_not_change_result(self):
        # Java writeUTF encodes NUL as C0 80 and 😀 as a six-byte surrogate pair.
        text = b"\xc0\x80\xed\xa0\xbd\xed\xb8\x80"
        pos = b"\x06" + struct.pack(">i3d", 3, 1.5, 2.5, 3.5)
        uuids = _field(11, "UUID", struct.pack(">5i", 4, 4, 3, 2, 1))
        item = _field(8, "text", _string(text)) + uuids + b"\x00"
        items = _field(9, "Inventory", b"\x0a" + struct.pack(">i", 1) + item)
        self.put(_root(_version(), _dimension(OVERWORLD), _field(9, "Pos", pos), items))
        self.single("REFERENCE_PRESENT")

    def test_trailing_or_malformed_nested_data_blocks(self):
        good = _root(_version(), _dimension(OVERWORLD))
        self.put(good + b"\x00")
        self.single("UNREADABLE_PRIMARY")
        self.put(_root(_version(), _dimension(OVERWORLD), _field(9, "Pos", b"\x06" + struct.pack(">i", -1))))
        self.single("UNREADABLE_PRIMARY")

    def test_unrecognized_player_file_never_silently_skipped(self):
        self.put(_root(_version(), _dimension(OVERWORLD)), "unrecognized.dat")
        self.single("UNVERIFIED_FILENAME")

    def test_bad_inventories_block(self):
        for candidate in ([], [PROBE], [OVERWORLD, OVERWORLD], [OVERWORLD, 7], {"dimensions": [OVERWORLD]}):
            with self.subTest(candidate=candidate):
                self.set_inventory(candidate)
                self.assertEqual(self.result()[0], 2)
        self.inventory.write_text("[", encoding="utf-8")
        self.assertEqual(self.result()[0], 2)

    def test_level_schema_and_missing_playerdir_rejected(self):
        self.level(_field(10, "Player", b"\x00"))
        self.assertEqual(self.result()[0], 2)
        self.level(version=5022)
        self.assertEqual(self.result()[0], 2)
        self.level()
        self.data.rmdir()
        self.assertEqual(self.result()[0], 2)

    def test_explicit_empty_26_3_directory_has_no_references(self):
        self.assertEqual(self.result(), (0, {"status": "NO_MISSING_REFERENCES_FOUND", "records": []}))

    def test_default_redaction_and_read_only(self):
        self.put(_root(_version(), _dimension(PROBE)))
        self.put(_root(_version(), _dimension(OVERWORLD)), OTHER + ".dat")
        digest = lambda: {f: hashlib.sha256(f.read_bytes()).hexdigest() for f in self.root.rglob("*") if f.is_file()}
        before = digest()
        code, report = self.result()
        self.assertEqual(code, 1)
        self.assertNotIn(PLAYER, json.dumps(report))
        self.assertNotIn(OTHER, json.dumps(report))
        self.assertEqual([r["record"] for r in report["records"]], [1, 2])
        self.assertEqual(self.result("--show-files")[1]["records"][0]["file"], PLAYER + ".dat")
        self.assertEqual(digest(), before)

    def test_dimension_ids_only_appear_with_opt_in(self):
        missing = "custom:rooms/" + PLAYER
        self.put(_root(_version(), _dimension(missing)))
        self.single("DIMENSION_MISSING")
        self.assertNotIn("dimension", self.result("--show-files")[1]["records"][0])
        record = self.result("--show-dimensions")[1]["records"][0]
        self.assertEqual(record["dimension"], missing)
        self.assertNotIn("file", record)

    def test_cli_argument_error_is_json_without_raw_operands(self):
        run = subprocess.run([sys.executable, str(Path(p.__file__)), str(self.world),
                              str(self.inventory), "/private/SENTINEL.dat"],
                             capture_output=True, text=True, timeout=10)
        self.assertEqual(run.returncode, 2)
        self.assertEqual(json.loads(run.stdout)["status"], "INPUT_UNVERIFIED")
        self.assertNotIn("SENTINEL", run.stdout + run.stderr)

    def test_expanded_resource_limit_blocks(self):
        oversized = _field(7, "padding", struct.pack(">i", 600) + bytes(600))
        self.put(_root(_version(), _dimension(OVERWORLD), oversized))
        with patch.object(p, "MAX_NBT", 512):
            self.single("UNREADABLE_PRIMARY")

    def test_legacy_player_storage_cannot_be_silently_ignored(self):
        (self.world / "playerdata").mkdir()
        self.assertEqual(self.result()[0], 2)

    def test_symbolic_primary_is_refused(self):
        target = self.put(_root(_version(), _dimension(OVERWORLD)), "target.non-player")
        try:
            (self.data / (PLAYER + ".dat")).symlink_to(target)
        except (NotImplementedError, OSError):
            self.skipTest("symlink unavailable")
        self.single("UNREADABLE_PRIMARY")

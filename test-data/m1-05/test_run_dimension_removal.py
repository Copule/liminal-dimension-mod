"""Exercise the acceptance gate without starting a server."""

import gzip
from io import BytesIO
import json
from pathlib import Path
import re
from subprocess import TimeoutExpired
from tempfile import TemporaryDirectory
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import zipfile

import run_dimension_removal as runner


def write_level_dat(world, data=None):
    if data is None:
        data = runner.nbtlib.Compound({"DataVersion": runner.nbtlib.Int(1)})
    runner.nbtlib.File({"Data": data}, gzipped=True).save(world / "level.dat")


class RemovalGateTest(unittest.TestCase):
    def setUp(self):
        root = Path(self.enterContext(TemporaryDirectory()))
        self.args = SimpleNamespace(runtime=root / "runtime", evidence=root / "evidence",
                                    pack=root / "source-pack", java=root / "java.exe", port=25576)
        self.world = self.args.runtime / "world"
        self.world.mkdir(parents=True)
        write_level_dat(self.world)
        self.args.evidence.mkdir()
        self.args.pack.mkdir()
        (self.args.pack / "pack.mcmeta").write_text("{}", encoding="utf-8")
        self.installed_pack = self.world / "datapacks" / runner.PACK
        self.probe_present = False
        self.extra_packs = []

        self.popen = self.enterContext(patch.object(runner.subprocess, "Popen"))
        self.process = self.popen.return_value
        self.process.poll.return_value = None
        self.process.wait.return_value = 0
        self.console = self.enterContext(patch.object(runner, "Console")).return_value
        self.console.wait.return_value = "Done (0.100s)!"
        self.console.command.side_effect = self.command_response

    def command_response(self, command, pattern):
        if command == "datapack list enabled":
            packs = ["[vanilla (built-in)]", "[mod_data]", *self.extra_packs]
            if self.installed_pack.exists():
                packs.append(f"[{runner.PACK_ID} (world)]")
            response = f"There are {len(packs)} data pack(s) enabled: {', '.join(packs)}"
        elif command == f"execute in {runner.PROBE} if loaded {runner.COORDS}":
            # An existing dimension can be recognized before its probe chunk loads.
            response = "Test failed" if self.probe_present else f"Unknown dimension '{runner.PROBE}'"
        elif command == "save-all flush":
            response = "Saved the game"
        else:
            self.fail(f"Unexpected command: {command}")
        self.assertRegex(response, pattern)
        return response

    def read_result(self, name="A03-removed", *, backup=True):
        phase = self.args.evidence / name
        self.assertEqual((phase / "world.zip").is_file(), backup)
        self.assertEqual((phase / "backup.json").is_file(), backup)
        self.assertTrue((phase / "files.json").is_file())
        return json.loads((phase / "result.json").read_text(encoding="utf-8"))

    def failed_phase(self, **options):
        with self.assertRaisesRegex(RuntimeError, r"A03-removed failed.*result\.json"):
            runner.run_phase(self.args, "A03-removed", self.world.name, **options)
        return self.read_result(backup=False)

    def test_not_ready_with_zero_exit_fails(self):
        self.console.wait.return_value = "Failed to start the minecraft server"
        result = self.failed_phase()
        self.assertFalse(result["ready"])
        self.assertEqual(result["exit_code"], 0)

    def test_failed_probes_fail_the_phase(self):
        self.console.block.side_effect = RuntimeError("Test failed")
        result = self.failed_phase()
        self.assertTrue(result["ready"])
        self.assertFalse(result["probes_passed"])
        self.console.send.assert_called_once_with("stop")

    def test_nonzero_exit_fails_even_when_probes_pass(self):
        self.process.wait.return_value = 1
        result = self.failed_phase()
        self.assertTrue(result["probes_passed"])
        self.assertEqual(result["exit_code"], 1)

    def test_forced_termination_fails_even_with_zero_exit(self):
        self.process.wait.side_effect = [TimeoutExpired("java", 70), 0]
        result = self.failed_phase()
        self.assertTrue(result["probes_passed"])
        self.assertTrue(result["forced_termination"])
        self.assertEqual(result["exit_code"], 0)
        self.process.kill.assert_called_once()

    def test_unexpected_probe_fails(self):
        self.probe_present = True
        result = self.failed_phase()
        self.assertIs(result["probe_registered"], True)
        self.assertIn("Expected probe_registered=False", result["error"])

    def test_missing_expected_probe_fails(self):
        runner.install_pack(self.args.pack, self.world)
        result = self.failed_phase(pack=True)
        self.assertIs(result["probe_registered"], False)
        self.assertIn("Expected probe_registered=True", result["error"])

    def test_all_conditions_pass_without_pack(self):
        runner.run_phase(self.args, "A03-removed", self.world.name)
        result = self.read_result()
        self.assertTrue(result["ready"])
        self.assertTrue(result["probes_passed"])
        self.assertEqual(result["exit_code"], 0)
        self.assertFalse(result["forced_termination"])
        self.assertIs(result["probe_registered"], False)

    def test_all_conditions_pass_with_pack(self):
        runner.install_pack(self.args.pack, self.world)
        self.probe_present = True
        runner.run_phase(self.args, "A02-installed", self.world.name, pack=True)
        result = self.read_result("A02-installed")
        self.assertTrue(result["probes_passed"])
        self.assertIs(result["probe_registered"], True)
        self.console.tickets.assert_any_call(runner.PROBE, extra=True, create=False)

    def test_pack_with_similar_id_cannot_replace_required_pack(self):
        self.extra_packs = [f"[{runner.PACK_ID}-backup (world)]"]
        self.probe_present = True
        result = self.failed_phase(pack=True)
        self.assertIn(f"Expected enabled {runner.PACK_ID}=True", result["error"])

    def test_pack_with_similar_id_does_not_block_expected_absence(self):
        self.extra_packs = [f"[{runner.PACK_ID}-backup (world)]"]
        runner.run_phase(self.args, "A03-removed", self.world.name)
        self.assertTrue(self.read_result()["probes_passed"])

    def test_exact_pack_is_found_among_similar_ids(self):
        self.extra_packs = [f"[{runner.PACK_ID}-backup (world)]"]
        self.probe_present = True
        runner.install_pack(self.args.pack, self.world)
        runner.run_phase(self.args, "A02-installed", self.world.name, pack=True)
        self.assertTrue(self.read_result("A02-installed")["probes_passed"])

    def test_explicit_override_allows_registered_probe_without_pack(self):
        self.probe_present = True
        runner.run_phase(self.args, "A03-removed", self.world.name, expect_probe=True)
        self.assertIs(self.read_result()["probe_registered"], True)

    def test_successful_process_with_no_world_evidence_fails(self):
        (self.world / "level.dat").unlink()
        with self.assertRaisesRegex(RuntimeError, r"A03-removed failed world snapshot validation.*result.json"):
            runner.run_phase(self.args, "A03-removed", self.world.name)
        result = self.read_result(backup=False)
        self.assertTrue(result["probes_passed"])
        self.assertIn("Missing level.dat", result["snapshot_error"])

    def test_failed_process_with_partial_world_preserves_inventory(self):
        (self.world / "level.dat").unlink()
        (self.world / "partial.dat").write_bytes(b"partial save")
        self.console.wait.return_value = "Failed to start the minecraft server"
        result = self.failed_phase()
        self.assertFalse(result["ready"])
        files = json.loads((self.args.evidence / "A03-removed" / "files.json").read_text("utf-8"))
        self.assertEqual(set(files), {"partial.dat"})

    def test_bad_deflate_stream_preserves_snapshot_failure(self):
        path = self.world / "level.dat"
        compressed = bytearray(gzip.compress(gzip.decompress(path.read_bytes()), mtime=0))
        compressed[10] = 0x07  # Reserved deflate block type, not valid compressed data.
        path.write_bytes(compressed)
        with self.assertRaisesRegex(RuntimeError, r"A03-removed failed world snapshot validation.*result\.json"):
            runner.run_phase(self.args, "A03-removed", self.world.name)
        result = self.read_result(backup=False)
        self.assertIn("Cannot read level.dat", result["snapshot_error"])

    def test_failed_removal_does_not_restore(self):
        runner.install_pack(self.args.pack, self.world)
        self.probe_present = True
        # A second startup could succeed if the workflow proceeded past the failed removal.
        self.console.wait.side_effect = ["Failed to start the minecraft server", "Done (0.100s)!"]
        with self.assertRaisesRegex(RuntimeError, r"A03-removed failed.*result\.json"):
            runner.remove_and_restore(self.args, "A", self.world.name)
        self.popen.assert_called_once()
        self.assertFalse(self.installed_pack.exists())
        self.assertTrue((self.args.evidence / "A-parked-pack/pack.mcmeta").is_file())
        self.assertFalse((self.args.evidence / "A04-restored").exists())
        self.assertFalse(self.read_result(backup=False)["ready"])


class BackupValidationTest(unittest.TestCase):
    def setUp(self):
        root = Path(self.enterContext(TemporaryDirectory()))
        self.world = root / "world"
        self.phase = root / "phase"
        self.target = root / "restored"
        self.phase.mkdir()

    def check_rejected_snapshot(self, message, *, archive=True):
        with self.assertRaisesRegex(RuntimeError, message):
            runner.snapshot(self.world, self.phase, archive=archive)
        self.assertTrue((self.phase / "files.json").is_file())
        self.assertFalse((self.phase / "world.zip").exists())
        self.assertFalse((self.phase / "backup.json").exists())

    def test_missing_world_cannot_be_archived(self):
        self.check_rejected_snapshot("Missing level.dat")

    def test_empty_world_cannot_be_archived(self):
        self.world.mkdir()
        self.check_rejected_snapshot("Missing level.dat")

    def test_nonempty_world_without_level_dat_cannot_be_snapshotted(self):
        self.world.mkdir()
        (self.world / "level.dat_old").write_bytes(b"old")
        self.check_rejected_snapshot("Missing level.dat", archive=False)

    def test_unreadable_level_dat_cannot_be_archived(self):
        self.world.mkdir()
        (self.world / "level.dat").write_bytes(b"not NBT")
        self.check_rejected_snapshot("Cannot read level.dat")

    def test_truncated_plain_nbt_end_marker_cannot_be_archived(self):
        self.world.mkdir()
        raw = BytesIO()
        runner.nbtlib.File({"Data": runner.nbtlib.Compound()}).write(raw)
        (self.world / "level.dat").write_bytes(raw.getvalue()[:-1])
        self.check_rejected_snapshot("Cannot read level.dat")

    def test_bad_gzip_checksum_cannot_be_archived(self):
        self.world.mkdir()
        path = self.world / "level.dat"
        write_level_dat(self.world)
        compressed = bytearray(path.read_bytes())
        compressed[-8] ^= 1
        path.write_bytes(compressed)
        self.check_rejected_snapshot("Cannot read level.dat")

    def test_wrong_level_dat_data_type_cannot_be_archived(self):
        self.world.mkdir()
        write_level_dat(self.world, data=runner.nbtlib.Int(1))
        self.check_rejected_snapshot("Missing or invalid level.dat Data compound")

    def test_valid_minimal_world_round_trip(self):
        self.world.mkdir()
        write_level_dat(self.world)
        (self.world / "session.lock").write_bytes(b"not part of the backup")
        self.assertEqual(set(runner.snapshot(self.world, self.phase, archive=True)), {"level.dat"})
        self.assertEqual(runner.restore_backup(self.phase, self.target)["verified_files"], 1)
        self.assertEqual((self.target / "level.dat").read_bytes(), (self.world / "level.dat").read_bytes())
        self.assertFalse((self.target / "session.lock").exists())

    def prepare_legacy_archive(self, *, copy_world):
        """Build self-consistent evidence with the old archive rules, independently of snapshot()."""
        files = runner.inventory(self.world) if copy_world else {}
        backup = self.phase / "world.zip"
        with zipfile.ZipFile(backup, "x") as archive:
            for name in files:
                archive.write(self.world / name, name)
        runner.write_json(self.phase / "files.json", files)
        runner.write_json(self.phase / "backup.json", {
            "sha256": runner.sha256(backup), "bytes": backup.stat().st_size, "verified_files": len(files)
        })

    def test_restore_rejects_old_empty_backup_before_creating_target(self):
        self.prepare_legacy_archive(copy_world=False)
        with self.assertRaisesRegex(RuntimeError, "Backup manifest lacks a verified world"):
            runner.restore_backup(self.phase, self.target)
        self.assertFalse(self.target.exists())

    def test_restore_rejects_level_dat_missing_from_archive_before_extract(self):
        self.world.mkdir()
        write_level_dat(self.world)
        self.prepare_legacy_archive(copy_world=False)
        files = runner.inventory(self.world)
        runner.write_json(self.phase / "files.json", files)
        path = self.phase / "backup.json"
        metadata = json.loads(path.read_text("utf-8"))
        metadata["verified_files"] = len(files)
        runner.write_json(path, metadata)
        with self.assertRaisesRegex(RuntimeError, "Archive entries differ"):
            runner.restore_backup(self.phase, self.target)
        self.assertFalse(self.target.exists())

    def test_restore_does_not_accept_matching_hashes_for_invalid_level_dat(self):
        self.world.mkdir()
        write_level_dat(self.world, data=runner.nbtlib.String("not a compound"))
        self.prepare_legacy_archive(copy_world=True)
        with self.assertRaisesRegex(RuntimeError, "Missing or invalid level.dat Data compound"):
            runner.restore_backup(self.phase, self.target)


class ConsoleProbeTest(unittest.TestCase):
    def test_pack_list_parser_matches_english_server_log_with_source_labels(self):
        line = "[13:09:04] [Server thread/INFO]: System chat: There are 3 data pack(s) enabled: "
        line += f"[vanilla (built-in)], [mod_data], [{runner.PACK_ID} (world)]"
        self.assertEqual(runner.enabled_pack_ids(line), {"vanilla", "mod_data", runner.PACK_ID})
        self.assertEqual(runner.enabled_pack_ids("There are no data packs enabled"), set())

    def test_incomplete_pack_list_is_not_treated_as_absence(self):
        with self.assertRaisesRegex(RuntimeError, "Incomplete enabled data pack list"):
            runner.enabled_pack_ids("There are 3 data pack(s) enabled: [vanilla (built-in)]")

    def test_failed_block_check_is_reported_immediately(self):
        console = SimpleNamespace(loaded=lambda _: None)

        def response(command, pattern):
            self.assertTrue(command.endswith("minecraft:gold_block"))
            self.assertIsNotNone(re.search(pattern, "Test failed"))
            return "Test failed"

        console.command = response
        with self.assertRaisesRegex(RuntimeError, "Test failed"):
            runner.Console.block(console, runner.OVERWORLD, "gold_block", False)

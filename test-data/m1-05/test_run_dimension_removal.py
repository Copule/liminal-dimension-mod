"""Exercise the acceptance gate without starting a server."""

import json
from pathlib import Path
import re
from subprocess import TimeoutExpired
from tempfile import TemporaryDirectory
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import run_dimension_removal as runner


class RemovalGateTest(unittest.TestCase):
    def setUp(self):
        root = Path(self.enterContext(TemporaryDirectory()))
        self.args = SimpleNamespace(runtime=root / "runtime", evidence=root / "evidence",
                                    pack=root / "source-pack", java=root / "java.exe", port=25576)
        self.world = self.args.runtime / "world"
        self.world.mkdir(parents=True)
        self.args.evidence.mkdir()
        self.args.pack.mkdir()
        (self.args.pack / "pack.mcmeta").write_text("{}", encoding="utf-8")
        self.installed_pack = self.world / "datapacks" / runner.PACK
        self.probe_present = False

        self.popen = self.enterContext(patch.object(runner.subprocess, "Popen"))
        self.process = self.popen.return_value
        self.process.poll.return_value = None
        self.process.wait.return_value = 0
        self.console = self.enterContext(patch.object(runner, "Console")).return_value
        self.console.wait.return_value = "Done (0.100s)!"
        self.console.command.side_effect = self.command_response

    def command_response(self, command, pattern):
        if command == "datapack list enabled":
            packs = f"vanilla, {runner.PACK_ID}" if self.installed_pack.exists() else "vanilla"
            response = f"There are data pack(s) enabled: [{packs}]"
        elif command == f"execute in {runner.PROBE} if loaded {runner.COORDS}":
            # An existing dimension can be recognized before its probe chunk loads.
            response = "Test failed" if self.probe_present else f"Unknown dimension '{runner.PROBE}'"
        elif command == "save-all flush":
            response = "Saved the game"
        else:
            self.fail(f"Unexpected command: {command}")
        self.assertRegex(response, pattern)
        return response

    def read_result(self, name="A03-removed"):
        phase = self.args.evidence / name
        self.assertTrue((phase / "world.zip").is_file())
        return json.loads((phase / "result.json").read_text(encoding="utf-8"))

    def failed_phase(self, **options):
        with self.assertRaisesRegex(RuntimeError, r"A03-removed failed.*result\.json"):
            runner.run_phase(self.args, "A03-removed", self.world.name, **options)
        return self.read_result()

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

    def test_explicit_override_allows_registered_probe_without_pack(self):
        self.probe_present = True
        runner.run_phase(self.args, "A03-removed", self.world.name, expect_probe=True)
        self.assertIs(self.read_result()["probe_registered"], True)

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
        self.assertFalse(self.read_result()["ready"])


class ConsoleProbeTest(unittest.TestCase):
    def test_failed_block_check_is_reported_immediately(self):
        console = SimpleNamespace(loaded=lambda _: None)

        def response(command, pattern):
            self.assertTrue(command.endswith("minecraft:gold_block"))
            self.assertIsNotNone(re.search(pattern, "Test failed"))
            return "Test failed"

        console.command = response
        with self.assertRaisesRegex(RuntimeError, "Test failed"):
            runner.Console.block(console, runner.OVERWORLD, "gold_block", False)

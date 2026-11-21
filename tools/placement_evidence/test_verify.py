import contextlib
from hashlib import sha256
import io
import json
import os
from pathlib import Path
import tempfile
import unittest

import verify as v


PLAYER = "FixturePerson"
UUID = "00000000-0000-4000-8000-000000000001"
DATA = UUID + ".dat"
OLD = DATA + "_old"


def entry(name, text):
    raw = text.encode()
    return {"name": name, "size": len(raw), "sha256": sha256(raw).hexdigest()}


def event(payload, logger=None, thread="Server thread", level="INFO"):
    return f"[12:30:00] [{thread}/{level}]" + (f" [minecraft/{logger}]" if logger else "") + ": " + payload


READY = event('Done (123.4s)! For help, type "help"', "DedicatedServer")
AUTH = event(f"UUID of player {PLAYER} is {UUID}", "ServerLoginPacketListenerImpl", "User Authenticator #1")
PLACED = event(f"{PLAYER}[/127.0.0.1:55001] logged in with entity id 15 at (1.0, 2.0, 3.0)", "PlayerList")
JOIN = event(f"{PLAYER} joined the game", "MinecraftServer")
DENIED = event(f"{PLAYER} ({UUID}) lost connection: {v.DENIAL}", "ServerConfigurationPacketListenerImpl")
ZERO = event("There are 0 of a max of 20 players online: ", "MinecraftServer")
STOP = event("Stopping server", "MinecraftServer")


class PairedEvidence(unittest.TestCase):
    def setUp(self):
        self.root = Path(self.enterContext(tempfile.TemporaryDirectory())).resolve()
        self.meta = {
            "schema": 1, "fixture_commit": v.FIXTURE, "fixture_jar_sha256": "1" * 64,
            "minecraft": "26.3", "neoforge": "26.3.0.113", "java": 25,
            "profile": PLAYER, "uuid": UUID,
            "context": dict.fromkeys(("dedicated", "online_mode", "secure_profile", "whitelist",
                                      "disposable_copy", "fixture_enabled", "full_logs",
                                      "complete_stopped_inventories"), True),
            "trials": {},
        }
        self.meta["context"].update(authorized_count=1, bind="127.0.0.1", client_address="127.0.0.1")
        self.baseline = [entry(DATA, "primary"), entry(OLD, "different backup"), entry("sibling.dat", "other")]
        for trial in ("positive", "negative"):
            enabled = trial == "positive"
            self.meta["trials"][trial] = {"pack_present": enabled, "probe_loaded": enabled,
                                           "exit_code": 0, "stopped_cleanly": True}
            self.inventory(trial, "before", self.baseline)
            self.inventory(trial, "after", self.baseline if not enabled else
                           [entry(DATA, "fresh primary"), *self.baseline[1:]])
        self.log("positive", READY, AUTH, PLACED, JOIN, STOP)
        self.log("negative", READY, AUTH, DENIED, ZERO, STOP)

    def capture(self, trial, field, filename, data):
        blob = data.encode()
        (self.root / filename).write_bytes(blob)
        self.meta["trials"][trial][field + "_sha256"] = sha256(blob).hexdigest()

    def log(self, trial, *lines):
        self.capture(trial, "log", trial + "-server.log", "\n".join(lines) + "\n")

    def inventory(self, trial, phase, entries):
        self.capture(trial, phase, f"{trial}-{phase}.json",
                     json.dumps({"trial": trial, "phase": phase, "entries": entries}))

    def checked(self, *extra):
        (self.root / "evidence.json").write_text(json.dumps(self.meta), encoding="utf-8")
        files = {p.name: p.read_bytes() for p in self.root.iterdir() if p.is_file() and not p.is_symlink()}
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf), contextlib.redirect_stderr(buf):
            code = v.main([str(self.root), *extra])
        text = buf.getvalue()
        self.assertNotIn(PLAYER, text)
        self.assertNotIn(UUID, text)
        self.assertNotIn(str(self.root), text)
        self.assertEqual(files, {p.name: p.read_bytes() for p in self.root.iterdir()
                                 if p.is_file() and not p.is_symlink()})
        return code, json.loads(text)

    def has(self, status, expected_code):
        code, report = self.checked()
        self.assertEqual(code, expected_code)
        self.assertIn(status, [item["code"] for item in report["issues"]])

    def test_complete_pair(self):
        self.assertEqual(self.checked(), (0, {"status": "CONSISTENT_EVIDENCE", "issues": []}))

    def test_vanilla_prefixes_also_supported(self):
        vanilla = lambda line: line.replace(" [minecraft/MinecraftServer]", "").replace(
            " [minecraft/DedicatedServer]", "").replace(" [minecraft/ServerLoginPacketListenerImpl]", "").replace(
            " [minecraft/PlayerList]", "").replace(" [minecraft/ServerConfigurationPacketListenerImpl]", "")
        self.log("positive", *(vanilla(x) for x in [READY, AUTH, PLACED, JOIN, STOP]))
        self.log("negative", *(vanilla(x) for x in [READY, AUTH, DENIED, ZERO, STOP]))
        self.assertEqual(self.checked()[0], 0)

    def test_generic_or_suffixed_disconnect_does_not_prove_fixture(self):
        for line in (DENIED.replace(v.DENIAL, "Disconnected"), DENIED + " more"):
            with self.subTest(line=line):
                self.log("negative", READY, AUTH, line, ZERO, STOP)
                self.has("NEGATIVE_DISCONNECT_MISMATCH", 1)

    def test_either_negative_world_event_fails(self):
        for world in (JOIN, PLACED):
            with self.subTest(world=world):
                self.log("negative", READY, AUTH, world, DENIED, ZERO, STOP)
                self.has("NEGATIVE_WORLD_EVENT", 1)

    def test_world_event_under_unrecognized_prefix_cannot_clear(self):
        self.log("negative", READY, AUTH, "unsupported " + JOIN, DENIED, ZERO, STOP)
        self.has("WORLD_EVENT_FORMAT_UNSUPPORTED", 2)

    def test_extra_core_events_under_unrecognized_prefix_cannot_clear(self):
        for trial, good in (("positive", (READY, AUTH, PLACED, JOIN)),
                            ("negative", (READY, AUTH, DENIED, ZERO))):
            for extra in (DENIED.replace("/INFO]", "/WARN]"), AUTH.replace("User Authenticator #1", "Other"),
                          ZERO.replace("are 0", "are 1").replace("/INFO]", "/WARN]"),
                          READY.replace("/INFO]", "/WARN]"), STOP.replace("/INFO]", "/WARN]")):
                with self.subTest(trial=trial, extra=extra):
                    self.log(trial, *good, extra, STOP)
                    self.has("CORE_EVENT_FORMAT_UNSUPPORTED", 2)
            self.log(trial, *good, STOP)

    def test_placement_requires_valid_port_and_finite_coordinates(self):
        for line in (PLACED.replace("55001", "99999"), PLACED.replace("55001", "0"),
                     PLACED.replace("2.0", "arbitrary"), PLACED.replace("2.0", "1e9999")):
            with self.subTest(line=line):
                self.log("positive", READY, AUTH, line, JOIN, STOP)
                self.has("WORLD_EVENT_FORMAT_UNSUPPORTED", 2)

    def test_zero_players_required_after_denial(self):
        self.log("negative", READY, AUTH, ZERO, DENIED, STOP)
        self.has("NEGATIVE_ZERO_ONLINE_AFTER_DENIAL_UNPROVEN", 2)
        self.log("negative", READY, AUTH, DENIED, ZERO.replace("are 0", "are 1") + PLAYER, STOP)
        self.has("NEGATIVE_ONLINE_PLAYERS", 1)

    def test_auth_required_and_same_profile_both_trials(self):
        for line in (AUTH.replace(PLAYER, "Other"), AUTH.replace(UUID, "1" * 36)):
            with self.subTest(line=line):
                self.log("negative", READY, line, DENIED, ZERO, STOP)
                self.has("AUTH_EVIDENCE_UNSUPPORTED", 2)
        self.log("negative", READY, DENIED, AUTH, ZERO, STOP)
        self.has("EVENT_ORDER_UNSUPPORTED", 2)

    def test_single_fresh_session_with_clean_stop(self):
        self.log("negative", READY, AUTH, DENIED, ZERO, STOP, READY, STOP)
        self.has("UNSUPPORTED_LOG_SESSION", 2)
        self.log("negative", READY, AUTH, DENIED, ZERO, STOP)
        self.meta["trials"]["negative"]["exit_code"] = 1
        self.has("TRIAL_SETUP_UNSUPPORTED", 2)

    def test_error_after_stop_makes_evidence_uncertain(self):
        self.log("negative", READY, AUTH, DENIED, ZERO, STOP, event("Exception stopping the server", level="ERROR"))
        self.has("UNSUPPORTED_LOG_SESSION", 2)

    def test_reaching_positive_requires_placement_and_join(self):
        self.log("positive", READY, AUTH, PLACED, STOP)
        self.has("POSITIVE_PLACEMENT_OR_JOIN_UNPROVEN", 2)

    def test_both_records_must_exist_and_baseline_is_distinct(self):
        self.inventory("negative", "before", [self.baseline[0], self.baseline[2]])
        self.has("PLAYER_PAIR_INCOMPLETE", 2)
        clone = dict(self.baseline[0], name=OLD)
        self.inventory("negative", "before", [self.baseline[0], clone, self.baseline[2]])
        self.has("BASELINE_PAIR_NOT_DISTINCT", 2)

    def test_different_per_trial_baseline_prevents_comparison(self):
        self.inventory("positive", "before", [*self.baseline, entry("unpaired", "more")])
        self.has("BASELINES_DIVERGE", 2)

    def test_overwrite_removal_or_new_sibling_fail_negative(self):
        changed = [entry(DATA, "changed"), *self.baseline[1:]]
        for entries in (changed, self.baseline[1:], self.baseline[:-1], [], [*self.baseline, entry("new.bak", "x")]):
            with self.subTest(entries=entries):
                self.inventory("negative", "after", entries)
                self.has("PLAYER_DATA_SIBLINGS_CHANGED", 1)

    def test_duplicate_or_incorrect_sibling_schema_is_rejected(self):
        for extra in (self.baseline[0], dict(self.baseline[0], name=DATA.upper()),
                      entry("../disallowed", "x"), dict(self.baseline[0], name="bad", size=True)):
            with self.subTest(extra=extra):
                self.inventory("negative", "after", [*self.baseline, extra])
                self.has("INVENTORY_ENTRY", 2)

    def test_hash_mismatch_and_symlinks_are_rejected(self):
        artifact = self.root / "negative-before.json"
        original = artifact.read_bytes()
        artifact.write_bytes(original + b" ")
        self.has("INVENTORY_HASH_MISMATCH", 2)
        artifact.unlink()
        target = self.root / "other.json"
        target.write_bytes(original)
        try:
            artifact.symlink_to(target)
        except OSError:
            return
        self.has("FILE_UNSUPPORTED", 2)

    def test_hardlinked_evidence_is_rejected(self):
        try:
            os.link(self.root / "negative-before.json", self.root / "alias.json")
        except OSError:
            self.skipTest("hard link unavailable")
        self.has("FILE_UNSUPPORTED", 2)

    def test_auth_mode_or_setup_drift_cannot_clear(self):
        for field in ("online_mode", "secure_profile", "whitelist", "fixture_enabled", "full_logs"):
            with self.subTest(field=field):
                self.meta["context"][field] = False
                self.has("CONTEXT_UNSUPPORTED", 2)
                self.meta["context"][field] = True
        self.meta["trials"]["negative"]["probe_loaded"] = True
        self.has("TRIAL_SETUP_UNSUPPORTED", 2)

    def test_cli_error_does_not_disclose_operand(self):
        code, parsed = self.checked("/private/" + UUID)
        self.assertEqual(code, 2)
        self.assertEqual(parsed["issues"][0]["code"], "INVALID_ARGUMENTS")

    def test_duplicate_json_keys_fail(self):
        self.capture("negative", "before", "negative-before.json", '{"trial":"negative","trial":"positive"}')
        self.has("DUPLICATE_JSON_KEY", 2)


if __name__ == "__main__":
    unittest.main()

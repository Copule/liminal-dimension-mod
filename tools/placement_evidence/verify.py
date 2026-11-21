"""Verify paired PR #13 evidence offline, without opening any player-data files."""

import argparse
from hashlib import sha256
import json
from math import isfinite
from os import fstat
from pathlib import Path
import re
import stat
import sys
import uuid

FIXTURE = "09d2019c951c1ad66f45e066054e548d91bdcdf8"
DENIAL = "Placement fixture blocked this connection before spawn: staged probe unavailable."
NAME = re.compile(r"[A-Za-z0-9_]{1,16}")
DIGEST = re.compile(r"[0-9a-f]{64}")
SIBLING = re.compile(r"[A-Za-z0-9_.-]{1,255}")
WORLD_MARKERS = (" logged in with entity id ", " joined the game")
LINE = re.compile(r"\[\d{2}:\d{2}:\d{2}\] \[(?P<thread>[^\]/]{1,100})/INFO\](?: \[(?P<logger>[^\]]+)\])?: (?P<message>.*)")
UUID_LOG = re.compile(r"UUID of player ([A-Za-z0-9_]{1,16}) is ([0-9a-f-]{36})")
CONFIG = re.compile(r"([A-Za-z0-9_]{1,16}) \(([0-9a-f-]{36})\) lost connection: (.*)")
COORD = r"([-+]?(?:[0-9]+(?:\.[0-9]*)?|\.[0-9]+)(?:[Ee][-+]?[0-9]{1,4})?)"
PLACED = re.compile(
    r"([A-Za-z0-9_]{1,16})\[/127\.0\.0\.1:([0-9]{1,5})\] logged in with entity id [0-9]+ at \("
    + ", ".join([COORD] * 3) + r"\)"
)
JOINED = re.compile(r"([A-Za-z0-9_]{1,16}) joined the game")
ONLINE = re.compile(r"There are ([0-9]+) of a max of ([0-9]+) players online: *(.*)")
READY = re.compile(r'Done \([0-9.]+s\)! For help, type "help"')
OTHER_CORE_MARKER = re.compile(
    r"UUID of player |\([0-9a-fA-F-]{36}\) lost connection: |"
    r"There are [0-9]+ of a max of [0-9]+ players online: |"
    r'Done \([^\r\n]*\)! For help, type "help"|(?:^|: )Stopping server$'
)
LIMIT_LOG = 16 * 1024 * 1024
LIMIT_JSON = 4 * 1024 * 1024


class Unverified(ValueError):
    """Missing, ambiguous or unsupported evidence."""


def require(condition, code):
    if not condition:
        raise Unverified(code)


def read_bytes(root, name, limit):
    path = root / name
    before = path.lstat()
    require(stat.S_ISREG(before.st_mode) and before.st_nlink == 1
            and path.resolve(strict=True) == path and before.st_size <= limit,
            "FILE_UNSUPPORTED")
    signature = lambda info: (info.st_dev, info.st_ino, info.st_mode, info.st_nlink,
                              info.st_size, info.st_mtime_ns, info.st_ctime_ns)
    with path.open("rb") as stream:
        require(signature(fstat(stream.fileno())) == signature(before), "FILE_UNSTABLE")
        data = stream.read(limit + 1)
        require(signature(fstat(stream.fileno())) == signature(before), "FILE_UNSTABLE")
    after = path.lstat()
    require(signature(before) == signature(after) and len(data) == before.st_size, "FILE_UNSTABLE")
    return data


def unique_json(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, "DUPLICATE_JSON_KEY")
        result[key] = value
    return result


def decode_json(raw):
    return json.loads(raw.decode("utf-8"), object_pairs_hook=unique_json)


def keys(value, expected, error):
    require(isinstance(value, dict) and set(value) == set(expected.split()), error)


def sha(value):
    return isinstance(value, str) and DIGEST.fullmatch(value) is not None


def check_inventory(value, trial, phase, player_id):
    keys(value, "trial phase entries", "INVENTORY_SCHEMA")
    require(value["trial"] == trial and value["phase"] == phase, "INVENTORY_SCOPE")
    entries = value["entries"]
    require(isinstance(entries, list) and len(entries) <= 20000, "INVENTORY_SIZE")
    result, folded = {}, set()
    for entry in entries:
        keys(entry, "name size sha256", "INVENTORY_ENTRY")
        name, size, digest = entry["name"], entry["size"], entry["sha256"]
        require(isinstance(name, str) and SIBLING.fullmatch(name) and name not in {".", ".."}
                and not name.endswith(".") and name.casefold() not in folded
                and type(size) is int and 0 <= size < 2**53 and sha(digest), "INVENTORY_ENTRY")
        result[name] = (size, digest)
        folded.add(name.casefold())
    primary, backup = player_id + ".dat", player_id + ".dat_old"
    if phase == "before" or trial == "positive":
        require(primary in result and backup in result and result[primary][0] > 0 and result[backup][0] > 0,
                "PLAYER_PAIR_INCOMPLETE")
    if phase == "before":
        require(result[primary][1] != result[backup][1], "BASELINE_PAIR_NOT_DISTINCT")
    return result


def class_logger(actual, simple):
    if actual is None:   # original vanilla INFO prefix
        return True
    qualified = {
        "DedicatedServer": "net.minecraft.server.dedicated.DedicatedServer",
        "MinecraftServer": "net.minecraft.server.MinecraftServer",
        "PlayerList": "net.minecraft.server.players.PlayerList",
        "ServerLoginPacketListenerImpl": "net.minecraft.server.network.ServerLoginPacketListenerImpl",
        "ServerConfigurationPacketListenerImpl": "net.minecraft.server.network.ServerConfigurationPacketListenerImpl",
    }[simple]
    return actual in {f"minecraft/{simple}", qualified}


def parse_log(text):
    events = []
    for line in text.splitlines():
        match = LINE.fullmatch(line)
        if match:
            thread, logger, message = match.group("thread", "logger", "message")
            if thread == "Server thread":
                if READY.fullmatch(message) and (class_logger(logger, "DedicatedServer")
                                                 or class_logger(logger, "MinecraftServer")):
                    events.append(("ready",))
                    continue
                if message == "Stopping server" and (class_logger(logger, "MinecraftServer")
                                                      or class_logger(logger, "DedicatedServer")):
                    events.append(("stop",))
                    continue
                if ((result := PLACED.fullmatch(message)) and class_logger(logger, "PlayerList")
                        and 1 <= int(result[2]) <= 65535
                        and all(isfinite(float(coord)) for coord in result.groups()[2:])):
                    events.append(("place", result[1]))
                    continue
                if (result := JOINED.fullmatch(message)) and (class_logger(logger, "MinecraftServer")
                                                               or class_logger(logger, "PlayerList")):
                    events.append(("join", result[1]))
                    continue
                if (result := CONFIG.fullmatch(message)) and class_logger(logger, "ServerConfigurationPacketListenerImpl"):
                    events.append(("config_end", *result.groups()))
                    continue
                if (result := ONLINE.fullmatch(message)) and (class_logger(logger, "DedicatedServer")
                                                               or class_logger(logger, "MinecraftServer")):
                    events.append(("online", result[1], result[3]))
                    continue
            elif re.fullmatch(r"User Authenticator #[0-9]+", thread):
                if (result := UUID_LOG.fullmatch(message)) and class_logger(logger, "ServerLoginPacketListenerImpl"):
                    events.append(("auth", *result.groups()))
                    continue
        # Do not silently discard evidence that resembles a world event under
        # untested prefixes, address settings, locale variants or logger names.
        if any(marker in line for marker in WORLD_MARKERS):
            events.append(("unparsed_world",))
        if DENIAL in line or OTHER_CORE_MARKER.search(line):
            events.append(("unparsed_core",))
        if "/ERROR]" in line or "/FATAL]" in line:
            events.append(("server_error",))
    return events


def check_log(text, player, player_id, trial):
    events = parse_log(text)
    tags = [event[0] for event in events]
    require(tags.count("ready") == tags.count("stop") == 1 and tags[0] == "ready" and tags[-1] == "stop",
            "UNSUPPORTED_LOG_SESSION")
    failed, missing = [], []
    world = [event for event in events if event[0] in {"place", "join"}]
    auth = [event for event in events if event[0] == "auth"]
    ends = [event for event in events if event[0] == "config_end"]
    if "server_error" in tags:
        missing.append("SERVER_ERROR_PRESENT")
    if "unparsed_world" in tags:
        missing.append("WORLD_EVENT_FORMAT_UNSUPPORTED")
    if "unparsed_core" in tags:
        missing.append("CORE_EVENT_FORMAT_UNSUPPORTED")
    if trial == "negative" and world:
        failed.append("NEGATIVE_WORLD_EVENT")
    if len(auth) != 1 or auth[0] != ("auth", player, player_id):
        missing.append("AUTH_EVIDENCE_UNSUPPORTED")
    if trial == "positive":
        expected = [("place", player), ("join", player)]
        if world != expected:
            missing.append("POSITIVE_PLACEMENT_OR_JOIN_UNPROVEN")
        if ends:
            failed.append("POSITIVE_CONFIGURATION_DISCONNECT")
        if auth and world and tags.index("auth") > tags.index(world[0][0]):
            missing.append("EVENT_ORDER_UNSUPPORTED")
    else:
        if not ends:
            missing.append("NEGATIVE_FIXTURE_DISCONNECT_UNPROVEN")
        elif ends != [("config_end", player, player_id, DENIAL)]:
            failed.append("NEGATIVE_DISCONNECT_MISMATCH")
        online = [event for event in events if event[0] == "online"]
        if any(event != ("online", "0", "") for event in online):
            failed.append("NEGATIVE_ONLINE_PLAYERS")
        if len(online) != 1 or not ends or tags.index("config_end") > tags.index("online"):
            missing.append("NEGATIVE_ZERO_ONLINE_AFTER_DENIAL_UNPROVEN")
        if auth and ends and tags.index("auth") > tags.index("config_end"):
            missing.append("EVENT_ORDER_UNSUPPORTED")
    return failed, missing


def verify(root):
    require(".." not in root.parts, "EVIDENCE_ROOT_UNSUPPORTED")
    absolute = root.absolute()
    require(absolute.resolve(strict=True) == absolute and absolute.is_dir(), "EVIDENCE_ROOT_UNSUPPORTED")
    manifest = decode_json(read_bytes(absolute, "evidence.json", LIMIT_JSON))
    keys(manifest, "schema fixture_commit fixture_jar_sha256 minecraft neoforge java profile uuid context trials",
         "MANIFEST_SCHEMA")
    require(type(manifest["schema"]) is int and manifest["schema"] == 1
            and manifest["fixture_commit"] == FIXTURE and sha(manifest["fixture_jar_sha256"])
            and manifest["minecraft"] == "26.3" and manifest["neoforge"] == "26.3.0.113"
            and type(manifest["java"]) is int and manifest["java"] == 25, "FIXTURE_VERSION_UNSUPPORTED")
    player, player_id = manifest["profile"], manifest["uuid"]
    require(isinstance(player, str) and NAME.fullmatch(player) and isinstance(player_id, str)
            and str(uuid.UUID(player_id)) == player_id, "IDENTITY_FORMAT_UNSUPPORTED")
    context = manifest["context"]
    keys(context, "dedicated online_mode secure_profile whitelist authorized_count disposable_copy bind client_address "
                  "fixture_enabled full_logs complete_stopped_inventories", "CONTEXT_SCHEMA")
    require(all(context[k] is True for k in ("dedicated", "online_mode", "secure_profile", "whitelist",
                                            "disposable_copy", "fixture_enabled", "full_logs",
                                            "complete_stopped_inventories"))
            and type(context["authorized_count"]) is int and context["authorized_count"] == 1
            and context["bind"] == context["client_address"] == "127.0.0.1", "CONTEXT_UNSUPPORTED")
    trials = manifest["trials"]
    keys(trials, "positive negative", "TRIAL_SCHEMA")
    checked, failures, missing = {}, [], []
    log_hashes = set()
    for trial in ("positive", "negative"):
        evidence = trials[trial]
        keys(evidence, "pack_present probe_loaded exit_code stopped_cleanly log_sha256 before_sha256 after_sha256",
             "TRIAL_SCHEMA")
        expected = trial == "positive"
        require(evidence["pack_present"] is expected and evidence["probe_loaded"] is expected
                and type(evidence["exit_code"]) is int and evidence["exit_code"] == 0
                and evidence["stopped_cleanly"] is True,
                "TRIAL_SETUP_UNSUPPORTED")
        raw = read_bytes(absolute, trial + "-server.log", LIMIT_LOG)
        log_digest = sha256(raw).hexdigest()
        require(evidence["log_sha256"] == log_digest and log_digest not in log_hashes, "LOG_HASH_OR_DUPLICATE")
        log_hashes.add(log_digest)
        failures_from_log, missing_from_log = check_log(raw.decode("utf-8"), player, player_id, trial)
        failures.extend((trial, reason) for reason in failures_from_log)
        missing.extend((trial, reason) for reason in missing_from_log)
        for phase in ("before", "after"):
            raw = read_bytes(absolute, f"{trial}-{phase}.json", LIMIT_JSON)
            require(evidence[phase + "_sha256"] == sha256(raw).hexdigest(), "INVENTORY_HASH_MISMATCH")
            checked[trial, phase] = check_inventory(decode_json(raw), trial, phase, player_id)
    if checked["positive", "before"] != checked["negative", "before"]:
        missing.append(("pair", "BASELINES_DIVERGE"))
    if checked["negative", "before"] != checked["negative", "after"]:
        failures.append(("negative", "PLAYER_DATA_SIBLINGS_CHANGED"))
    return failures, missing


class PrivateParser(argparse.ArgumentParser):
    def error(self, message):
        raise Unverified("INVALID_ARGUMENTS")


def main(argv=None):
    parser = PrivateParser(description=__doc__)
    parser.add_argument("evidence_directory", type=Path)
    try:
        failures, missing = verify(parser.parse_args(argv).evidence_directory)
    except Unverified as error:
        print(json.dumps({"status": "UNVERIFIED", "issues": [{"scope": "input", "code": str(error)}]}))
        return 2
    except (OSError, ValueError, RuntimeError):
        # Exception text can contain file paths, usernames and other private content.
        print(json.dumps({"status": "UNVERIFIED", "issues": [{"scope": "input", "code": "READ_OR_FORMAT_ERROR"}]}))
        return 2
    status = "FAIL" if failures else "UNVERIFIED" if missing else "CONSISTENT_EVIDENCE"
    print(json.dumps({"status": status, "issues": [{"scope": scope, "code": code}
                                                   for scope, code in failures + missing]}))
    return 1 if failures else 2 if missing else 0


if __name__ == "__main__":
    sys.exit(main())

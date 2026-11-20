"""Run the M1-05 experiment in a fresh, owner-authorized Windows server instance."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import queue
import re
import shutil
import stat
import subprocess
import threading
import time
import zipfile

import nbtlib


PACK = "m1-04-dimension-poc"
PACK_ID = f"file/{PACK}"
OVERWORLD = "minecraft:overworld"
PROBE = "liminal_dimension_poc:probe"
COORDS = "8 180 8"
NEOFORGE = "26.3.0.113"


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def write_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n", "utf-8")


def sha256(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def inventory(directory, exclude=()):
    return {
        path.relative_to(directory).as_posix(): {"bytes": path.stat().st_size, "sha256": sha256(path)}
        for path in sorted(directory.rglob("*"))
        if path.is_file() and path.relative_to(directory).as_posix() not in exclude
    }


def snapshot(world, phase, archive=False):
    files = inventory(world, exclude={"session.lock"})
    write_json(phase / "files.json", files)
    nbt = {}
    for name in files:
        if name in {"level.dat", "level.dat_old"} or name.endswith(("/world_gen_settings.dat", "/chunk_tickets.dat")):
            tag = nbtlib.load(world / name)
            if name.startswith("level.dat"):
                data = tag["Data"]
                keys = ("DataVersion", "Version", "DataPacks", "enabled_features", "WorldGenSettings",
                        "LastPlayed", "Time", "DayTime", "Spawn", "initialized")
                tag = {key: data[key].snbt() for key in keys if key in data}
            else:
                data = tag.get("data")
                tag = {"DataVersion": tag.get("DataVersion"), "data_snbt": data.snbt() if data else None}
                if name.endswith("/world_gen_settings.dat") and data:
                    tag["dimension_keys"] = sorted(data.get("dimensions", {}))
            nbt[name] = tag
    write_json(phase / "nbt.json", nbt)
    if archive:
        backup = phase / "world.zip"
        with zipfile.ZipFile(backup, "x", compression=zipfile.ZIP_DEFLATED, compresslevel=1) as output:
            for name in files:
                output.write(world / name, name)
        with zipfile.ZipFile(backup) as saved:
            require(set(saved.namelist()) == files.keys(), f"Archive entries differ: {backup}")
            for name, info in files.items():
                with saved.open(name) as stream:
                    require(hashlib.file_digest(stream, "sha256").hexdigest() == info["sha256"],
                            f"Archive content differs: {name}")
        write_json(phase / "backup.json", {"sha256": sha256(backup), "bytes": backup.stat().st_size,
                                          "verified_files": len(files)})
        backup.chmod(stat.S_IREAD)
    return files


class Console:
    def __init__(self, process, transcript):
        self.process = process
        self.lines = queue.Queue()
        self.transcript = transcript
        self.reader = threading.Thread(target=self.read, daemon=True)
        self.reader.start()

    def read(self):
        with (self.transcript.parent / "console.log").open("w", encoding="utf-8", newline="") as output:
            for line in self.process.stdout:
                output.write(line)
                output.flush()
                self.lines.put(line.rstrip("\r\n"))
        self.lines.put(None)

    def wait(self, pattern, seconds):
        deadline = time.monotonic() + seconds
        while (remaining := deadline - time.monotonic()) > 0:
            try:
                line = self.lines.get(timeout=remaining)
            except queue.Empty as exc:
                raise TimeoutError(pattern) from exc
            if line is None:
                raise EOFError(pattern)
            if re.search(pattern, line):
                return line
        raise TimeoutError(pattern)

    def send(self, command):
        self.process.stdin.write(command + "\n")
        self.process.stdin.flush()
        with self.transcript.open("a", encoding="utf-8") as log:
            log.write(f"> {command}\n")

    def command(self, command, response):
        self.send(command)
        line = self.wait(response, 35)
        with self.transcript.open("a", encoding="utf-8") as log:
            log.write(line + "\n")
        return line

    def loaded(self, dimension):
        for _ in range(45):
            line = self.command(f"execute in {dimension} if loaded {COORDS}", r"Test passed|Test failed|Unknown dimension")
            if "Test passed" in line:
                return
            require("Unknown dimension" not in line, line)
            time.sleep(1)
        raise TimeoutError(f"Probe chunk never loaded in {dimension}")

    def block(self, dimension, block, create):
        self.loaded(dimension)
        if create:
            self.command(f"execute in {dimension} run setblock {COORDS} minecraft:{block}", "Changed the block")
        self.command(f"execute in {dimension} if block {COORDS} minecraft:{block}", "Test passed")

    def tickets(self, dimension, *, extra, create):
        if create:
            bounds = "8 8 24 8" if extra else "8 8"
            self.command(f"execute in {dimension} run forceload add {bounds}", r"Marked .*force load")
        self.command(f"execute in {dimension} run forceload query 8 8", r"\[0, 0\].* is marked")
        expected = r"\[1, 0\].* is marked" if extra else r"\[1, 0\].*not marked"
        self.command(f"execute in {dimension} run forceload query 24 8", expected)


def configure(runtime, world, preinstalled, port, preset):
    properties = {
        "server-ip": "127.0.0.1", "server-port": port, "online-mode": "true",
        "white-list": "true", "enforce-whitelist": "true", "enforce-secure-profile": "true",
        "enable-rcon": "false", "enable-query": "false", "management-server-enabled": "false",
        "level-name": world, "level-seed": 71905, "view-distance": 4, "simulation-distance": 3,
        "pause-when-empty-seconds": -1,
        "initial-enabled-packs": f"vanilla,{PACK_ID}" if preinstalled else "vanilla",
    }
    if preset:
        properties["level-type"] = preset
    (runtime / "server.properties").write_text(
        "".join(f"{key}={value}\n" for key, value in properties.items()), encoding="ascii"
    )
    return properties


def run_phase(args, name, world_name, *, vanilla=False, pack=False, preinstalled=False,
              create_overworld=False, create_probe=False, removal=False, preset=None):
    phase = args.evidence / name
    phase.mkdir()
    world = args.runtime / world_name
    properties = configure(args.runtime, world_name, preinstalled, args.port, preset)
    java_args = ["-jar", "vanilla-26.3.jar"] if vanilla else [
        f"@libraries/net/neoforged/neoforge/{NEOFORGE}/win_args.txt"
    ]
    argv = [str(args.java), "-Xmx2G", "-Duser.language=en", "-Duser.country=US",
            "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8",
            f"-Duser.home={args.runtime}", f"-Djava.io.tmpdir={args.runtime / 'tmp'}", *java_args, "nogui"]
    env = os.environ.copy()
    for key, subdirectory in {"APPDATA": "appdata", "LOCALAPPDATA": "localappdata", "TEMP": "tmp", "TMP": "tmp"}.items():
        env[key] = str(args.runtime / subdirectory)
    write_json(phase / "launch.json", {
        "argv": argv, "cwd": str(args.runtime), "properties": properties,
        "level_dat_exists_before_launch": (world / "level.dat").exists(),
        "installed_pack_before_launch": (world / "datapacks" / PACK).is_dir(),
    })
    for filename in ("latest.log", "debug.log"):
        old = args.runtime / "logs" / filename
        if old.exists():
            old.rename(phase / f"prior-{filename}")
    print(f"{name}: launching", flush=True)
    result = {"ready": False, "exit_code": None, "probes_passed": False, "forced_termination": False}
    process = subprocess.Popen(argv, cwd=args.runtime, env=env, stdin=subprocess.PIPE,
                               stdout=subprocess.PIPE, stderr=subprocess.STDOUT, encoding="utf-8", errors="replace")
    console = Console(process, phase / "probes.txt")
    try:
        outcome = console.wait(r'Done \([^)]+\)!|Failed to load datapacks, can.t proceed|Failed to start the minecraft server', 240)
        result["startup_response"] = outcome
        result["ready"] = "Done (" in outcome
        require(result["ready"], outcome)
        pack_response = console.command("datapack list enabled", r"data pack\(s\) enabled|no data packs enabled")
        require((PACK_ID in pack_response) == pack, pack_response)
        console.tickets(OVERWORLD, extra=False, create=create_overworld)
        console.block(OVERWORLD, "gold_block", create_overworld)
        if pack:
            console.tickets(PROBE, extra=True, create=create_probe)
            console.block(PROBE, "emerald_block", create_probe)
            result["probe_registered"] = True
        else:
            line = console.command(f"execute in {PROBE} if loaded {COORDS}", "Unknown dimension|Test passed|Test failed")
            result["probe_registered"] = "Unknown dimension" not in line
            if result["probe_registered"]:
                console.tickets(PROBE, extra=True, create=False)
                console.block(PROBE, "emerald_block", False)
        console.command("save-all flush", "Saved the game")
        result["probes_passed"] = True
    except (EOFError, RuntimeError, TimeoutError) as exc:
        result["error"] = f"{type(exc).__name__}: {exc}"
    finally:
        if process.poll() is None and result["ready"]:
            try:
                console.send("stop")
            except OSError:
                pass
        try:
            result["exit_code"] = process.wait(timeout=70)
        except subprocess.TimeoutExpired:
            result["forced_termination"] = True
            process.kill()
            result["exit_code"] = process.wait()
        console.reader.join()
        for filename in ("latest.log", "debug.log"):
            log = args.runtime / "logs" / filename
            if log.exists():
                shutil.copyfile(log, phase / filename)
        write_json(phase / "result.json", result)
    files = snapshot(world, phase, archive=not name.endswith("rollback"))
    print(f"{name}: ready={result['ready']}, probes={result['probes_passed']}, exit={result['exit_code']}", flush=True)
    require(not result["forced_termination"], f"Inspect {phase / 'result.json'}")
    if not removal:
        require(result["probes_passed"] and result["exit_code"] == 0 and not result["forced_termination"],
                f"Inspect {phase / 'result.json'}")
    return files


def install_pack(pack_source, world):
    destination = world / "datapacks" / PACK
    shutil.copytree(pack_source, destination)
    require(inventory(destination) == inventory(pack_source), "Installed pack hash mismatch")


def remove_and_restore(args, prefix, world_name):
    world = args.runtime / world_name
    pack = world / "datapacks" / PACK
    parked = args.evidence / f"{prefix}-parked-pack"
    pack.rename(parked)
    require(inventory(parked) == inventory(args.pack), "Removed pack hash mismatch")
    before = args.evidence / f"{prefix}-before-removal-boot"
    before.mkdir()
    snapshot(world, before)
    after_removal = run_phase(args, f"{prefix}03-removed", world_name, removal=True)
    install_pack(parked, world)
    restored_input = args.evidence / f"{prefix}-before-restored-boot"
    restored_input.mkdir()
    restored_files = snapshot(world, restored_input)
    pack_path = f"datapacks/{PACK}/"
    require({name: entry for name, entry in restored_files.items() if not name.startswith(pack_path)}
            == after_removal, "Files other than the pack changed before the restoration boot")
    run_phase(args, f"{prefix}04-restored", world_name, pack=True)


def restore_backup(phase, target):
    backup = phase / "world.zip"
    expected = json.loads((phase / "backup.json").read_text("utf-8"))
    require(sha256(backup) == expected["sha256"], f"Backup checksum changed: {backup}")
    target.mkdir()
    with zipfile.ZipFile(backup) as archive:
        archive.extractall(target)
    files = json.loads((phase / "files.json").read_text("utf-8"))
    require(inventory(target) == files, f"Restored directory differs: {target}")
    return {"archive_sha256": expected["sha256"], "verified_files": len(files)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--runtime", required=True, type=Path, help="Fresh dedicated server directory")
    parser.add_argument("--evidence", required=True, type=Path, help="New directory alongside runtime, on the same drive")
    parser.add_argument("--java", required=True, type=Path, help="JDK 25 java.exe")
    parser.add_argument("--port", type=int, default=25575)
    args = parser.parse_args()
    args.runtime = args.runtime.resolve(strict=True)
    args.evidence = args.evidence.resolve()
    args.java = args.java.resolve(strict=True)
    args.pack = Path(__file__).resolve().parents[1] / PACK
    require(args.runtime.parent == args.evidence.parent, "Runtime and evidence must be siblings in an isolated directory")
    require("eula=true" in (args.runtime / "eula.txt").read_text("utf-8").splitlines(),
            "The project owner must approve and manually provision eula.txt for this instance")
    require(not (args.runtime / "server.properties").exists(), "Runtime must be fresh")
    require((args.runtime / "vanilla-26.3.jar").is_file(), "Missing unmodified Mojang server JAR")
    world_a, world_b, rollback = "m1-05-a", "m1-05-b", "m1-05-a-rollback"
    for name in (world_a, world_b, rollback):
        require(not (args.runtime / name).exists(), f"World already exists: {name}")
    args.evidence.mkdir()
    for name in ("appdata", "localappdata", "tmp"):
        (args.runtime / name).mkdir(exist_ok=True)
    write_json(args.evidence / "pack.json", inventory(args.pack))
    run_phase(args, "A01-vanilla", world_a, vanilla=True, create_overworld=True)
    install_pack(args.pack, args.runtime / world_a)
    run_phase(args, "A02-installed", world_a, pack=True, create_probe=True)
    remove_and_restore(args, "A", world_a)

    install_pack(args.pack, args.runtime / world_b)
    run_phase(args, "B02-preinstalled", world_b, pack=True, preinstalled=True,
              create_overworld=True, create_probe=True)
    remove_and_restore(args, "B", world_b)

    verification = restore_backup(args.evidence / "A01-vanilla", args.runtime / rollback)
    write_json(args.evidence / "rollback-verified.json", verification)
    run_phase(args, "A05-rollback", rollback, vanilla=True)


if __name__ == "__main__":
    main()

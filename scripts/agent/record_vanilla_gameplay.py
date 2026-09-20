#!/usr/bin/env python3
"""Record the existing Fabric production client with Xcode's Game Performance template.

No verdict is inferred from profiler output. Gameplay reports and raw Instruments
data remain separate from render correctness and controlled performance acceptance.
"""
import argparse
import ctypes
import json
import os
from pathlib import Path
import signal
import subprocess
import time
import uuid
import zipfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--template", default="Game Performance")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    jar = args.jar.resolve()
    with zipfile.ZipFile(jar) as archive:
        identity = json.loads(archive.read("metallum-build-identity.json"))
    if identity["dirty"]:
        raise RuntimeError("Build the committed source before recording a production artifact")
    command = [str(root / "gradlew"), "--no-daemon", "-p", str(root / ".github/ci/minecraft-e2e"),
               f"-PmetallumJar={jar}", f"-PmetallumSourceSha={identity['sourceSha']}",
               "-Pmetallum.noOptionalMods=true", "-Pgameplay=true", "-PwaitForProfiler=true",
               "-Pp1Metal4Lane=candidate",
               f"-PevidenceDir={output}", "runProductionClientGameTest"]
    recording = None
    # Xcode 27 supplies a Darwin notification when all instruments are recording.
    # Do not guess readiness from a delay or let attachment startup consume the route.
    notify = ctypes.CDLL("/usr/lib/system/libsystem_notify.dylib")
    token = ctypes.c_int()
    notification = f"com.metallum.gameplay.{uuid.uuid4().hex}"
    if notify.notify_register_check(notification.encode(), ctypes.byref(token)) != 0:
        raise RuntimeError("Cannot register Instruments recording notification")
    changed = ctypes.c_int()
    notify.notify_check(token, ctypes.byref(changed))
    receipt = {"source": identity, "clientCommand": command, "template": args.template,
               "claim": "diagnostic gameplay recording; not a performance acceptance verdict"}
    with (output / "client.log").open("w") as log, (output / "instruments.log").open("w") as trace_log:
        client = subprocess.Popen(command, cwd=root, stdout=log, stderr=subprocess.STDOUT,
                                  start_new_session=True)
        try:
            deadline = time.monotonic() + 600
            ready = output / "gameplay-ready.json"
            while not ready.exists():
                if client.poll() is not None:
                    raise RuntimeError(f"Client exited before gameplay was ready: {client.returncode}")
                if time.monotonic() > deadline:
                    raise TimeoutError("Client did not reach gameplay readiness within 10 minutes")
                time.sleep(0.5)
            pid = json.loads(ready.read_text())["pid"]
            trace_command = ["xcrun", "xctrace", "record", "--template", args.template,
                             "--attach", str(pid), "--output", str(output / "gameplay.trace"),
                             "--time-limit", "120s", "--window", "120s", "--no-prompt",
                             "--notify-tracing-started", notification]
            receipt["traceCommand"] = trace_command
            recording = subprocess.Popen(trace_command, stdout=trace_log, stderr=subprocess.STDOUT)
            deadline = time.monotonic() + 45
            while True:
                notify.notify_check(token, ctypes.byref(changed))
                if changed.value:
                    break
                if recording.poll() is not None:
                    raise RuntimeError(f"Instruments could not start: {recording.returncode}; see instruments.log")
                if time.monotonic() > deadline:
                    raise TimeoutError("Instruments did not signal recording started")
                time.sleep(0.2)
            (output / "profiler-started").touch()
            deadline = time.monotonic() + 300
            while not (output / "gameplay.json").exists():
                if client.poll() is not None:
                    raise RuntimeError("Client exited before completing gameplay")
                if time.monotonic() > deadline:
                    raise TimeoutError("Gameplay exceeded five minutes")
                time.sleep(0.5)
            receipt["gameplay"] = json.loads((output / "gameplay.json").read_text())
            if recording.poll() is None:
                recording.send_signal(signal.SIGINT)
            receipt["traceExitCode"] = recording.wait(timeout=60)
            receipt["clientExitCode"] = client.wait(timeout=180)
            if receipt["gameplay"]["status"] != "completed" or receipt["clientExitCode"] != 0:
                raise RuntimeError("Gameplay/client failed; recorded trace is diagnostic only")
            if receipt["traceExitCode"] != 0:
                raise RuntimeError("Instruments recording failed; see instruments.log")
        except BaseException as failure:
            receipt["failure"] = str(failure)
            raise
        finally:
            if recording is not None and recording.poll() is None:
                recording.send_signal(signal.SIGINT)
                try:
                    recording.wait(timeout=60)
                except subprocess.TimeoutExpired:
                    recording.terminate()
            if client.poll() is None:
                os.killpg(client.pid, signal.SIGTERM)
            notify.notify_cancel(token)
            (output / "recording.json").write_text(json.dumps(receipt, indent=2) + "\n")
    subprocess.run(["xcrun", "xctrace", "export", "--input", str(output / "gameplay.trace"),
                    "--toc", "--output", str(output / "trace-toc.xml")], check=True)
    print(output / "recording.json")


if __name__ == "__main__":
    main()

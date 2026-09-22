#!/usr/bin/env python3
"""Record the existing Fabric production client with Xcode's Game Performance template.

No verdict is inferred from profiler output. Gameplay reports and raw Instruments
data remain separate from render correctness and controlled performance acceptance.
"""
import argparse
import ctypes
import json
import os
import re
from pathlib import Path
import signal
import subprocess
import sys
import time
import uuid
import zipfile


TRIAL_ID_PATTERN = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,159}")
PHASE_SAMPLE = {"stationary": "stationary-full-view", "streaming": "flight-new-chunks"}


def snapshot_identity(initial_world):
    """Read the immutable snapshot identity without changing the supplied world."""
    if initial_world.is_symlink():
        raise RuntimeError(f"Initial world snapshot must not be a symlink: {initial_world}")
    snapshot = initial_world.resolve()
    manifest_path = snapshot.with_name(snapshot.name + "-manifest.json")
    if not snapshot.is_dir() or snapshot.is_symlink():
        raise RuntimeError(f"Initial world snapshot is not a real directory: {snapshot}")
    if not manifest_path.is_file() or manifest_path.is_symlink():
        raise RuntimeError(f"Initial world snapshot manifest is missing: {manifest_path}")
    try:
        manifest = json.loads(manifest_path.read_text())
    except (OSError, json.JSONDecodeError) as failure:
        raise RuntimeError(f"Initial world snapshot manifest is unreadable: {manifest_path}") from failure
    identity = manifest.get("identity") if isinstance(manifest, dict) else None
    files = manifest.get("files") if isinstance(manifest, dict) else None
    snapshot_sha = identity.get("snapshotSha256") if isinstance(identity, dict) else None
    paths = {entry.get("path") for entry in files if isinstance(entry, dict)} if isinstance(files, list) else set()
    if (not isinstance(snapshot_sha, str) or re.fullmatch(r"[0-9a-f]{64}", snapshot_sha) is None
            or not isinstance(identity, dict) or identity.get("snapshotDirectory") != snapshot.name
            or "level.dat" not in paths):
        raise RuntimeError(f"Initial world snapshot manifest has no valid immutable identity: {manifest_path}")
    return {"path": str(snapshot), "manifest": str(manifest_path), "snapshotSha256": snapshot_sha}


def record_snapshot_identity(receipt, supplied_snapshot):
    """Bind the runtime replay report to the caller-selected snapshot, if any."""
    gameplay = receipt["gameplay"]
    world = gameplay.get("world", {}) if isinstance(gameplay, dict) else {}
    runtime_sha = world.get("replaySourceSnapshotSha256") if isinstance(world, dict) else None
    if supplied_snapshot is not None:
        if runtime_sha != supplied_snapshot["snapshotSha256"]:
            raise RuntimeError("Gameplay replay snapshot identity differs from the supplied immutable snapshot")
        supplied_snapshot["runtimeSnapshotSha256"] = runtime_sha
        receipt["initialWorld"] = supplied_snapshot
        return
    initial_content = gameplay.get("frameEvidenceProfile", {}).get("initialContent", {})
    if isinstance(initial_content, dict) and isinstance(initial_content.get("snapshotSha256"), str):
        receipt["generatedInitialContentSnapshotSha256"] = initial_content["snapshotSha256"]


def verify_frame_evidence(output, frame_evidence_mode, expected_head, root, run_command=None,
                          *, expected_trial_id=None, expected_phase=None):
    """Verify the final bounded archive after the client has exited normally."""
    if frame_evidence_mode == "off":
        return None
    evidence = output / "frame-evidence.json"
    if not evidence.is_file():
        raise RuntimeError("Frame evidence was requested but the production client did not export frame-evidence.json")
    raw_evidence = json.loads(evidence.read_text())
    archive = raw_evidence.get("archive")
    if raw_evidence.get("schemaVersion") != 2 or not isinstance(archive, dict):
        raise RuntimeError("Frame evidence did not use the required bounded archive schema")
    if archive.get("complete") is not True:
        raise RuntimeError("Frame evidence archive was not complete after client shutdown")
    identity = raw_evidence.get("identity")
    if not isinstance(identity, dict):
        raise RuntimeError("Frame evidence archive is missing runtime identity")
    if identity.get("instrumentationMode") != frame_evidence_mode:
        raise RuntimeError("Frame evidence archive mode differs from the requested runner mode")
    if expected_trial_id is not None and identity.get("trialId") != expected_trial_id:
        raise RuntimeError("Frame evidence archive trial identity differs from the requested trial")
    if expected_phase is not None:
        profile = raw_evidence.get("window", {}).get("profile", {})
        actual_phase = profile.get("route", {}).get("samplePhase") if isinstance(profile, dict) else None
        if actual_phase != PHASE_SAMPLE[expected_phase]:
            raise RuntimeError("Frame evidence archive phase differs from the requested runner phase")
    if run_command is None:
        run_command = subprocess.run
    verification = run_command(
        [sys.executable, str(root / "scripts/agent/verify_frame_evidence.py"), str(evidence),
         "--expected-head", expected_head, "--require-packaged"],
        cwd=root, capture_output=True, text=True)
    verification_path = output / "frame-evidence-verification.json"
    verification_path.write_text(verification.stdout)
    if verification.returncode != 0:
        detail = verification.stderr.strip() or verification.stdout.strip()
        raise RuntimeError(f"Frame evidence verification failed: {detail}")
    verification_result = json.loads(verification.stdout)
    if verification_result.get("instrumentationMode") != frame_evidence_mode:
        raise RuntimeError("Frame evidence verifier returned a mode different from the requested runner mode")
    return {
        "status": verification_result.get("status"),
        "instrumentationMode": verification_result.get("instrumentationMode"),
        "trialId": identity.get("trialId"),
        "physicalPerformanceAcceptance": verification_result.get("physicalPerformanceAcceptance"),
        "path": verification_path.name,
    }


def finalize_client_run(receipt, recording, client, output, root, source_sha,
                        frame_evidence_mode, run_command=None, *, expected_trial_id=None,
                        expected_phase=None):
    """Close the profiler/client, then verify evidence emitted during client shutdown."""
    if recording is not None:
        if recording.poll() is None:
            recording.send_signal(signal.SIGINT)
        receipt["traceExitCode"] = recording.wait(timeout=600)
    receipt["clientExitCode"] = client.wait(timeout=180)
    if receipt["gameplay"]["status"] != "completed" or receipt["clientExitCode"] != 0:
        raise RuntimeError("Gameplay/client failed; recorded trace is diagnostic only")
    if receipt.get("traceExitCode", 0) != 0:
        raise RuntimeError("Instruments recording failed; see instruments.log")
    receipt["frameEvidenceVerification"] = verify_frame_evidence(
        output, frame_evidence_mode, source_sha, root, run_command,
        expected_trial_id=expected_trial_id, expected_phase=expected_phase)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--stationary-baseline", action="store_true",
                        help="Test-only fixed-view 60 FPS/VSync profile; no movement route")
    parser.add_argument("--template", default="Game Performance")
    parser.add_argument("--initial-world", type=Path, help="Replay an initial-world snapshot with its sibling manifest; input stays immutable")
    parser.add_argument("--frame-evidence-phase", choices=("stationary", "streaming"), default="stationary",
                        help="Select the predeclared stationary or existing input-driven streaming window")
    parser.add_argument("--frame-evidence", choices=("off", "timing", "diagnostic"), default="off",
                        help="Bounded 5s warmup/10s source-to-present observation; use --metrics-only for timing")
    parser.add_argument("--trial-id",
                        help="Stable identity for this trial; defaults to the output directory name")
    parser.add_argument("--metrics-only", action="store_true",
                        help="Run the identical route without Instruments/JFR, retaining source-frame metrics")
    parser.add_argument("--capture-seconds", type=int, default=120,
                        help="Instruments clip length; short clips avoid losing early GPU events in long traces")
    parser.add_argument("--render-labels", action="store_true",
                        help="Enable Vanilla renderDebugLabels for diagnostic pass attribution")
    parser.add_argument("--presentation-metrics", action="store_true",
                        help="Sample native drawable wait once per frame; diagnostic, excluded from timing trials")
    parser.add_argument("--reuse-encoder-state", action="store_true",
                        help="Enable the candidate CPU state/scratch reuse; off is the rollback path")
    parser.add_argument("--terrain-slice-cache", action="store_true",
                        help="Reuse mesh-owned terrain allocation metadata until allocation mutation")
    parser.add_argument("--verify-terrain-cache", action="store_true",
                        help="Compare every cache hit with live Vanilla allocation lookup; diagnostic only")
    args = parser.parse_args()
    if not 1 <= args.capture_seconds <= 120:
        parser.error("--capture-seconds must be between 1 and 120")
    if args.frame_evidence == "timing" and (not args.metrics_only or args.presentation_metrics or args.render_labels):
        parser.error("timing frame evidence requires --metrics-only and no diagnostic presentation metrics or render labels")
    if args.metrics_only and args.render_labels:
        parser.error("render labels are diagnostic-only; omit them for timing trials")
    if args.verify_terrain_cache and not args.terrain_slice_cache:
        parser.error("--verify-terrain-cache requires --terrain-slice-cache")
    if args.stationary_baseline and args.initial_world is None:
        parser.error("stationary baseline requires --initial-world with its verified immutable snapshot")
    if args.stationary_baseline and (args.frame_evidence == "diagnostic" or args.frame_evidence_phase != "stationary" or not args.metrics_only
            or args.presentation_metrics or args.reuse_encoder_state or args.terrain_slice_cache):
        parser.error("stationary baseline requires stationary metrics-only without diagnostic getters or optimization experiments")
    root = Path(__file__).resolve().parents[2]
    output = args.output.resolve()
    trial_id = args.trial_id or output.name
    if TRIAL_ID_PATTERN.fullmatch(trial_id) is None:
        parser.error("--trial-id and the output directory name must be 1-160 ASCII letters, digits, '.', '_' or '-'")
    initial_world = snapshot_identity(args.initial_world) if args.initial_world is not None else None
    output.mkdir(parents=True, exist_ok=False)
    jar = args.jar.resolve()
    with zipfile.ZipFile(jar) as archive:
        identity = json.loads(archive.read("metallum-build-identity.json"))
    if identity["dirty"]:
        raise RuntimeError("Build the committed source before recording a production artifact")
    displays = json.loads(subprocess.check_output(
        ["system_profiler", "SPDisplaysDataType", "-json"], text=True))
    main_display = next((display for gpu in displays["SPDisplaysDataType"]
                         for display in gpu.get("spdisplays_ndrvs", [])
                         if display.get("spdisplays_main") == "spdisplays_yes"), None)
    if main_display is None:
        raise RuntimeError("Physical main display unavailable; cannot run a native-resolution presentation trial")
    native_size = re.search(r"(\d+)\s*x\s*(\d+)", main_display["_spdisplays_pixels"])
    if native_size is None:
        raise RuntimeError("Cannot establish the physical display resolution")
    width, height = map(int, native_size.groups())
    command = [str(root / "gradlew"), "--no-daemon", "-p", str(root / ".github/ci/minecraft-e2e"),
               f"-PmetallumJar={jar}", f"-PmetallumSourceSha={identity['sourceSha']}",
               "-Pmetallum.noOptionalMods=true", "-Pgameplay=true",
               f"-PframeEvidenceMode={args.frame_evidence}",
               f"-PframeEvidenceSegmented={str(args.frame_evidence != 'off').lower()}",
               f"-PstationaryBaseline={str(args.stationary_baseline).lower()}",
               f"-PframeEvidencePhase={args.frame_evidence_phase}",
               f"-PframeEvidenceTrialId={trial_id}",
               f"-PwaitForProfiler={str(not args.metrics_only).lower()}",
               f"-PgameplayJfr={str(not args.metrics_only).lower()}",
               f"-PrenderDebugLabels={str(args.render_labels).lower()}",
               f"-PpresentationMetrics={str(args.presentation_metrics).lower()}",
               "-Pp1Metal4Lane=candidate",
               f"-PreuseEncoderState={str(args.reuse_encoder_state).lower()}",
               f"-PterrainSliceCache={str(args.terrain_slice_cache).lower()}",
               f"-PverifyTerrainSliceCache={str(args.verify_terrain_cache).lower()}",
               f"-PnativeWidth={width}", f"-PnativeHeight={height}",
               f"-PevidenceDir={output}", "runProductionClientGameTest"]
    if args.initial_world is not None:
        command.insert(-1, f"-PinitialWorld={args.initial_world.resolve()}")
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
    receipt = {"source": identity, "clientCommand": command, "template": None if args.metrics_only else args.template,
               "profilingEnabled": not args.metrics_only,
               "captureSeconds": None if args.metrics_only else args.capture_seconds,
               "renderDebugLabels": args.render_labels,
               "presentationMetrics": args.presentation_metrics,
               "frameEvidenceMode": args.frame_evidence,
               "frameEvidenceSegmented": args.frame_evidence != "off",
               "frameEvidencePhase": args.frame_evidence_phase,
               "frameEvidenceTrialId": trial_id,
               "initialWorld": initial_world,
               "frameEvidenceVerification": None,
               "frameEvidenceProfile": "vanilla-stationary-60-v1" if args.stationary_baseline else f"vanilla-normal-{args.frame_evidence_phase}-v1",
               "warmupNanos": 5_000_000_000, "sampleNanos": 10_000_000_000,
               "terrainSliceCache": args.terrain_slice_cache,
               "verifyTerrainSliceCache": args.verify_terrain_cache,
               "display": main_display,
               "clientEnvironment": {"SDL_VIDEO_MAC_FULLSCREEN_SPACES": "0"},
               "claim": "diagnostic gameplay recording; not a performance acceptance verdict"}
    with (output / "client.log").open("w") as log, (output / "instruments.log").open("w") as trace_log:
        client = subprocess.Popen(command, cwd=root, stdout=log, stderr=subprocess.STDOUT,
                                  env={**os.environ, **receipt["clientEnvironment"]},
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
            if not args.metrics_only:
                trace_command = ["xcrun", "xctrace", "record", "--template", args.template,
                                 "--attach", str(pid), "--output", str(output / "gameplay.trace"),
                                 "--time-limit", f"{args.capture_seconds}s",
                                 "--window", f"{args.capture_seconds}s", "--no-prompt",
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
            record_snapshot_identity(receipt, initial_world)
            finalize_client_run(receipt, recording, client, output, root, identity["sourceSha"],
                                args.frame_evidence, expected_trial_id=trial_id,
                                expected_phase=args.frame_evidence_phase)
        except BaseException as failure:
            receipt["failure"] = str(failure)
            raise
        finally:
            if recording is not None and recording.poll() is None:
                recording.send_signal(signal.SIGINT)
                try:
                    recording.wait(timeout=600)
                except subprocess.TimeoutExpired:
                    recording.terminate()
            if client.poll() is None:
                os.killpg(client.pid, signal.SIGTERM)
            notify.notify_cancel(token)
            (output / "recording.json").write_text(json.dumps(receipt, indent=2) + "\n")
    if not args.metrics_only:
        subprocess.run(["xcrun", "xctrace", "export", "--input", str(output / "gameplay.trace"),
                        "--toc", "--output", str(output / "trace-toc.xml")], check=True)
    print(output / "recording.json")


if __name__ == "__main__":
    main()

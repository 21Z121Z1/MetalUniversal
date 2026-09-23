#!/usr/bin/env python3
"""Execute one versioned physical workload through the existing production GameTest.

No hosted/synthetic execution or source-return receipt is physical performance
acceptance. Diagnostic Instruments/JFR capture stays in record_vanilla_gameplay.py.
"""
import argparse
from datetime import datetime, timezone
import os
from pathlib import Path
import platform
import re
import shutil
import signal
import subprocess
import sys
import time

from frame_trial_contract import (
    WORKLOADS, PRODUCERS, BACKENDS, EFFICIENCY_FLAGS, require, packaged_identity, snapshot_identity,
    shader_identity, canonical_hash, sha256, parse_json, atomic_json, verify_loaded,
    validate_gameplay, finite_seconds, artifact_inventory, runner_lock,
)


def command_fact(command, timeout=30):
    """A bounded endpoint fact. Missing commands are not zero-valued measurements."""
    try:
        result = subprocess.run(command, capture_output=True, text=True, timeout=timeout, check=False)
        if result.returncode != 0:
            return {"authority": "unavailable", "reason": "command-exit-" + str(result.returncode), "command": command}
        return {"authority": "measured", "command": command, "value": result.stdout.strip() or result.stderr.strip(),
                "observedAt": datetime.now(timezone.utc).isoformat()}
    except (OSError, subprocess.TimeoutExpired) as error:
        return {"authority": "unavailable", "reason": type(error).__name__, "command": command}


def environment_facts():
    facts = {"os": command_fact(["sw_vers"]), "architecture": command_fact(["uname", "-m"]),
             "hardwareModel": command_fact(["sysctl", "-n", "hw.model"]),
             "memoryBytes": command_fact(["sysctl", "-n", "hw.memsize"]),
             "jdk": command_fact(["java", "-version"]), "xcode": command_fact(["xcodebuild", "-version"]),
             "sdk": command_fact(["xcrun", "--sdk", "macosx", "--show-sdk-version"]),
             "powerPolicy": command_fact(["pmset", "-g", "custom"]),
             "powerSource": command_fact(["pmset", "-g", "batt"]),
             "thermal": command_fact(["pmset", "-g", "therm"]),
             "energyJoules": {"authority": "unavailable", "reason": "no-calibrated-energy-source"},
             "inputToPhoton": {"authority": "unavailable", "reason": "external-physical-instrument-required"}}
    source = facts["powerSource"]
    if source["authority"] == "measured":
        match = re.search(r"Now drawing from '([^']+)'", source["value"])
        # Keep the observed source, not serial numbers, battery percentage or a guessed wattage.
        facts["powerSource"] = {"authority": "measured" if match else "unavailable",
                                "value": match.group(1) if match else None,
                                "reason": None if match else "unrecognized-pmset-output"}
    return facts


def physical_display():
    if platform.system() != "Darwin" or platform.machine().lower() not in ("arm64", "aarch64"):
        raise RuntimeError("physical-validation-required: this runner requires macOS on Apple Silicon")
    if os.environ.get("RUNNER_ENVIRONMENT") == "github-hosted" or os.environ.get("METALLUM_HOSTED_METAL_OFFSCREEN") == "true":
        raise RuntimeError("physical-validation-required: hosted offscreen execution cannot run a physical trial")
    result = command_fact(["system_profiler", "SPDisplaysDataType", "-json"], 60)
    if result["authority"] != "measured":
        raise RuntimeError("physical-validation-required: display enumeration unavailable")
    displays = parse_json(result["value"])
    for gpu in displays.get("SPDisplaysDataType", []):
        if "paravirtual" in str(gpu).lower():
            raise RuntimeError("physical-validation-required: a paravirtual GPU is not the target machine")
        for display in gpu.get("spdisplays_ndrvs", []):
            if display.get("spdisplays_main") != "spdisplays_yes":
                continue
            size = re.search(r"(\d+)\s*x\s*(\d+)", display.get("_spdisplays_pixels", ""))
            if not size:
                raise RuntimeError("physical-validation-required: native display pixels are unavailable")
            width, height = map(int, size.groups())
            require(width > 1 and height > 1, "invalid native display dimensions")
            return {"width": width, "height": height, "authority": "system_profiler.SPDisplaysDataType",
                    "display": {key: display[key] for key in ("_name", "_spdisplays_pixels", "spdisplays_resolution",
                         "spdisplays_main", "spdisplays_online", "spdisplays_display_type", "spdisplays_retina") if key in display},
                    "gpu": {key: gpu[key] for key in ("sppci_model", "spdisplays_metal", "spdisplays_vendor") if key in gpu},
                    "actualScanout": "physical-validation-required", "refreshCapability": "unavailable: no display-link range receipt"}
    raise RuntimeError("physical-validation-required: no real main display was reported")


def workload_specification(args, root, output_size, world, shader):
    # Identity excludes A/B binary and observer choice, but includes the actual driver and fixed work.
    # Backend is a declared implementation dimension; pair checking handles it explicitly.
    files = (".github/ci/minecraft-e2e/src/main/java/com/metallum/e2e/FrameWorkloads.java",
             ".github/ci/minecraft-e2e/src/main/java/com/metallum/e2e/VanillaGameplay.java",
             ".github/ci/minecraft-e2e/src/main/java/com/metallum/e2e/SourceWindow.java",
             ".github/ci/minecraft-e2e/build.gradle", ".github/ci/minecraft-e2e/gradle.properties", "gradle.properties")
    return {"protocolVersion": 1, "workloadId": args.workload, "producer": args.producer,
            "backend": args.backend, "warmupNs": round(args.warmup_seconds * 1e9),
            "sampleNs": round(args.sample_seconds * 1e9), "targetFps": args.target_fps,
            "output": {key: output_size[key] for key in ("width", "height")}, "initialWorld": world,
            "prepareScene": args.prepare_scene, "shaderPack": shader,
            "qualityContract": "Fabulous/native-output/32-render-distance; original simulation and entity behavior",
            "shaderCostClass": "user-assigned I0/I1, not inferred from the file name",
            "driverFiles": {name: sha256(root / name) for name in files},
            "cacheProtocol": "fresh disposable client and world; shared OS/Gradle caches are not a cold-cache claim"}


def workload_command(args, root, output, artifact, spec):
    values = {"metallumJar": str(args.jar.resolve()), "metallumSourceSha": artifact["build"]["sourceSha"],
              "metallum.noOptionalMods": args.producer == "vanilla", "gameplay": True,
              "frameWorkload": args.workload, "trialProducer": args.producer, "trialBackend": args.backend,
              "optimizationProfile": "frame-trial-v1",
              "workloadSha256": canonical_hash(spec), "prepareScene": args.prepare_scene,
              "warmupNs": spec["warmupNs"], "sampleNs": spec["sampleNs"], "targetFps": args.target_fps,
              "frameEvidenceMode": args.frame_evidence, "frameEvidenceTrialId": output.name,
              "frameEvidenceSegmented": True, "waitForProfiler": False, "gameplayJfr": False,
              "renderDebugLabels": False, "presentationMetrics": False, "stationaryBaseline": False,
              "frameEvidencePhase": "stationary", "reuseEncoderState": args.reuse_encoder_state,
              "terrainSliceCache": args.terrain_slice_cache, "verifyTerrainSliceCache": args.verify_terrain_cache,
              "nativeWidth": spec["output"]["width"], "nativeHeight": spec["output"]["height"],
              "evidenceDir": str(output), "clientRunDir": str(output / "client-instance"),
              "shaderPackName": spec["shaderPack"]["stagedName"] if spec["shaderPack"] else ""}
    for key, flag in EFFICIENCY_FLAGS.items():
        values[key] = getattr(args, flag.replace("-", "_"), False)
    values["efficiencyTelemetry"] = args.frame_evidence != "off"
    if args.initial_world is not None:
        values["initialWorld"] = str(args.initial_world.resolve())
    encoded = lambda value: str(value).lower() if isinstance(value, bool) else str(value)
    return [str(root / "gradlew"), "--no-daemon", "--max-workers=2", "-p", str(root / ".github/ci/minecraft-e2e"),
            *[f"-P{key}={encoded(value)}" for key, value in values.items()], "runProductionClientGameTest"]


def stage_shader(args, output, shader):
    if shader is None:
        return
    instance = output / "client-instance"
    (instance / "shaderpacks").mkdir(parents=True, exist_ok=False)
    (instance / "config").mkdir(exist_ok=False)
    target = instance / "shaderpacks" / shader["stagedName"]
    shutil.copyfile(args.shader_pack, target)
    require(sha256(target) == shader["sha256"], "staged shader bytes differ")
    # These are Iris's persisted selection keys, not proof of activation. The runtime receipt is mandatory.
    (instance / "config/iris.properties").write_text("enableShaders=true\nshaderPack=" + shader["stagedName"] + "\n")


def collect_workload_result(output, expected, specification):
    loaded = parse_json((output / "artifact-identity.json").read_bytes())
    verify_loaded(expected, loaded, None)
    require(loaded.get("loadedNativeSha256") == expected["packagedNativeSha256"], "actually loaded native hash is unavailable/mismatched")
    gameplay = parse_json((output / "gameplay.json").read_bytes())
    result = validate_gameplay(gameplay, specification)
    result["loadedArtifact"] = loaded
    result["optimizationActivation"] = gameplay.get("optimizationActivation", {
        "status": "unavailable", "reason": "loaded-driver-does-not-report-activation"})
    result["terrainSliceCacheEvidence"] = gameplay.get("terrainSliceCacheEvidence")
    result["efficiencyActivation"] = gameplay.get("efficiencyActivation", {})
    # The output copy and the input were independently verified; a seed is not substituted for either.
    snapshot = snapshot_identity(output / "initial-world")
    if specification["initialWorld"] is not None:
        require(snapshot == specification["initialWorld"], "retained initial world differs from the input")
    result["initialWorld"] = snapshot
    frame_path = output / "frame-evidence.json"
    if frame_path.exists():
        from verify_frame_evidence import load_report, verify
        frames = load_report(frame_path)
        verify_loaded(expected, loaded, frames)
        result["frameEvidence"] = verify(frames, expected["build"]["sourceSha"], require_packaged=True, artifact_root=output)
    else:
        result["frameEvidence"] = {"status": "unavailable", "reason": "observer-off-or-missing-output"}
    return result


def run_workload(args, root):
    """One trial through the existing production client; never retries or filters a result."""
    finite_seconds(args.warmup_seconds, 0, 3600, "warmup seconds")
    finite_seconds(args.sample_seconds, 1, 86400, "sample seconds")
    require(15 <= args.target_fps < 260, "target cadence must be explicit and below Vanilla's unlimited sentinel")
    energy_profile = getattr(args, "energy_profile", None)
    require(getattr(args, "power_trace", None) is None or energy_profile, "--power-trace requires --energy-profile")
    if energy_profile:
        from fixed_cadence_energy import PROFILE
        require(args.warmup_seconds * 1e9 >= PROFILE["minimumWarmupNs"]
                and args.sample_seconds * 1e9 >= PROFILE["minimumSampleNs"],
                "energy profile requires 30 s warmup and 120 s sample")
    require(args.metrics_only,
            "versioned workload trials use --metrics-only; diagnostic profiler runs stay separate")
    require(not args.prepare_scene or args.workload in ("C0", "G0"), "only C0/G0 have prepared content")
    require(args.initial_world is not None or args.bootstrap, "a trial requires an immutable initial world; use --bootstrap once to create one")
    require(not args.prepare_scene or args.bootstrap, "scene preparation is bootstrap-only, never part of a paired trial")
    require(args.workload != "T0" or args.sample_seconds >= 50, "T0 needs at least 50 seconds for its fixed route")
    require(args.workload != "X0" or args.sample_seconds >= 60, "X0 needs at least a 60-second declared transition window")
    require(not args.workload.startswith("I") or args.producer == "iris", "I0/I1 require Iris")
    if args.producer != "iris":
        require(args.shader_pack is None and args.shader_pack_sha256 is None, "a non-Iris trial cannot silently load a pack")
    output = args.output.absolute()
    output.mkdir(parents=True, exist_ok=False)
    require(not output.is_symlink(), "trial output cannot be a symlink")
    manifest = {"schemaVersion": 1, "trialId": output.name, "complete": False, "status": "preflight",
                "startedAt": datetime.now(timezone.utc).isoformat(), "finishedAt": None, "exitCode": None,
                "evidenceCompletion": "unavailable", "physicalPerformanceAcceptance": "physical-validation-required",
                "productPromotable": False, "unavailable": [], "claim": "controlled workload observations, not product acceptance"}
    atomic_json(output / "trial-manifest.json", manifest)
    client = None
    failure = None
    try:
        # Hidden JVM/driver overrides would change the actual comparison contract.
        forbidden = [key for key in ("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS", "GRADLE_OPTS",
                                    "DYLD_INSERT_LIBRARIES", "MTL_DEBUG_LAYER", "MTL_SHADER_VALIDATION")
                     if os.environ.get(key) not in (None, "", "0")]
        require(not forbidden, "undeclared runtime overrides: " + ",".join(forbidden))
        artifact = packaged_identity(args.jar)
        world = snapshot_identity(args.initial_world) if args.initial_world is not None else None
        shader = shader_identity(args.shader_pack, args.shader_pack_sha256) if args.producer == "iris" else None
        manifest.update({"artifact": artifact, "initialWorld": world, "shaderPack": shader,
                         "observerMode": args.frame_evidence, "bootstrap": args.bootstrap,
                         "harness": {"sourceSha": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip(),
                                     "treeSha": subprocess.check_output(["git", "rev-parse", "HEAD^{tree}"], cwd=root, text=True).strip(),
                                     "dirty": bool(subprocess.check_output(["git", "status", "--porcelain", "--untracked-files=normal"], cwd=root, text=True).strip())}})
        require(not manifest["harness"]["dirty"], "the workload harness must be a clean exact checkout")
        if args.preflight_only:
            manifest.update(status="preflight-only", complete=True, evidenceCompletion="not-run")
            manifest["unavailable"].append("physical trial not requested by --preflight-only")
            return 0
        manifest["displayBefore"] = physical_display()
        manifest["environmentBefore"] = environment_facts()
        spec = workload_specification(args, root, manifest["displayBefore"], world, shader)
        manifest["workload"] = spec
        manifest["workloadSha256"] = canonical_hash(spec)
        manifest["features"] = {"reuseEncoderState": args.reuse_encoder_state, "terrainSliceCache": args.terrain_slice_cache,
                                "verifyTerrainSliceCache": args.verify_terrain_cache, "frameEvidenceSegmented": True,
                                "metalFx": "OFF", "frameInterpolation": False, "ordinaryDisplayLink": False}
        manifest["features"].update({key: getattr(args, flag.replace("-", "_"), False) for key, flag in EFFICIENCY_FLAGS.items()})
        command = workload_command(args, root, output, artifact, spec)
        manifest["clientCommand"] = command
        manifest["clientEnvironment"] = {"SDL_VIDEO_MAC_FULLSCREEN_SPACES": "0"}
        manifest["status"] = "running"
        stage_shader(args, output, shader)
        atomic_json(output / "trial-manifest.json", manifest)
        with (output / "client.log").open("x") as log:
            client = subprocess.Popen(command, cwd=root, stdout=log, stderr=subprocess.STDOUT, start_new_session=True,
                                      env={**os.environ, **manifest["clientEnvironment"]})
            # Setup has its own fixed limit; sample length never gets silently clipped to 300 seconds.
            deadline = time.monotonic() + args.warmup_seconds + args.sample_seconds + 1800
            while client.poll() is None:
                if time.monotonic() > deadline:
                    raise TimeoutError("trial exceeded its predeclared setup + warmup + sample + shutdown limit")
                time.sleep(0.5)
        manifest["exitCode"] = client.returncode
        require(client.returncode == 0, "packaged client failed; preserve the failed trial")
        result = collect_workload_result(output, artifact, spec)
        require(args.frame_evidence == "off" or result["frameEvidence"].get("status") in ("valid-observation", "comparison-ready"),
                "enabled observer did not produce verified evidence")
        require(args.frame_evidence != "off" or not (output / "frame-evidence.json").exists(), "observer OFF unexpectedly produced frame evidence")
        if args.reuse_encoder_state:
            activation = result["optimizationActivation"]
            if args.frame_evidence == "off":
                manifest["unavailable"].append("encoder reuse activation counters are disabled in observer-OFF; not an activation/performance claim")
            else:
                require(activation.get("requested") is True and activation.get("active") is True
                        and activation.get("telemetryEnabled") is True,
                        "requested encoder reuse did not produce an activation receipt")
        manifest["observation"] = result
        if getattr(args, "energy_profile", None):
            from fixed_cadence_energy import collect
            result["energy"] = collect(args.power_trace, output, output.name, artifact, result["sourceSampleWindow"])
        for key in EFFICIENCY_FLAGS:
            if manifest["features"][key] and args.frame_evidence != "off":
                require(result["efficiencyActivation"].get(key, {}).get("active") is True,
                        "requested efficiency lane did not activate: " + key)
        manifest["environmentAfter"] = environment_facts()
        manifest["displayAfter"] = physical_display()
        require(manifest["displayBefore"] == manifest["displayAfter"], "display identity changed outside the declared transition")
        for key in ("powerSource", "powerPolicy"):
            before, after = manifest["environmentBefore"][key], manifest["environmentAfter"][key]
            if before["authority"] != "measured" or after["authority"] != "measured":
                manifest["unavailable"].append(key + ": endpoint identity unavailable")
            else:
                require(before["value"] == after["value"], "power source/policy changed; keep trial but reject comparison")
        require(packaged_identity(args.jar) == artifact, "JAR/native file changed during the trial")
        if args.initial_world is not None:
            require(snapshot_identity(args.initial_world) == world, "immutable input world changed during the trial")
        if shader is not None:
            require(shader_identity(args.shader_pack, args.shader_pack_sha256) == shader, "shader input changed during the trial")
        manifest.update(status="valid-observation", complete=True, evidenceCompletion="complete")
        if args.bootstrap:
            manifest["unavailable"].append("bootstrap creates content; it is not an A/A or A/B sample")
        return 0
    except (Exception, KeyboardInterrupt) as error:
        failure = str(error)
        manifest["status"] = "unavailable" if failure.startswith("physical-validation-required:") else "invalid-evidence"
        manifest["failure"] = failure
        print(failure, file=sys.stderr)
        return 2 if manifest["status"] == "unavailable" else 1
    finally:
        if client is not None and client.poll() is None:
            os.killpg(client.pid, signal.SIGTERM)
            try:
                client.wait(timeout=30)
            except subprocess.TimeoutExpired:
                os.killpg(client.pid, signal.SIGKILL)
                client.wait(timeout=30)
        if client is not None:
            manifest["exitCode"] = client.returncode
        manifest["finishedAt"] = datetime.now(timezone.utc).isoformat()
        try:
            manifest["artifacts"] = artifact_inventory(output)
        except Exception as artifact_error:
            manifest.update(status="invalid-evidence", complete=False, artifactFailure=str(artifact_error))
            if failure is None:
                raise
        finally:
            atomic_json(output / "trial-manifest.json", manifest)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--workload", choices=WORKLOADS, required=True)
    parser.add_argument("--producer", choices=PRODUCERS, default="vanilla")
    parser.add_argument("--backend", choices=BACKENDS, default="metal3")
    parser.add_argument("--frame-evidence", choices=("off", "timing", "diagnostic"), default="timing")
    parser.add_argument("--warmup-seconds", type=float, default=30)
    parser.add_argument("--sample-seconds", type=float, default=120)
    parser.add_argument("--target-fps", type=int, default=60)
    for flag in EFFICIENCY_FLAGS.values():
        parser.add_argument("--" + flag, action="store_true")
    parser.add_argument("--energy-profile", choices=("fixed-cadence-energy-v1",))
    parser.add_argument("--power-trace", type=Path, help="Instrument export for this exact trial/source window; absent means unavailable")
    parser.add_argument("--metrics-only", action="store_true", required=True,
                        help="Physical trials never enable JFR, HUD or diagnostic rendering labels")
    parser.add_argument("--initial-world", type=Path)
    parser.add_argument("--bootstrap", action="store_true",
                        help="Create content once; never a paired trial")
    parser.add_argument("--prepare-scene", action="store_true",
                        help="Prepare C0/G0 content before preserving its immutable snapshot")
    parser.add_argument("--shader-pack", type=Path)
    parser.add_argument("--shader-pack-sha256")
    parser.add_argument("--reuse-encoder-state", action="store_true")
    parser.add_argument("--terrain-slice-cache", action="store_true")
    parser.add_argument("--verify-terrain-cache", action="store_true")
    parser.add_argument("--preflight-only", action="store_true",
                        help="Validate inputs without executing a physical trial")
    args = parser.parse_args()
    if args.verify_terrain_cache and not args.terrain_slice_cache:
        parser.error("--verify-terrain-cache requires --terrain-slice-cache")
    if args.verify_terrain_cache and args.frame_evidence != "diagnostic":
        parser.error("--verify-terrain-cache is a diagnostic-only check, not a timing trial")
    root = Path(__file__).resolve().parents[2]
    with runner_lock(root):
        return run_workload(args, root)


if __name__ == "__main__":
    sys.exit(main())

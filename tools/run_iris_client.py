#!/usr/bin/env python3
"""Run an isolated receipt-producing Iris Metal client from a packaged mod JAR."""

import argparse
import json
import os
import pathlib
import shutil
import subprocess
import sys


def load_version(minecraft_root, version_id):
    path = minecraft_root / "versions" / version_id / f"{version_id}.json"
    return json.loads(path.read_text(encoding="utf-8"))


def allowed(library):
    rules = library.get("rules")
    if not rules:
        return True
    result = False
    for rule in rules:
        os_rule = rule.get("os", {})
        if os_rule and os_rule.get("name") not in (None, "osx"):
            continue
        result = rule["action"] == "allow"
    return result


def maven_path(coordinate):
    group, artifact, version, *classifier = coordinate.split(":")
    suffix = f"-{classifier[0]}" if classifier else ""
    return pathlib.Path(group.replace(".", "/")) / artifact / version / f"{artifact}-{version}{suffix}.jar"


def libraries(minecraft_root, version):
    paths = []
    for library in version.get("libraries", []):
        if not allowed(library):
            continue
        artifact = library.get("downloads", {}).get("artifact", {}).get("path")
        relative = pathlib.Path(artifact) if artifact else maven_path(library["name"])
        path = minecraft_root / "libraries" / relative
        if not path.is_file():
            raise FileNotFoundError(path)
        paths.append(path)
    return paths


def copy_file(source, target):
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source, target)


def prepare_game_dir(args):
    game_dir = args.game_dir.resolve()
    game_dir.mkdir(parents=True, exist_ok=True)
    mods_dir = game_dir / "mods"
    mods_dir.mkdir(parents=True, exist_ok=True)
    for path in sorted(args.support_mods_dir.glob("*.jar")):
        copy_file(path, mods_dir / path.name)
    copy_file(args.mod_jar.resolve(), mods_dir / args.mod_jar.name)

    if args.seed_game_dir:
        source_save = args.seed_game_dir.resolve() / "saves" / "New World"
        if source_save.is_dir():
            target_save = game_dir / "saves" / "New World"
            if not target_save.exists():
                shutil.copytree(source_save, target_save)

    shaderpacks = game_dir / "shaderpacks"
    shaderpacks.mkdir(parents=True, exist_ok=True)
    copy_file(args.shader_pack.resolve(), shaderpacks / args.shader_pack.name)

    config_dir = game_dir / "config"
    config_dir.mkdir(parents=True, exist_ok=True)
    (config_dir / "iris.properties").write_text(
        "enableShaders=true\nshaderPack=" + args.shader_pack.name + "\n",
        encoding="utf-8",
    )
    options = game_dir / "options.txt"
    if options.is_file():
        lines = options.read_text(encoding="utf-8").splitlines()
    else:
        lines = []
    normalized = []
    found_backend = False
    found_clean = False
    for line in lines:
        if line.startswith("preferredGraphicsBackend:"):
            normalized.append('preferredGraphicsBackend:"default"')
            found_backend = True
        elif line.startswith("startedCleanly:"):
            normalized.append("startedCleanly:true")
            found_clean = True
        else:
            normalized.append(line)
    if not found_backend:
        normalized.append('preferredGraphicsBackend:"default"')
    if not found_clean:
        normalized.append("startedCleanly:true")
    options.write_text("\n".join(normalized) + "\n", encoding="utf-8")
    return game_dir


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--minecraft-root", type=pathlib.Path, default=pathlib.Path.home() / "Library/Application Support/minecraft")
    parser.add_argument("--game-dir", type=pathlib.Path, required=True)
    parser.add_argument("--seed-game-dir", type=pathlib.Path)
    parser.add_argument("--support-mods-dir", type=pathlib.Path, required=True)
    parser.add_argument("--shader-pack", type=pathlib.Path, required=True)
    parser.add_argument("--mod-jar", type=pathlib.Path, required=True)
    parser.add_argument("--receipt", type=pathlib.Path, required=True)
    parser.add_argument("--control-receipt", type=pathlib.Path, required=True)
    parser.add_argument("--capture-dir", type=pathlib.Path, required=True)
    parser.add_argument("--artifact-jar", type=pathlib.Path, required=True)
    parser.add_argument("--source-commit", required=True)
    parser.add_argument("--expected-dylib-sha256", required=True)
    parser.add_argument("--expected-artifact-jar-sha256", required=True)
    parser.add_argument("--reload-frame", type=int, default=120)
    parser.add_argument("--disable-frame", type=int, default=260)
    parser.add_argument("--enable-frame", type=int, default=320)
    parser.add_argument("--stop-frame", type=int, default=420)
    parser.add_argument("--shadow-distance", type=int, default=32)
    parser.add_argument(
        "--capture-every",
        type=int,
        default=30,
        help="Capture final/shadow readbacks every N sampled frames.",
    )
    parser.add_argument(
        "--shadow-stages",
        action="store_true",
        help="Capture validation-only shadow-stage readbacks at each scene boundary.",
    )
    parser.add_argument("--width", default="1280")
    parser.add_argument("--height", default="720")
    parser.add_argument("--java", type=pathlib.Path, default=pathlib.Path("/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home/bin/java"))
    args = parser.parse_args()
    if args.capture_every <= 0:
        parser.error("--capture-every must be positive")

    game_dir = prepare_game_dir(args)
    receipt = args.receipt.resolve()
    control = args.control_receipt.resolve()
    capture_dir = args.capture_dir.resolve()
    receipt.parent.mkdir(parents=True, exist_ok=True)
    control.parent.mkdir(parents=True, exist_ok=True)
    capture_dir.mkdir(parents=True, exist_ok=True)
    receipt.unlink(missing_ok=True)
    control.unlink(missing_ok=True)

    base_id = "26.2"
    fabric_id = "fabric-loader-0.19.3-26.2"
    base = load_version(args.minecraft_root, base_id)
    fabric = load_version(args.minecraft_root, fabric_id)
    classpath = libraries(args.minecraft_root, base) + libraries(args.minecraft_root, fabric)
    classpath.append(args.minecraft_root / "versions" / base_id / f"{base_id}.jar")
    natives = game_dir / ".metallum-natives"
    for directory in (natives / "java", natives / "jna", natives / "lwjgl", natives / "netty"):
        directory.mkdir(parents=True, exist_ok=True)

    java_args = [
        str(args.java), "-XstartOnFirstThread", "--sun-misc-unsafe-memory-access=allow",
        "--enable-native-access=ALL-UNNAMED", "-Xms1G", "-Xmx4G",
        f"-Djava.library.path={natives / 'java'}",
        f"-Djna.tmpdir={natives / 'jna'}",
        f"-Dorg.lwjgl.system.SharedLibraryExtractPath={natives / 'lwjgl'}",
        f"-Dio.netty.native.workdir={natives / 'netty'}",
        "-Dminecraft.launcher.brand=iris-runtime-candidate",
        "-Dminecraft.launcher.version=1",
        "-Dmetallum.metalfx.mode=OFF",
        "-Dmetallum.metalfx.frameGeneration=false",
        "-Dmetallum.metal4MainRenderer=false",
        "-Dmetallum.iris.strict=true",
        "-Dmetallum.iris.validation.enabled=true",
        f"-Dmetallum.iris.validation.receipt={receipt}",
        f"-Dmetallum.iris.validation.controlReceipt={control}",
        f"-Dmetallum.iris.validation.captureDir={capture_dir}",
        f"-Dmetallum.iris.validation.captureEvery={args.capture_every}",
        f"-Dmetallum.iris.validation.shadowStages={str(args.shadow_stages).lower()}",
        f"-Dmetallum.iris.validation.reloadFrame={args.reload_frame}",
        f"-Dmetallum.iris.validation.disableFrame={args.disable_frame}",
        f"-Dmetallum.iris.validation.enableFrame={args.enable_frame}",
        f"-Dmetallum.iris.validation.stopFrame={args.stop_frame}",
        "-Dmetallum.iris.validation.worldTimeoutFrames=1200",
        f"-Dmetallum.iris.validation.shadowDistance={args.shadow_distance}",
        f"-Dmetallum.iris.validation.expectedCodeSource={game_dir / 'mods' / args.mod_jar.name}",
        f"-Dmetallum.iris.validation.expectedCodeSourceSha256={args.expected_artifact_jar_sha256}",
        "-Dmetallum.iris.validation.requireCodeIdentity=true",
        f"-Dmetallum.iris.validation.expectedArtifactJarSha256={args.expected_artifact_jar_sha256}",
        f"-Dmetallum.iris.validation.expectedNativeDylibSha256={args.expected_dylib_sha256}",
        f"-Dmetallum.iris.artifactJar={args.artifact_jar.resolve()}",
        f"-Dmetallum.iris.sourceCommit={args.source_commit}",
        "-cp", os.pathsep.join(map(str, classpath)), fabric["mainClass"],
        "--username", "IrisRuntimeQA", "--version", fabric_id,
        "--gameDir", str(game_dir), "--assetsDir", str(args.minecraft_root / "assets"),
        "--assetIndex", base["assetIndex"]["id"],
        "--uuid", "00000000000000000000000000000001", "--accessToken", "0",
        "--clientId", "0", "--xuid", "0", "--versionType", "release",
        "--width", str(args.width), "--height", str(args.height),
        "--quickPlaySingleplayer", "New World",
    ]
    return subprocess.call(java_args, cwd=game_dir, env=os.environ.copy())


if __name__ == "__main__":
    sys.exit(main())

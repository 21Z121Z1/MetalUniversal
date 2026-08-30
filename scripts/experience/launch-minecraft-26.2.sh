#!/usr/bin/env bash
set -euo pipefail

# Direct, reproducible launcher for the isolated Minecraft 26.2 experience
# profiles. It deliberately uses the installed official 26.2/Fabric runtime
# and the packaged MetalUniversal JAR; Gradle's development client is not an
# acceptance substitute.

usage() {
    printf '%s\n' "Usage: $0 <safe|metal4|visible|fused|framegen|iris|iris-modern> <instance-dir> [world-id]"
    exit 2
}

[[ $# -ge 2 && $# -le 3 ]] || usage
PROFILE="$1"
INSTANCE="$2"
WORLD_ID="${3:-New World}"

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
MINECRAFT_ROOT="${MINECRAFT_ROOT:-$HOME/Library/Application Support/minecraft}"
JAVA_BIN="${JAVA_BIN:-/opt/homebrew/opt/openjdk@25/bin/java}"
JAR_PATH="${METALLUM_JAR:-$REPO_ROOT/build/libs/metallum-1.0.3.jar}"
VANILLA_JSON="$MINECRAFT_ROOT/versions/26.2/26.2.json"
FABRIC_JSON="$MINECRAFT_ROOT/versions/fabric-loader-0.19.3-26.2/fabric-loader-0.19.3-26.2.json"
MINECRAFT_JAR="$MINECRAFT_ROOT/versions/26.2/26.2.jar"
FABRIC_LOADER_JAR="$MINECRAFT_ROOT/libraries/net/fabricmc/fabric-loader/0.19.3/fabric-loader-0.19.3.jar"

for required in "$JAVA_BIN" "$JAR_PATH" "$VANILLA_JSON" "$FABRIC_JSON" "$MINECRAFT_JAR" "$FABRIC_LOADER_JAR"; do
    [[ -f "$required" ]] || { printf 'Missing required file: %s\n' "$required" >&2; exit 1; }
done
[[ -d "$INSTANCE/mods" ]] || { printf 'Missing isolated instance mods directory: %s\n' "$INSTANCE/mods" >&2; exit 1; }

NATIVE_PATH="$(find "$MINECRAFT_ROOT/bin" -type d -path '*/lwjgl/3.4.1-snapshot/arm64' -exec stat -f '%m %N' {} \; | sort -nr | head -1 | cut -d' ' -f2-)"
[[ -d "$NATIVE_PATH" ]] || { printf 'Could not locate official LWJGL 3.4.1 arm64 natives\n' >&2; exit 1; }

# Minecraft's version manifest uses Maven coordinates for Fabric's libraries
# and explicit artifact paths for the vanilla libraries. Native classifier
# jars are intentionally not put on the Java classpath; their official
# extracted arm64 directory is supplied through java.library.path.
LIBRARY_PATHS="$(for version_json in "$VANILLA_JSON" "$FABRIC_JSON"; do
    jq -r '.libraries[] | select((.name | contains(":natives-")) | not) | (.downloads.artifact.path? // ((.name | split(":")) as $p | (($p[0] | gsub("\\."; "/")) + "/" + $p[1] + "/" + $p[2] + "/" + $p[1] + "-" + $p[2] + (if ($p|length) > 3 then "-" + $p[3] else "" end) + ".jar")))' "$version_json"
done | awk -v root="$MINECRAFT_ROOT/libraries" 'BEGIN{first=1} {p=root "/" $0; if ((getline x < p) >= 0) {close(p); if (first) {printf "%s",p; first=0} else printf ":%s",p}} END{printf "\n"}')"
CLASSPATH="$MINECRAFT_JAR:$FABRIC_LOADER_JAR:$LIBRARY_PATHS"

COMMON_FLAGS=(
    -Dmetallum.validation.forceMetal=true
    -Dmetallum.validation.sourceCommit="${METALLUM_SOURCE_SHA:-unknown}"
    -Dmetallum.hotpath.telemetry=true
    -Dmetallum.presentation.telemetry=true
    -Dmetallum.metalfx.debug=true
    -Dmetallum.opt.psoArchive=true
    -Dmetallum.opt.bindingTokens=true
    -Dmetallum.opt.compiledBindingPlan=true
    -Dmetallum.opt.deferredStore=true
    -Dmetallum.opt.deferredColorStore=true
    -Dmetallum.opt.blitBatch=true
    -Dmetallum.opt.encoderStateShadow=true
    -Dmetallum.opt.renderStatePacket=true
    -Dmetallum.opt.mslCache=true
    -Dmetallum.metalfx.mode=OFF
    -Dmetallum.metalfx.frameGeneration=false
    -Dmetallum.iris.semantic=false
    -Dmetallum.opt.metal4=false
    -Dmetallum.opt.metal4Compiler=false
    -Dmetallum.opt.metal4MainRenderer=false
    -Dmetallum.opt.metal4Present=false
    -Dmetallum.opt.metal4Barrier=false
    -Dmetallum.opt.residencySet=false
    -Dmetallum.opt.terrainSceneSnapshot=false
    -Dmetallum.opt.terrainIcb=false
    -Dmetallum.opt.terrainGpuEncode=false
    -Dmetallum.opt.terrainGpuVisibilityProbe=false
    -Dmetallum.opt.terrainVisibleGpuIcb=false
    -Dmetallum.opt.terrainFusedVisibleIcb=false
)
case "$PROFILE" in
    safe) ;;
    metal4)
        COMMON_FLAGS+=(
            -Dmetallum.opt.metal4=true
            -Dmetallum.opt.metal4Compiler=true
            -Dmetallum.opt.metal4MainRenderer=true
            -Dmetallum.opt.metal4Present=true
            -Dmetallum.opt.metal4Barrier=true
            -Dmetallum.opt.residencySet=true
        )
        ;;
    visible)
        COMMON_FLAGS+=(
            -Dmetallum.opt.metal4=true
            -Dmetallum.opt.metal4Compiler=true
            -Dmetallum.opt.metal4MainRenderer=true
            -Dmetallum.opt.metal4Present=true
            -Dmetallum.opt.metal4Barrier=true
            -Dmetallum.opt.residencySet=true
            -Dmetallum.opt.terrainSceneSnapshot=true
            -Dmetallum.opt.terrainIcb=true
            -Dmetallum.opt.terrainGpuEncode=true
            -Dmetallum.opt.terrainGpuVisibilityProbe=true
            -Dmetallum.opt.terrainVisibleGpuIcb=true
            -Dmetallum.opt.terrainDrawMetadata=true
        )
        ;;
    fused)
        COMMON_FLAGS+=(
            -Dmetallum.opt.metal4=true
            -Dmetallum.opt.metal4Compiler=true
            -Dmetallum.opt.metal4MainRenderer=true
            -Dmetallum.opt.metal4Present=true
            -Dmetallum.opt.metal4Barrier=true
            -Dmetallum.opt.residencySet=true
            -Dmetallum.opt.terrainSceneSnapshot=true
            -Dmetallum.opt.terrainIcb=true
            -Dmetallum.opt.terrainGpuEncode=true
            -Dmetallum.opt.terrainVisibleGpuIcb=true
            -Dmetallum.opt.terrainFusedVisibleIcb=true
            -Dmetallum.opt.terrainDrawMetadata=true
        )
        ;;
    framegen)
        COMMON_FLAGS+=(
            -Dmetallum.metalfx.mode=TEMPORAL
            -Dmetallum.metalfx.frameGeneration=true
        )
        ;;
    iris)
        COMMON_FLAGS+=(
            -Dmetallum.iris.semantic=true
            -Dmetallum.iris.strict=true
        )
        ;;
    iris-modern)
        COMMON_FLAGS+=(
            -Dmetallum.iris.semantic=true
            -Dmetallum.iris.strict=true
            -Dmetallum.iris.hazardGraph=true
            -Dmetallum.iris.passFusion=true
            -Dmetallum.iris.experimental.passFusion=true
            -Dmetallum.iris.computeGrouping=true
            -Dmetallum.iris.experimental.computeGrouping=true
            -Dmetallum.iris.attachmentLiveness=true
            -Dmetallum.iris.depthLiveness=true
            -Dmetallum.iris.finalColorFusion=true
            -Dmetallum.iris.experimental.finalColorFusion=true
            -Dmetallum.iris.argumentTables=true
            -Dmetallum.iris.experimental.argumentTables=true
            -Dmetallum.iris.indirectSubmission=true
            -Dmetallum.iris.experimental.icb=true
        )
        ;;
    *) usage ;;
esac

mkdir -p "$INSTANCE/natives/lwjgl" "$INSTANCE/natives/jna" "$INSTANCE/natives/netty"
printf '%s\n' "profile=$PROFILE" "instance=$INSTANCE" "worldId=$WORLD_ID" \
    "sourceSha=${METALLUM_SOURCE_SHA:-unknown}" "jar=$JAR_PATH" \
    "jarSha256=$(shasum -a 256 "$JAR_PATH" | awk '{print $1}')" \
    "minecraftJarSha256=$(shasum -a 256 "$MINECRAFT_JAR" | awk '{print $1}')" \
    "fabricLoaderJarSha256=$(shasum -a 256 "$FABRIC_LOADER_JAR" | awk '{print $1}')" \
    > "$INSTANCE/experience-launch.env"
printf '%s\n' "Launching Minecraft 26.2 profile=$PROFILE instance=$INSTANCE"

exec "$JAVA_BIN" -XstartOnFirstThread --enable-native-access=ALL-UNNAMED \
    -Xms2G -Xmx4G -XX:+UseZGC -XX:+UseCompactObjectHeaders -XX:+AlwaysPreTouch \
    "-Djava.library.path=$NATIVE_PATH" "-Dorg.lwjgl.librarypath=$NATIVE_PATH" \
    "-Dorg.lwjgl.system.SharedLibraryExtractPath=$INSTANCE/natives/lwjgl" \
    "-Djna.tmpdir=$INSTANCE/natives/jna" "-Dio.netty.native.workdir=$INSTANCE/natives/netty" \
    "${COMMON_FLAGS[@]}" -cp "$CLASSPATH" \
    net.fabricmc.loader.impl.launch.knot.KnotClient \
    --username MetalExperience --version fabric-loader-0.19.3-26.2 \
    --gameDir "$INSTANCE" --assetsDir "$MINECRAFT_ROOT/assets" --assetIndex 32 \
    --uuid 7a294d59-ecbe-4b47-b864-66c57a3dbf00 --accessToken 0 --versionType release \
    --width 1280 --height 720 --quickPlayPath logs/quickplay.json \
    --quickPlaySingleplayer "$WORLD_ID"

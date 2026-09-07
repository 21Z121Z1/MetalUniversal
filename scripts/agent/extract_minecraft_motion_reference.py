#!/usr/bin/env python3
"""Extract narrow, hash-anchored Minecraft motion-source evidence for agents.

The Minecraft reference tree is generated locally by minecraft-reference.sh and
must remain untracked. This script deliberately emits only small, line-numbered
source excerpts around motion-critical symbols plus whole-file SHA-256 hashes;
it never copies the decompiled source tree into the repository or artifact.
"""

from __future__ import annotations

import argparse
import hashlib
import json
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable


CONTEXT_LINES = 14
MAX_SNIPPETS_PER_FILE = 12
MAX_MATCHED_FILES_PER_QUERY = 10


@dataclass(frozen=True)
class Query:
    name: str
    file_names: tuple[str, ...]
    needles: tuple[str, ...]
    discovery_needles: tuple[str, ...] = ()


QUERIES = (
    Query(
        "game_renderer",
        ("GameRenderer.java",),
        (
            "renderLevel(",
            "renderItemInHand(",
            "submitHandsWithItems(",
            "ProjectionMatrixBuffer",
            "gameRenderState",
            "clearDepthTexture",
        ),
    ),
    Query(
        "camera_render_state",
        ("CameraRenderState.java",),
        ("class CameraRenderState", "viewRotationMatrix", "pos"),
    ),
    Query(
        "entity_dispatch",
        ("EntityRenderDispatcher.java",),
        ("extractEntity(", "submit(", "CameraRenderState", "PoseStack"),
    ),
    Query(
        "moving_block",
        (
            "MovingBlockFeatureRenderer.java",
            "MovingBlockRenderState.java",
            "FallingBlockRenderer.java",
            "FallingBlockRenderState.java",
        ),
        (
            "buildGroup(",
            "submitMovingBlock(",
            "MovingBlockRenderState",
            "pose",
            "tesselateBlock",
        ),
        ("MovingBlockRenderState", "submitMovingBlock("),
    ),
    Query(
        "piston_motion",
        (
            "PistonHeadRenderer.java",
            "PistonHeadRenderState.java",
            "PistonMovingBlockEntity.java",
            "PistonBaseBlock.java",
        ),
        (
            "extractRenderState(",
            "submit(",
            "xOffset",
            "yOffset",
            "zOffset",
            "getXOff(",
            "getYOff(",
            "getZOff(",
            "getProgress(",
            "progress",
            "progressO",
            "isExtending",
            "getMovementDirection(",
            "getMovedState(",
            "getBlockPos(",
            "translate(",
        ),
        ("class PistonMovingBlockEntity", "getXOff(", "PistonHeadRenderState"),
    ),
    Query(
        "display_entity",
        (
            "DisplayRenderer.java",
            "DisplayEntityRenderer.java",
            "DisplayEntityRenderState.java",
            "Display.java",
        ),
        (
            "DisplayEntityRenderState",
            "interpol",
            "transformation",
            "billboardConstraints",
            "transformXRot",
            "transformYRot",
            "pose",
            "submit(",
        ),
        ("DisplayEntityRenderState",),
    ),
    Query(
        "display_features",
        (
            "BlockDisplayRenderer.java",
            "ItemDisplayRenderer.java",
            "TextDisplayRenderer.java",
            "BlockDisplayEntityRenderState.java",
            "ItemDisplayEntityRenderState.java",
            "TextDisplayEntityRenderState.java",
        ),
        (
            "submit(",
            "submitModel(",
            "submitItem(",
            "submitText(",
            "SubmitNodeCollector",
            "PoseStack",
            "renderState",
            "blockState",
            "item",
            "text",
        ),
        ("extends DisplayRenderer", "DisplayEntityRenderState"),
    ),
    Query(
        "transformation_math",
        ("Transformation.java",),
        ("class Transformation", "getMatrix", "translation", "leftRotation", "scale", "rightRotation"),
        ("class Transformation",),
    ),
    Query(
        "first_person",
        (
            "GameRenderer.java",
            "ItemInHandRenderer.java",
            "ItemInHandRenderState.java",
            "HandRenderState.java",
        ),
        (
            "renderItemInHand(",
            "submitHandsWithItems(",
            "renderHandsWithItems(",
            "submitArmWithItem(",
            "renderArm",
            "swing",
            "equip",
            "PoseStack",
            "applyItemArmTransform",
            "applyItemArmAttackTransform",
        ),
        ("submitHandsWithItems(", "renderHandsWithItems(", "submitArmWithItem("),
    ),
    Query(
        "living_model",
        (
            "LivingEntityRenderer.java",
            "LivingEntityRenderState.java",
            "EntityModel.java",
            "ModelPart.java",
            "HumanoidModel.java",
        ),
        (
            "submit(",
            "setupRotations(",
            "setupAnim",
            "resetPose",
            "LivingEntityRenderState",
            "bodyRot",
            "PoseStack",
            "translateAndRotate(",
            "visit(",
            "getAllParts(",
        ),
        ("setupAnim", "translateAndRotate(", "LivingEntityRenderState"),
    ),
    Query(
        "boat_animation",
        (
            "AbstractBoatRenderer.java",
            "BoatRenderer.java",
            "BoatRenderState.java",
            "BoatModel.java",
        ),
        (
            "submit(",
            "setupAnim",
            "paddle",
            "rowingTime",
            "hurtTime",
            "damageTime",
            "bubbleAngle",
            "PoseStack",
        ),
        ("BoatRenderState", "rowingTime", "paddle"),
    ),
    Query(
        "particles",
        (
            "SubmitNodeStorage.java",
            "SubmitNodeCollection.java",
            "QuadParticleRenderState.java",
            "ParticleEngine.java",
            "Particle.java",
            "SingleQuadParticle.java",
        ),
        (
            "submitQuadParticleGroup(",
            "submitParticleGroup(",
            "QuadParticleRenderState",
            "render(",
            "extract(",
            "getRenderType",
            "xOld",
            "yOld",
            "zOld",
            "xo",
            "yo",
            "zo",
            "partial",
            "lerp",
            "rotation",
            "scale",
        ),
        ("submitQuadParticleGroup(", "xOld", "yOld", "zOld"),
    ),
)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def relative(path: Path, root: Path) -> str:
    return path.relative_to(root).as_posix()


def java_files(root: Path) -> list[Path]:
    return sorted(root.rglob("*.java"))


def named_candidates(files: Iterable[Path], names: tuple[str, ...]) -> list[Path]:
    wanted = set(names)
    return [path for path in files if path.name in wanted]


def discover_candidates(
    files: Iterable[Path], needles: tuple[str, ...], already: set[Path]
) -> list[Path]:
    if not needles:
        return []
    found: list[Path] = []
    for path in files:
        if path in already:
            continue
        try:
            text = path.read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue
        if any(needle in text for needle in needles):
            found.append(path)
            if len(found) >= MAX_MATCHED_FILES_PER_QUERY:
                break
    return found


def snippets(path: Path, needles: tuple[str, ...]) -> list[dict[str, object]]:
    lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
    hits: list[int] = []
    for index, line in enumerate(lines):
        if any(needle in line for needle in needles):
            hits.append(index)

    windows: list[tuple[int, int]] = []
    for hit in hits:
        start = max(0, hit - CONTEXT_LINES)
        end = min(len(lines), hit + CONTEXT_LINES + 1)
        if windows and start <= windows[-1][1] + 1:
            windows[-1] = (windows[-1][0], max(windows[-1][1], end))
        else:
            windows.append((start, end))
        if len(windows) >= MAX_SNIPPETS_PER_FILE:
            break

    result: list[dict[str, object]] = []
    for start, end in windows:
        result.append(
            {
                "start_line": start + 1,
                "end_line": end,
                "text": "\n".join(
                    f"{line_number:05d}: {lines[line_number - 1]}"
                    for line_number in range(start + 1, end + 1)
                ),
            }
        )
    return result


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source-root", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()

    root = args.source_root.resolve()
    if not (root / "net/minecraft/client/Minecraft.java").is_file():
        raise SystemExit(f"not a Minecraft reference source tree: {root}")

    files = java_files(root)
    payload: dict[str, object] = {
        "schema": 1,
        "source_root_basename": root.name,
        "java_file_count": len(files),
        "queries": {},
    }
    query_payload: dict[str, object] = {}

    for query in QUERIES:
        selected = named_candidates(files, query.file_names)
        selected_set = set(selected)
        selected.extend(discover_candidates(files, query.discovery_needles, selected_set))
        selected = selected[:MAX_MATCHED_FILES_PER_QUERY]

        entries: list[dict[str, object]] = []
        for path in selected:
            excerpt = snippets(path, query.needles)
            entries.append(
                {
                    "path": relative(path, root),
                    "sha256": sha256(path),
                    "line_count": sum(1 for _ in path.open("r", encoding="utf-8", errors="replace")),
                    "snippets": excerpt,
                }
            )
        query_payload[query.name] = entries

    payload["queries"] = query_payload
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(payload, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")

    required = (
        "game_renderer",
        "camera_render_state",
        "entity_dispatch",
        "moving_block",
        "piston_motion",
        "display_entity",
        "first_person",
        "living_model",
        "particles",
    )
    missing = [name for name in required if not query_payload.get(name)]
    if missing:
        raise SystemExit(f"required motion-reference queries produced no files: {', '.join(missing)}")

    print(f"Wrote Minecraft motion reference evidence: {args.output}")
    for name, entries in query_payload.items():
        print(f"  {name}: {len(entries)} file(s)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

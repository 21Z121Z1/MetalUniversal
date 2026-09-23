#!/usr/bin/env python3
"""Fail-closed source/API contract check for the Minecraft 26.3 terrain path.

This is a read-only companion to scripts/minecraft-reference.sh. It checks the
specific vanilla declarations targeted by the registered terrain mixins and a
pair of LevelRenderer call-site anchors reported by the 26.3 source probe. It
does not attempt to infer runtime behavior from decompiled source.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable


EXPECTED_VERSION = "26.3"
EXPECTED_VINEFLOWER_VERSION = "1.12.0"
EXPECTED_VINEFLOWER_SHA256 = (
    "1dfcfe974395734fa467ce620661c7623d05ba83670de0529b1fbd63ff548b9d"
)


@dataclass(frozen=True)
class MethodContract:
    name: str
    parameters: tuple[str, ...]
    returns: str | None = None
    exact_parameters: bool = True


@dataclass(frozen=True)
class SourceContract:
    path: str
    class_chain: tuple[str, ...]
    methods: tuple[MethodContract, ...]


SOURCE_CONTRACTS = (
    SourceContract(
        "net/minecraft/client/renderer/LevelRenderer.java",
        ("LevelRenderer",),
        (
            MethodContract("compileSections", (), exact_parameters=False),
            MethodContract("prepareChunkRendersIndirect", (), exact_parameters=False),
        ),
    ),
    SourceContract(
        "net/minecraft/client/renderer/LevelExtractor.java",
        ("LevelExtractor",),
        (
            MethodContract("setSectionDirty", ("int", "int", "int", "boolean"), "void"),
            MethodContract("setLevel", ("ClientLevel",), "void"),
            MethodContract("allChanged", (), "void"),
        ),
    ),
    SourceContract(
        "net/minecraft/client/renderer/chunk/SectionRenderDispatcher.java",
        ("SectionRenderDispatcher", "RenderSection", "CompileTask"),
        (
            MethodContract("doTask", ("SectionBufferBuilderPack",), "SectionTaskResult"),
        ),
    ),
    SourceContract(
        "net/minecraft/client/renderer/chunk/SectionRenderDispatcher.java",
        ("SectionRenderDispatcher", "RenderSection"),
        (
            MethodContract("createCompileTask", ("RenderSectionRegion",), "SectionTask"),
            MethodContract("compileAsync", ("RenderSectionRegion",), "void"),
            MethodContract("compileSync", ("RenderSectionRegion",), "void"),
            MethodContract("reset", (), "void"),
            MethodContract(
                "addSectionBuffersToUberBuffer",
                ("ChunkSectionLayer", "CompiledSectionMesh", "ByteBuffer", "ByteBuffer"),
                "void",
            ),
            MethodContract("vertexBufferUploadCallback", ("CompiledSectionMesh", "ChunkSectionLayer"), "void"),
            MethodContract(
                "indexBufferUploadCallback",
                ("CompiledSectionMesh", "ChunkSectionLayer", "boolean"),
                "void",
            ),
            MethodContract("setSectionMesh", ("SectionMesh",), "SectionMesh"),
            MethodContract("releaseSectionMesh", ("SectionMesh",), "void"),
        ),
    ),
    SourceContract(
        "net/minecraft/client/renderer/chunk/SectionCompiler.java",
        ("SectionCompiler",),
        (
            MethodContract("compile", ("RenderSectionRegion",), exact_parameters=False),
        ),
    ),
    SourceContract(
        "net/minecraft/client/renderer/chunk/ChunkSectionsToRender.java",
        ("ChunkSectionsToRender", "DrawIndirect"),
        (MethodContract("render", ("ChunkSectionLayer",), exact_parameters=False),),
    ),
    SourceContract(
        "net/minecraft/client/renderer/chunk/ChunkSectionsToRender.java",
        ("ChunkSectionsToRender", "DrawSeparate"),
        (MethodContract("render", ("ChunkSectionLayer",), exact_parameters=False),),
    ),
)


# These selectors are part of the fixed oracle. If a project selector moves,
# update the contract only after reviewing the new 26.3 source target.
MIXIN_CONTRACTS = {
    "terrain/LevelExtractorTerrainGenerationMixin.java": (
        '@Mixin(LevelExtractor.class)',
        '@Inject(method="setSectionDirty(IIIZ)V",at=@At("HEAD"))',
        '@Inject(method="setLevel",at=@At("HEAD"))',
        '@Inject(method="allChanged",at=@At("HEAD"))',
    ),
    "terrain/CompileTaskTerrainGenerationMixin.java": (
        '@Mixin(targets="net.minecraft.client.renderer.chunk.SectionRenderDispatcher$RenderSection$CompileTask")',
        '@WrapMethod(method="doTask")',
    ),
    "terrain/RenderSectionTerrainGenerationMixin.java": (
        '@Mixin(SectionRenderDispatcher.RenderSection.class)',
        '@Inject(method="createCompileTask",at=@At("RETURN"))',
        '@Inject(method="reset",at=@At("HEAD"))',
        '@Inject(method="addSectionBuffersToUberBuffer",at=@At("HEAD"))',
        '@WrapMethod(method="setSectionMesh")',
        '@Inject(method="releaseSectionMesh",at=@At("HEAD"))',
    ),
    "terrain/SectionCompilerTerrainWorkMixin.java": (
        '@Mixin(SectionCompiler.class)',
        '@ModifyVariable(method="compile",at=@At("HEAD"),argsOnly=true)',
    ),
    "terrain/RenderSectionTerrainWorkMixin.java": (
        '@Mixin(SectionRenderDispatcher.RenderSection.class)',
        '@Inject(method="compileAsync",at=@At("HEAD"))',
        '@Inject(method="compileSync",at=@At("HEAD"))',
        '@Inject(method="vertexBufferUploadCallback",at=@At("HEAD"))',
        '@Inject(method="indexBufferUploadCallback",at=@At("HEAD"))',
        '@Inject(method="setSectionMesh",at=@At("RETURN"))',
        '@Inject(method="releaseSectionMesh",at=@At("HEAD"))',
    ),
    "terrain/ChunkSectionsDrawTerrainWorkMixin.java": (
        '@Mixin({ChunkSectionsToRender.DrawIndirect.class,ChunkSectionsToRender.DrawSeparate.class})',
        '@ModifyVariable(method="render",at=@At("HEAD"),argsOnly=true)',
    ),
}


def strip_java_comments(text: str) -> str:
    """Remove Java comments while retaining string literals and line layout."""
    out = list(text)
    i = 0
    state = "code"
    while i < len(text):
        if state == "code":
            if text.startswith("//", i):
                out[i] = out[i + 1] = " "
                i += 2
                state = "line-comment"
                continue
            if text.startswith("/*", i):
                out[i] = out[i + 1] = " "
                i += 2
                state = "block-comment"
                continue
            if text.startswith('"""', i):
                i += 3
                state = "text-block"
                continue
            if text[i] == '"':
                i += 1
                state = "string"
                continue
            if text[i] == "'":
                i += 1
                state = "character"
                continue
            i += 1
            continue
        if state == "line-comment":
            if text[i] == "\n":
                state = "code"
            else:
                out[i] = " "
            i += 1
            continue
        if state == "block-comment":
            if text.startswith("*/", i):
                out[i] = out[i + 1] = " "
                i += 2
                state = "code"
            else:
                if text[i] != "\n":
                    out[i] = " "
                i += 1
            continue
        if state == "text-block":
            if text.startswith('"""', i):
                i += 3
                state = "code"
            else:
                i += 1
            continue
        if state in ("string", "character"):
            quote = '"' if state == "string" else "'"
            if text[i] == "\\":
                i += 2
            elif text[i] == quote:
                i += 1
                state = "code"
            else:
                i += 1
    return "".join(out)


def mask_java_literals(text: str) -> str:
    """Blank string/character literals so braces inside them cannot affect parsing."""
    out = list(text)
    i = 0
    state = "code"
    while i < len(text):
        if state == "code":
            if text.startswith('"""', i):
                out[i:i + 3] = "   "
                i += 3
                state = "text-block"
            elif text[i] == '"':
                out[i] = " "
                i += 1
                state = "string"
            elif text[i] == "'":
                out[i] = " "
                i += 1
                state = "character"
            else:
                i += 1
            continue
        if state == "text-block":
            if text.startswith('"""', i):
                out[i:i + 3] = "   "
                i += 3
                state = "code"
            else:
                if text[i] != "\n":
                    out[i] = " "
                i += 1
            continue
        if state in ("string", "character"):
            quote = '"' if state == "string" else "'"
            if text[i] == "\\":
                if text[i] != "\n":
                    out[i] = " "
                if i + 1 < len(text) and text[i + 1] != "\n":
                    out[i + 1] = " "
                i += 2
            elif text[i] == quote:
                out[i] = " "
                i += 1
                state = "code"
            else:
                if text[i] != "\n":
                    out[i] = " "
                i += 1
    return "".join(out)


def matching_delimiter(text: str, start: int, opening: str, closing: str) -> int | None:
    depth = 0
    for index in range(start, len(text)):
        if text[index] == opening:
            depth += 1
        elif text[index] == closing:
            depth -= 1
            if depth == 0:
                return index
    return None


def brace_depth_map(text: str) -> list[int]:
    depths = [0] * (len(text) + 1)
    depth = 0
    for index, char in enumerate(text):
        depths[index] = depth
        if char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
    depths[len(text)] = depth
    return depths


def find_class_span(text: str, class_chain: tuple[str, ...]) -> tuple[int, int] | None:
    parent_span: tuple[int, int] | None = None
    declaration = re.compile(r"\b(?:class|interface|enum|record)\s+([A-Za-z_$][\w$]*)")
    depths = brace_depth_map(text)
    for level, expected_name in enumerate(class_chain):
        lower, upper = parent_span or (0, len(text))
        candidates = []
        for match in declaration.finditer(text, lower, upper):
            if match.group(1) != expected_name or depths[match.start()] != level:
                continue
            brace = text.find("{", match.end(), upper)
            if brace < 0:
                continue
            end = matching_delimiter(text, brace, "{", "}")
            if end is not None:
                candidates.append((brace, end))
        if len(candidates) != 1:
            return None
        parent_span = candidates[0]
    return parent_span


def split_top_level(text: str, separator: str = ",") -> list[str]:
    items: list[str] = []
    start = 0
    angle = paren = bracket = brace = 0
    for index, char in enumerate(text):
        if char == "<":
            angle += 1
        elif char == ">" and angle:
            angle -= 1
        elif char == "(":
            paren += 1
        elif char == ")":
            paren -= 1
        elif char == "[":
            bracket += 1
        elif char == "]":
            bracket -= 1
        elif char == "{":
            brace += 1
        elif char == "}":
            brace -= 1
        elif char == separator and angle == paren == bracket == brace == 0:
            items.append(text[start:index].strip())
            start = index + 1
    tail = text[start:].strip()
    if tail:
        items.append(tail)
    return items


def normalize_type(raw: str) -> str:
    value = re.sub(r"@\w+(?:\([^)]*\))?", "", raw).strip()
    value = re.sub(r"\b(final|volatile|transient)\b", "", value)
    value = re.sub(r"\s+", "", value)
    value = re.sub(r"<.*>", "", value)
    value = value.replace("...", "[]")
    return value.rsplit(".", 1)[-1]


def normalize_parameter(raw: str) -> str:
    value = re.sub(r"@\w+(?:\([^)]*\))?", "", raw).strip()
    value = re.sub(r"\b(final|volatile|transient)\b", "", value).strip()
    tokens = value.split()
    if len(tokens) >= 2:
        value = " ".join(tokens[:-1])
    return normalize_type(value)


def parse_methods(class_text: str, class_span: tuple[int, int]) -> dict[str, list[tuple[tuple[str, ...], str]]]:
    methods: dict[str, list[tuple[tuple[str, ...], str]]] = {}
    clean = mask_java_literals(strip_java_comments(class_text))
    class_open, class_close = class_span
    depths = brace_depth_map(clean)
    class_depth = depths[class_open]
    for name_match in re.finditer(r"\b([A-Za-z_$][\w$]*)\s*\(", clean[class_open + 1:class_close]):
        name_start = class_open + 1 + name_match.start(1)
        name = name_match.group(1)
        if depths[name_start] != class_depth + 1:
            continue
        line_start = clean.rfind("\n", class_open + 1, name_start) + 1
        prefix = clean[line_start:name_start].strip()
        if not prefix or "=" in prefix or "." in prefix:
            continue
        words = prefix.split()
        return_word = words[-1]
        if return_word in {"return", "throw", "new", "if", "while", "switch", "catch"}:
            continue
        opening = clean.find("(", name_start)
        closing = matching_delimiter(clean, opening, "(", ")")
        if closing is None:
            continue
        raw_parameters = clean[opening + 1:closing].strip()
        parameters = tuple(normalize_parameter(part) for part in split_top_level(raw_parameters))
        methods.setdefault(name, []).append((parameters, normalize_type(return_word)))
    return methods


class Verifier:
    def __init__(self, sources: Path, repo_root: Path):
        self.sources = sources
        self.repo_root = repo_root
        self.checks: list[dict[str, str]] = []
        self.errors: list[str] = []

    def check(self, check_id: str, detail: str, passed: bool) -> None:
        state = "pass" if passed else "fail"
        self.checks.append({"id": check_id, "result": state, "detail": detail})
        if not passed:
            self.errors.append(f"{check_id}: {detail}")

    def validate_reference_identity(self) -> None:
        info_path = self.sources.parent / "REFERENCE_INFO.txt"
        fields: dict[str, str] = {}
        if info_path.is_file():
            for line in info_path.read_text(encoding="utf-8", errors="replace").splitlines():
                key, separator, value = line.partition("=")
                if separator:
                    fields[key.strip()] = value.strip()
        required = {
            "minecraft_version": EXPECTED_VERSION,
            "vineflower_version": EXPECTED_VINEFLOWER_VERSION,
            "vineflower_sha256": EXPECTED_VINEFLOWER_SHA256,
        }
        missing = [key for key in required if fields.get(key) != required[key]]
        sources_exist = self.sources.is_dir()
        java_count = sum(1 for _ in self.sources.rglob("*.java")) if sources_exist else 0
        passed = not missing and sources_exist and java_count >= 500
        detail = (
            f"reference metadata/version and decompiler pin verified; java_files={java_count}"
            if passed
            else f"missing or mismatched metadata {missing}; source_dir_exists={sources_exist}; java_files={java_count}"
        )
        self.check("reference-identity", detail, passed)

    def validate_source_contracts(self) -> None:
        for contract in SOURCE_CONTRACTS:
            source_path = self.sources / contract.path
            if not source_path.is_file():
                self.check(
                    f"source:{contract.path}",
                    f"required vanilla 26.3 source file missing: {contract.path}",
                    False,
                )
                continue
            text = mask_java_literals(
                strip_java_comments(source_path.read_text(encoding="utf-8", errors="replace"))
            )
            span = find_class_span(text, contract.class_chain)
            contract_name = ".".join(contract.class_chain)
            if span is None:
                self.check(
                    f"class:{contract_name}",
                    f"expected class target not found or ambiguous in {contract.path}",
                    False,
                )
                continue
            methods = parse_methods(text, span)
            self.check(f"class:{contract_name}", f"found in {contract.path}", True)
            for method_contract in contract.methods:
                observed = methods.get(method_contract.name, [])
                expected_parameters = tuple(normalize_type(p) for p in method_contract.parameters)
                matches = []
                for parameters, result in observed:
                    if method_contract.exact_parameters:
                        parameters_match = parameters == expected_parameters
                    else:
                        parameters_match = all(required in parameters for required in expected_parameters)
                    return_match = (
                        method_contract.returns is None
                        or result == normalize_type(method_contract.returns)
                    )
                    if parameters_match and return_match:
                        matches.append((parameters, result))
                expected = f"{method_contract.name}({', '.join(expected_parameters)})"
                if method_contract.returns:
                    expected += f" -> {normalize_type(method_contract.returns)}"
                if matches:
                    detail = f"{contract_name}.{expected} matched"
                    self.check(f"method:{contract_name}.{method_contract.name}", detail, True)
                else:
                    observed_text = ", ".join(
                        f"{method_contract.name}({', '.join(parameters)}) -> {result}"
                        for parameters, result in observed
                    ) or "no declaration found"
                    qualifier = "exactly " if method_contract.exact_parameters else "including "
                    detail = (
                        f"expected {qualifier}{expected}; observed {observed_text} in {contract.path}"
                    )
                    self.check(f"method:{contract_name}.{method_contract.name}", detail, False)

    def validate_project_targets(self) -> None:
        mixins_path = self.repo_root / "src/main/resources/metallum.mixins.json"
        try:
            mixins = json.loads(mixins_path.read_text(encoding="utf-8"))
            registered = set(mixins.get("mixins", []))
            registered.update(mixins.get("client", []))
            registered.update(mixins.get("server", []))
        except (OSError, json.JSONDecodeError) as error:
            self.check("project-mixins-json", f"cannot read mixin config: {error}", False)
            return

        for relative_path, selectors in MIXIN_CONTRACTS.items():
            path = self.repo_root / "src/main/java/com/metallum/mixin" / relative_path
            if not path.is_file():
                self.check(f"mixin:{relative_path}", "required terrain mixin source missing", False)
                continue
            source = strip_java_comments(path.read_text(encoding="utf-8", errors="replace"))
            compact = re.sub(r"\s+", "", source)
            source_name = path.stem
            registered_name = f"terrain.{source_name}"
            registered_ok = registered_name in registered
            missing = [selector for selector in selectors if re.sub(r"\s+", "", selector) not in compact]
            passed = registered_ok and not missing
            detail = (
                "registered and target selectors match fixed 26.3 contract"
                if passed
                else f"registered={registered_ok}; missing selectors={missing}"
            )
            self.check(f"mixin:{relative_path}", detail, passed)

    def report(self) -> dict[str, object]:
        return {
            "checker": "verify_minecraft_terrain_reference",
            "minecraft_version": EXPECTED_VERSION,
            "source_root": str(self.sources),
            "result": "pass" if not self.errors else "fail",
            "checks": self.checks,
            "errors": self.errors,
        }


def run_self_test() -> None:
    sample = """
        class SectionRenderDispatcher {
          class RenderSection {
            class CompileTask {
              SectionTaskResult doTask(SectionBufferBuilderPack pack) { return null; }
            }
          }
        }
        class LevelExtractor {
          void setSectionDirty(int x, int y, int z, boolean important) { }
        }
    """
    clean = mask_java_literals(strip_java_comments(sample))
    nested = find_class_span(clean, ("SectionRenderDispatcher", "RenderSection", "CompileTask"))
    top_level = find_class_span(clean, ("LevelExtractor",))
    if nested is None or top_level is None:
        raise AssertionError("self-test could not locate synthetic top-level/nested targets")
    nested_methods = parse_methods(clean, nested)
    top_methods = parse_methods(clean, top_level)
    expected_do_task = (("SectionBufferBuilderPack",), "SectionTaskResult")
    if expected_do_task not in nested_methods.get("doTask", []):
        raise AssertionError(f"self-test rejected valid nested signature: {nested_methods}")
    valid_dirty = (("int", "int", "int", "boolean"), "void")
    if valid_dirty not in top_methods.get("setSectionDirty", []):
        raise AssertionError(f"self-test rejected valid selector signature: {top_methods}")

    drifted = sample.replace("boolean important", "long important")
    drifted_clean = mask_java_literals(strip_java_comments(drifted))
    drifted_span = find_class_span(drifted_clean, ("LevelExtractor",))
    assert drifted_span is not None
    drifted_methods = parse_methods(drifted_clean, drifted_span)
    if valid_dirty in drifted_methods.get("setSectionDirty", []):
        raise AssertionError("self-test failed to detect parameter-type drift")

    missing_target = find_class_span(clean, ("SectionRenderDispatcher", "RenderSection", "MissingTask"))
    if missing_target is not None:
        raise AssertionError("self-test failed to detect target-class drift")


def main(argv: Iterable[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--sources",
        type=Path,
        help="decompiled source root (default: <repo>/.minecraft-reference/26.3/sources)",
    )
    parser.add_argument(
        "--self-test",
        action="store_true",
        help="exercise signature, nested-target, and fail-closed drift checks without a source tree",
    )
    args = parser.parse_args(argv)
    repo_root = Path(__file__).resolve().parents[2]
    if args.self_test:
        try:
            run_self_test()
            print(json.dumps({"self_test": "pass", "result": "pass"}, indent=2))
            return 0
        except AssertionError as error:
            print(json.dumps({"self_test": "fail", "result": "fail", "error": str(error)}, indent=2))
            return 1

    sources = args.sources or (repo_root / ".minecraft-reference" / EXPECTED_VERSION / "sources")
    sources = sources.expanduser().resolve()
    verifier = Verifier(sources, repo_root)
    verifier.validate_reference_identity()
    verifier.validate_source_contracts()
    verifier.validate_project_targets()
    print(json.dumps(verifier.report(), indent=2))
    return 0 if not verifier.errors else 1


if __name__ == "__main__":
    sys.exit(main())

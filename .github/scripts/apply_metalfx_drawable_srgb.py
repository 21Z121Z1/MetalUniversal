from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected exactly one match, found {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


native = ROOT / "src/main/native/MetallumNative.swift"
text = native.read_text(encoding="utf-8")
if "import CoreGraphics\n" not in text:
    replace_once(native, "import QuartzCore\n", "import QuartzCore\nimport CoreGraphics\n")
replace_once(
    native,
    """public func metallum_configure_layer(_ layer: CAMetalLayer, _ width: Double, _ height: Double, _ immediatePresentMode: Int32) {\n    layer.pixelFormat = .bgra8Unorm\n    layer.drawableSize = CGSize(width: width, height: height)\n""",
    """public func metallum_configure_layer(_ layer: CAMetalLayer, _ width: Double, _ height: Double, _ immediatePresentMode: Int32) {\n    // The present shader writes display-referred sRGB code values into a plain UNORM drawable.\n    // Tag those values for Core Animation color matching without selecting an _srgb attachment,\n    // which would apply an additional linear-to-sRGB conversion on render writes.\n    layer.pixelFormat = .bgra8Unorm\n    layer.colorspace = CGColorSpace(name: CGColorSpace.sRGB)\n    layer.drawableSize = CGSize(width: width, height: height)\n""",
)

contract = ROOT / "src/main/java/com/metallum/client/metal/render/FrameGenerationColorContract.java"
replace_once(
    contract,
    """        UiAlphaEncoding.UNPROVEN,\n                DrawableEncoding.UNTAGGED_BGRA8_UNORM\n        );\n""",
    """        UiAlphaEncoding.UNPROVEN,\n                DrawableEncoding.EXPLICIT_SRGB\n        );\n""",
)
replace_once(
    contract,
    """     * has not yet been proven to preserve a premultiplied-alpha invariant. Finally, the\n     * CAMetalLayer uses BGRA8Unorm without an explicit sRGB color-space tag. Those unknowns are\n     * deliberately represented rather than inferred from storage formats.</p>\n""",
    """     * has not yet been proven to preserve a premultiplied-alpha invariant. The CAMetalLayer\n     * keeps BGRA8Unorm storage but explicitly tags its display-referred contents as sRGB, avoiding\n     * an _srgb render-target conversion while giving Core Animation a concrete display color space.\n     * The remaining unknowns are deliberately represented rather than inferred from storage formats.</p>\n""",
)

test = ROOT / "src/test/java/com/metallum/client/metal/render/FrameGenerationColorContractTest.java"
replace_once(
    test,
    """                \"frame-interpolation-transfer\",\n                \"ui-premultiplied-alpha\",\n                \"drawable-srgb-colorspace\"\n        )));\n""",
    """                \"frame-interpolation-transfer\",\n                \"ui-premultiplied-alpha\"\n        )));\n        assertEquals(\n                FrameGenerationColorContract.DrawableEncoding.EXPLICIT_SRGB,\n                evidence.drawableEncoding()\n        );\n""",
)

native_test = ROOT / "src/test/java/com/metallum/client/metal/render/MetalFrameGenerationNativeSourceContractTest.java"
text = native_test.read_text(encoding="utf-8")
anchor = """    @Test\n    void frameGenerationDocumentIsNotBuildScriptPayload() throws Exception {\n"""
if anchor not in text:
    raise SystemExit(f"{native_test}: insertion anchor missing")
method = """    @Test\n    void drawableLayerTagsSrgbContentWithoutSrgbAttachmentEncoding() throws Exception {\n        String nativeSource = Files.readString(Path.of(\"src/main/native/MetallumNative.swift\"));\n        int start = nativeSource.indexOf(\"@_cdecl(\\\"metallum_configure_layer\\\")\");\n        int end = nativeSource.indexOf(\"\\n@_cdecl(\", start + 1);\n        assertTrue(start >= 0 && end > start);\n\n        String block = nativeSource.substring(start, end);\n        assertTrue(block.contains(\"layer.pixelFormat = .bgra8Unorm\"));\n        assertTrue(block.contains(\"layer.colorspace = CGColorSpace(name: CGColorSpace.sRGB)\"));\n        assertFalse(block.contains(\"layer.pixelFormat = .bgra8Unorm_srgb\"));\n    }\n\n"""
if method not in text:
    native_test.write_text(text.replace(anchor, method + anchor, 1), encoding="utf-8")

print("Applied explicit CAMetalLayer sRGB content tagging and fail-closed contract update")

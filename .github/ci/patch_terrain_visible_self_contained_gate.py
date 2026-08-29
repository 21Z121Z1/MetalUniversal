from pathlib import Path

root = Path(__file__).resolve().parents[2]
path = root / 'src/main/java/com/metallum/client/metal/render/MetalRenderPass.java'
s = path.read_text()
s = s.replace(
    'if (!TerrainSceneSnapshot.ICB_ENABLED && !TerrainSceneSnapshot.GPU_ICB_ENABLED) {',
    'if (!TerrainSceneSnapshot.ICB_ENABLED\n                && !TerrainSceneSnapshot.GPU_ICB_ENABLED\n                && !TerrainSceneSnapshot.VISIBLE_GPU_ICB_ENABLED) {',
    1
)
s = s.replace(
    'if (TerrainSceneSnapshot.ICB_ENABLED || TerrainSceneSnapshot.GPU_ICB_ENABLED) {\n                if (terrainSnapshotSubmitted(primitiveType, commands, drawCount)) {',
    'if (TerrainSceneSnapshot.ICB_ENABLED\n                    || TerrainSceneSnapshot.GPU_ICB_ENABLED\n                    || TerrainSceneSnapshot.VISIBLE_GPU_ICB_ENABLED) {\n                if (terrainSnapshotSubmitted(primitiveType, commands, drawCount)) {',
    1
)
path.write_text(s)

test = root / 'src/test/java/com/metallum/client/metal/render/Metal4TerrainVisibleIcbAuthorityContractTest.java'
s = test.read_text()
needle = 'assertTrue(renderPass.contains("TerrainCandidateSnapshot.VISIBLE_GPU_ICB_ENABLED"));'
if needle not in s:
    # Append a focused source-level test method before the final class brace.
    insert = r'''

    @Test
    void visibleIcbPropertyAloneRequestsSubmission() throws Exception {
        String renderPass = Files.readString(Path.of(
                "src/main/java/com/metallum/client/metal/render/MetalRenderPass.java"
        ));
        assertTrue(renderPass.contains(
                "&& !TerrainSceneSnapshot.VISIBLE_GPU_ICB_ENABLED)"
        ));
        assertTrue(renderPass.contains(
                "|| TerrainSceneSnapshot.VISIBLE_GPU_ICB_ENABLED)"
        ));
    }
'''
    pos = s.rfind('}')
    s = s[:pos] + insert + s[pos:]
    test.write_text(s)

print('visible self-contained gate patch applied')

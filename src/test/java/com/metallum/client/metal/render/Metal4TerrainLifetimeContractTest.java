package com.metallum.client.metal.render;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Source-level ABI and ownership contracts for the default-off terrain GPU
 * ICB lanes. The native smoke fixture supplies the device execution proof;
 * these checks keep Java/FFM/Swift lifetime changes aligned in headless CI.
 */
final class Metal4TerrainLifetimeContractTest {
    @Test
    void gpuIcbOwnerBindsTheCreatingLeaseAndExactCommandRange() throws IOException {
        String source = Files.readString(Path.of("src/main/native/MetallumNative.swift"));
        int ownerStart = source.indexOf("private final class TerrainGpuIcbOwner");
        int executeStart = source.indexOf(
                "public func metallum_MTLRenderCommandEncoder_executeTerrainIcb"
        );
        assertTrue(ownerStart >= 0 && executeStart > ownerStart);

        String owner = source.substring(ownerStart, executeStart);
        assertTrue(owner.contains("let lease: Metal4MainCommandBufferLease"));
        assertTrue(owner.contains("let commandCount: Int"));
        assertTrue(owner.contains("lease: Metal4MainCommandBufferLease"));
        assertTrue(owner.contains("commandCount: Int"));
        assertEquals(3, occurrences(owner, "lease: bridge.lease"),
                "all GPU-authored ICB factories must retain their creating lease");
        assertEquals(3, occurrences(owner, "commandCount: commandCount"),
                "all GPU-authored ICB factories must retain their exact range");

        String execute = source.substring(executeStart);
        assertTrue(execute.contains("owner.lease === bridge.lease"));
        assertTrue(execute.contains("Int(drawCount) == owner.commandCount"));
        assertTrue(execute.contains("return 0"));
        assertTrue(execute.contains("else if let raw = object as? MTLIndirectCommandBuffer"),
                "the raw CPU-authored ICB path must remain available");
    }

    @Test
    void probeAndSceneUseTypedRetainsWithMatchingFfmDescriptors() throws IOException {
        String nativeSource = Files.readString(Path.of("src/main/native/MetallumNative.swift"));
        String bridge = Files.readString(Path.of(
                "src/main/java/com/metallum/client/metal/render/bridge/MetalNativeBridge.java"
        ));
        String probe = Files.readString(Path.of(
                "src/main/java/com/metallum/client/metal/render/TerrainGpuVisibilityProbe.java"
        ));

        assertTrue(nativeSource.contains("metallum_terrain_visibility_probe_retain"));
        assertTrue(nativeSource.contains("metallum_terrain_visibility_scene_retain"));
        assertTrue(bridge.contains("metallum_terrain_visibility_probe_retain"));
        assertTrue(bridge.contains("metallum_terrain_visibility_scene_retain"));
        assertTrue(bridge.contains(
                "FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS)"
        ));
        assertTrue(bridge.contains("terrainVisibilityProbeRetain(final MemorySegment probe)"));
        assertTrue(bridge.contains("terrainVisibilitySceneRetain(final MemorySegment scene)"));
        assertTrue(probe.contains("return MetalNativeBridge.terrainVisibilityProbeRetain(found);"));
        assertTrue(probe.contains("return MetalNativeBridge.terrainVisibilitySceneRetain(persistentSceneOwner);"));
        assertFalse(probe.contains("metallum_retain_object"),
                "borrowed handles must use the type-specific retain ABI");
    }

    @Test
    void temporaryProbeAndSceneRetainsAreReleasedExactlyOnceByRenderPass() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/com/metallum/client/metal/render/MetalRenderPass.java"
        ));
        assertTrue(source.contains("persistentSceneForFused("));
        assertTrue(source.contains("ownerForEpoch("));
        assertEquals(1, occurrences(source, "metallum_release_object(sceneOwner)"));
        assertEquals(1, occurrences(source, "metallum_release_object(visibilityOwner)"));
        assertTrue(source.indexOf("metallum_release_object(sceneOwner)")
                        > source.indexOf("owner.encodeFusedVisibleGpu("));
        assertTrue(source.indexOf("metallum_release_object(visibilityOwner)")
                        > source.indexOf("owner.encodeVisibleGpu("));
    }

    @Test
    void noTraceTerrainLanePreparesVisibilityAndAttemptsVisibleBeforeFallback() throws IOException {
        String renderPass = Files.readString(Path.of(
                "src/main/java/com/metallum/client/metal/render/MetalRenderPass.java"
        ));
        String source = Files.readString(Path.of(
                "src/main/java/com/metallum/mixin/render/MetalRenderPassNoTraceDrawMixin.java"
        ));
        String snapshot = Files.readString(Path.of(
                "src/main/java/com/metallum/client/metal/render/TerrainSceneSnapshot.java"
        ));
        String candidates = Files.readString(Path.of(
                "src/main/java/com/metallum/client/metal/render/TerrainCandidateSnapshot.java"
        ));
        assertTrue(renderPass.contains("TerrainSceneSnapshot.VISIBLE_GPU_ICB_ENABLED"));
        int prepare = source.indexOf("TerrainGpuVisibilityProbe.inTerrainDrawScope()");
        int submit = source.indexOf("metallum$terrainSnapshotSubmitted", prepare);
        int fallback = source.indexOf("encoder.drawIndexedPrimitivesIndirect(", submit);
        assertTrue(source.contains("TerrainSceneSnapshot.VISIBLE_GPU_ICB_ENABLED"));
        assertTrue(prepare >= 0);
        assertTrue(source.indexOf("metallum$prepareTerrainDrawForVisibility()", prepare) > prepare);
        assertTrue(submit > prepare,
                "no-trace preparation must precede the visible/fused submission attempt");
        assertTrue(fallback > submit,
                "the one existing indirect draw remains the final fallback");
        assertFalse(source.contains("RenderContractRuntime"));
        assertFalse(source.contains("recordProducer"));

        // Both the recorded and allocation-free lanes must consume the same
        // visible gate before their existing single-submit fallback. Keep the
        // switch defaults independently fail-closed as well.
        assertTrue(renderPass.indexOf("terrainSnapshotSubmitted")
                        < renderPass.indexOf("submitIndexedIndirect"));
        assertTrue(source.indexOf("metallum$terrainSnapshotSubmitted")
                        < source.indexOf("metallum$submitIndexedIndirect"));
        assertEquals(1, occurrences(source, "encoder.drawIndexedPrimitivesIndirect("));
        assertTrue(snapshot.contains(
                "System.getProperty(\"metallum.opt.terrainIcb\", \"false\")"
        ));
        assertTrue(snapshot.contains(
                "System.getProperty(\"metallum.opt.terrainGpuEncode\", \"false\")"
        ));
        assertTrue(candidates.contains(
                "System.getProperty(VISIBLE_GPU_ICB_PROPERTY, \"false\")"
        ));
    }

    private static int occurrences(final String source, final String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = source.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }
}

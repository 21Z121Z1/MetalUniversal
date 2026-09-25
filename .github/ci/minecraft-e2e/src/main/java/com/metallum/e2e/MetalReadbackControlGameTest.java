package com.metallum.e2e;

import com.metallum.client.metal.render.MetalCiFramebufferProbe;
import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Path;

@SuppressWarnings("UnstableApiUsage")
public final class MetalReadbackControlGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        Path evidenceDir = Path.of(System.getProperty("metallum.ci.evidenceDir", "build/evidence"))
                .toAbsolutePath().normalize();

        require(FabricLoader.getInstance().isModLoaded("metallum"), "MetalUniversal was not loaded");
        validateRenderer(System.getProperty("metallum.ci.rendererMode", ""),
                FabricLoader.getInstance().isModLoaded("sodium"),
                FabricLoader.getInstance().isModLoaded("iris"));

        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            singleplayer.getConnection().waitForChunksRender();
            context.waitTicks(30);

            String backend = context.computeOnClient(
                    client -> RenderSystem.getDevice().getDeviceInfo().backendName()
            );
            require("Metal".equalsIgnoreCase(backend), "Expected Metal backend, got " + backend);

            MetalCiFramebufferProbe.ProbeSuite suite = context.computeOnClient(
                    client -> MetalCiFramebufferProbe.run(evidenceDir.resolve("readback-control"))
            );

            require(suite.bufferCopy().exact(),
                    "GPU buffer-to-buffer copy did not round-trip exactly: " + suite.bufferCopy());
            require(suite.textureRoundTrip().exact(),
                    "GPU buffer-texture-buffer copy did not round-trip exactly: " + suite.textureRoundTrip());
            require(suite.renderClear().exact(),
                    "GPU render clear did not survive texture readback: " + suite.renderClear());
        }
    }

    // Shared truth table: Sodium-only is distinct from Sodium plus optional Iris.
    static void validateRenderer(String mode, boolean sodium, boolean iris) {
        FrameWorkloads.validateProducer(mode, sodium, iris);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}

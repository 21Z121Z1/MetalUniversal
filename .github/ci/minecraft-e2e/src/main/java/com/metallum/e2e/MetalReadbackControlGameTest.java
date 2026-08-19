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
        require(FabricLoader.getInstance().isModLoaded("sodium"), "Sodium was not loaded");
        require(FabricLoader.getInstance().isModLoaded("iris"), "Iris was not loaded");

        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            singleplayer.getClientLevel().waitForChunksRender();
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

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}

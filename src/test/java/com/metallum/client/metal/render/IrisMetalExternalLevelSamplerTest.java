package com.metallum.client.metal.render;

import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.shaders.GpuDebugOptions;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import net.irisshaders.iris.pipeline.programs.ShaderKey;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.lang.foreign.MemorySegment;
import java.util.Map;
import java.util.OptionalDouble;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/** Real-device validation for Iris's externally managed Mojang level samplers. */
@EnabledOnOs(OS.MAC)
final class IrisMetalExternalLevelSamplerTest {
    private MetalDevice device;
    private MetalDevice foreignDevice;

    @AfterEach
    void closeDevices() {
        MetalFxManager.close();
        if (this.foreignDevice != null) {
            this.foreignDevice.close();
        }
        if (this.device != null) {
            this.device.close();
        }
    }

    @Test
    void overlayRequiresTheLiveSameDeviceClampLinearBinding() {
        this.device = createDevice("Iris external overlay device");
        this.foreignDevice = createDevice("Iris external overlay foreign device");

        int usage = GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST;
        try (MetalGpuTexture texture = (MetalGpuTexture) this.device.createTexture(
                "real Mojang overlay fixture", usage, GpuFormat.RGBA8_UNORM, 16, 16, 1, 1
        ); MetalGpuTextureView view = (MetalGpuTextureView) this.device.createTextureView(texture);
             MetalGpuSampler linear = (MetalGpuSampler) this.device.createSampler(
                     AddressMode.CLAMP_TO_EDGE,
                     AddressMode.CLAMP_TO_EDGE,
                     FilterMode.LINEAR,
                     FilterMode.LINEAR,
                     1,
                     OptionalDouble.empty()
             );
             MetalGpuSampler nearest = (MetalGpuSampler) this.device.createSampler(
                     AddressMode.CLAMP_TO_EDGE,
                     AddressMode.CLAMP_TO_EDGE,
                     FilterMode.NEAREST,
                     FilterMode.NEAREST,
                     1,
                     OptionalDouble.empty()
             )) {
            MetalRenderPass.TextureViewAndSampler binding =
                    IrisMetalPipelineOverrides.checkedMojangExternalOverlayBinding(
                            this.device, view, linear
                    );
            assertSame(view, binding.textureView());
            assertSame(linear, binding.sampler());

            MetalRenderPass.TextureViewAndSampler external =
                    new MetalRenderPass.TextureViewAndSampler(view, linear);
            MetalRenderPass.TextureViewAndSampler selectedExternal =
                    IrisMetalPipelineOverrides.selectMojangExternalOverlayBinding(
                    this.device,
                    ShaderKey.SHADOW_ENTITIES_CUTOUT,
                    "iris_overlay",
                    Map.of(),
                    external
            );
            assertNotNull(selectedExternal);
            assertSame(view, selectedExternal.textureView());
            assertSame(linear, selectedExternal.sampler());

            MetalRenderPass.TextureViewAndSampler drawLocal =
                    new MetalRenderPass.TextureViewAndSampler(view, linear);
            assertSame(drawLocal, IrisMetalPipelineOverrides.selectMojangExternalOverlayBinding(
                    this.device,
                    ShaderKey.SHADOW_ENTITIES_CUTOUT,
                    "iris_overlay",
                    Map.of("Sampler1", drawLocal),
                    external
            ));

            assertNull(IrisMetalPipelineOverrides.selectMojangExternalOverlayBinding(
                    this.device,
                    ShaderKey.TEXTURED,
                    "iris_overlay",
                    Map.of(),
                    external
            ));
            assertNull(IrisMetalPipelineOverrides.selectMojangExternalOverlayBinding(
                    this.foreignDevice,
                    ShaderKey.SHADOW_ENTITIES_CUTOUT,
                    "iris_overlay",
                    Map.of(),
                    external
            ));

            assertNull(IrisMetalPipelineOverrides.checkedMojangExternalOverlayBinding(
                    this.foreignDevice, view, linear
            ));
            assertNull(IrisMetalPipelineOverrides.checkedMojangExternalOverlayBinding(
                    this.device, view, nearest
            ));

            linear.close();
            assertNull(IrisMetalPipelineOverrides.checkedMojangExternalOverlayBinding(
                    this.device, view, linear
            ));
            view.close();
            assertNull(IrisMetalPipelineOverrides.checkedMojangExternalOverlayBinding(
                    this.device, view, nearest
            ));
        }
    }

    private static MetalDevice createDevice(final String label) {
        MemorySegment nativeDevice = MetalNativeBridge.metallum_create_system_default_device();
        assertFalse(MetalNativeBridge.isNullHandle(nativeDevice));
        return new MetalDevice(
                (identifier, type) -> null,
                new GpuDebugOptions(2, true, true, true),
                nativeDevice,
                MemorySegment.NULL,
                label,
                MemorySegment.NULL
        );
    }
}

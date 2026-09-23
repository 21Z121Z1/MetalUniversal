package com.metallum.client.metal.render;

import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertThrows;

class MetalTextureBindingContractTest {
    @Test
    void foreignBackendIsRejectedBeforeResourceAccess() {
        GpuTextureView view = foreignResource(GpuTextureView.class);
        GpuSampler sampler = foreignResource(GpuSampler.class);
        assertThrows(IllegalStateException.class, () ->
                MetalRenderPass.validateTextureBinding(null, view, sampler, "foreign"));
    }

    @Test
    void incompleteBindingIsRejected() {
        assertThrows(IllegalStateException.class, () ->
                MetalRenderPass.validateTextureBinding(null, null, null, "missing"));
    }

    private static <T> T foreignResource(Class<T> type) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> {
                    throw new AssertionError("Foreign resource must not be accessed: " + method.getName());
                }));
    }
}

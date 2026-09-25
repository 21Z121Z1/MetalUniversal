package com.metallum.client.metal.render;

import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.function.BiFunction;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Small 26.3 adapter for generated GLSL layered over Mojang's source provider. */
final class MetalShaderSourceAdapters {
    private MetalShaderSourceAdapters() {
    }

    static ShaderSource overlay(
            final Map<Identifier, String> generated,
            @Nullable final ShaderSource fallback
    ) {
        return new ShaderSource() {
            @Override
            public @Nullable String getShader(final Identifier id, final ShaderType type) {
                String source = generated.get(id);
                return source != null ? source : fallback == null ? null : fallback.getShader(id, type);
            }

            @Override
            public @Nullable CachedIncludeSource getInclude(final Identifier id) {
                return fallback == null ? null : fallback.getInclude(id);
            }

            @Override
            public void close() {
                // The generated map and fallback provider are owned by the caller.
            }
        };
    }

    /**
     * Adapts the one-function shader lookup used by small backend fixtures to
     * RenderPearl 26.3's shader/include provider.  The production frontend
     * always supplies a complete provider; this seam is intentionally limited
     * to tests that compile generated GLSL strings directly.
     */
    static ShaderSource from(
            final BiFunction<Identifier, ShaderType, @Nullable String> shaderLookup
    ) {
        return new ShaderSource() {
            @Override
            public @Nullable String getShader(final Identifier id, final ShaderType type) {
                return shaderLookup.apply(id, type);
            }

            @Override
            public @Nullable CachedIncludeSource getInclude(final Identifier id) {
                return null;
            }

            @Override
            public void close() {
            }
        };
    }

    static ShaderSource empty() {
        return from((identifier, type) -> null);
    }

    /**
     * Keeps the live Minecraft provider authoritative while making the
     * backend-owned sources available in Loom's directory-based dev runtime.
     * Fabric's production jar exposes the same files through its resource pack;
     * this fallback only runs when the provider reports a missing shader.
     */
    static ShaderSource withClasspathFallback(final ShaderSource delegate) {
        return new ShaderSource() {
            @Override
            public @Nullable String getShader(final Identifier id, final ShaderType type) {
                String source = delegate.getShader(id, type);
                if (source != null) {
                    return source;
                }
                Identifier file = type.idConverter().idToFile(id);
                String resourceName = "assets/" + file.getNamespace() + "/" + file.getPath();
                try (InputStream stream = MetalShaderSourceAdapters.class.getClassLoader()
                        .getResourceAsStream(resourceName)) {
                    return stream == null ? null : new String(stream.readAllBytes(), StandardCharsets.UTF_8);
                } catch (IOException exception) {
                    return null;
                }
            }

            @Override
            public @Nullable CachedIncludeSource getInclude(final Identifier id) {
                return delegate.getInclude(id);
            }

            @Override
            public void close() {
                delegate.close();
            }
        };
    }
}
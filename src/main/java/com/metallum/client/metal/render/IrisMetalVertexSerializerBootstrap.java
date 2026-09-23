package com.metallum.client.metal.render;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.caffeinemc.mods.sodium.api.vertex.serializer.VertexSerializerRegistry;
import net.irisshaders.iris.vertices.IrisVertexFormats;
import net.irisshaders.iris.vertices.sodium.EntityToTerrainVertexSerializer;
import net.irisshaders.iris.vertices.sodium.GlyphExtVertexSerializer;
import net.irisshaders.iris.vertices.sodium.IrisEntityToTerrainVertexSerializer;
import net.irisshaders.iris.vertices.sodium.ModelToEntityVertexSerializer;

/**
 * Preserves the CPU-only part of Iris's renderer bootstrap when Metal skips
 * the surrounding OpenGL capability probes.
 */
public final class IrisMetalVertexSerializerBootstrap {
    private static boolean registered;

    private IrisMetalVertexSerializerBootstrap() {
    }

    public static synchronized void ensureRegistered() {
        if (registered) {
            return;
        }

        registerInto(VertexSerializerRegistry.instance());
        registered = true;
    }

    static void registerInto(final VertexSerializerRegistry registry) {
        registry.registerSerializer(
                DefaultVertexFormat.ENTITY,
                IrisVertexFormats.TERRAIN,
                new EntityToTerrainVertexSerializer()
        );
        registry.registerSerializer(
                IrisVertexFormats.ENTITY,
                IrisVertexFormats.TERRAIN,
                new IrisEntityToTerrainVertexSerializer()
        );
        registry.registerSerializer(
                DefaultVertexFormat.POSITION_TEX_LIGHTMAP_COLOR,
                IrisVertexFormats.GLYPH,
                new GlyphExtVertexSerializer()
        );
        registry.registerSerializer(
                DefaultVertexFormat.ENTITY,
                IrisVertexFormats.ENTITY,
                new ModelToEntityVertexSerializer()
        );
    }
}

package com.metallum.client.metal.render;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.caffeinemc.mods.sodium.api.vertex.serializer.VertexSerializer;
import net.caffeinemc.mods.sodium.api.vertex.serializer.VertexSerializerRegistry;
import net.irisshaders.iris.vertices.IrisVertexFormats;
import net.irisshaders.iris.vertices.sodium.EntityToTerrainVertexSerializer;
import net.irisshaders.iris.vertices.sodium.GlyphExtVertexSerializer;
import net.irisshaders.iris.vertices.sodium.IrisEntityToTerrainVertexSerializer;
import net.irisshaders.iris.vertices.sodium.ModelToEntityVertexSerializer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

final class IrisMetalVertexSerializerBootstrapTest {
    @Test
    void metalBootstrapPreservesAllNativeIrisVertexSerializers() {
        RecordingRegistry registry = new RecordingRegistry();
        IrisMetalVertexSerializerBootstrap.registerInto(registry);

        assertEquals(4, registry.registrations.size());
        assertRegistration(
                registry.registrations.get(0),
                DefaultVertexFormat.ENTITY,
                IrisVertexFormats.TERRAIN,
                EntityToTerrainVertexSerializer.class
        );
        assertRegistration(
                registry.registrations.get(1),
                IrisVertexFormats.ENTITY,
                IrisVertexFormats.TERRAIN,
                IrisEntityToTerrainVertexSerializer.class
        );
        assertRegistration(
                registry.registrations.get(2),
                DefaultVertexFormat.POSITION_TEX_LIGHTMAP_COLOR,
                IrisVertexFormats.GLYPH,
                GlyphExtVertexSerializer.class
        );
        assertRegistration(
                registry.registrations.get(3),
                DefaultVertexFormat.ENTITY,
                IrisVertexFormats.ENTITY,
                ModelToEntityVertexSerializer.class
        );
    }

    private static void assertRegistration(
            final Registration registration,
            final VertexFormat source,
            final VertexFormat destination,
            final Class<? extends VertexSerializer> serializerType
    ) {
        assertSame(source, registration.source);
        assertSame(destination, registration.destination);
        assertInstanceOf(serializerType, registration.serializer);
    }

    private record Registration(
            VertexFormat source,
            VertexFormat destination,
            VertexSerializer serializer
    ) {
    }

    private static final class RecordingRegistry implements VertexSerializerRegistry {
        private final List<Registration> registrations = new ArrayList<>();

        @Override
        public VertexSerializer get(final VertexFormat source, final VertexFormat destination) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void registerSerializer(
                final VertexFormat source,
                final VertexFormat destination,
                final VertexSerializer serializer
        ) {
            this.registrations.add(new Registration(source, destination, serializer));
        }
    }
}

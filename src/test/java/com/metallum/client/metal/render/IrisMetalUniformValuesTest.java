package com.metallum.client.metal.render;

import net.caffeinemc.mods.sodium.client.util.FogParameters;
import net.irisshaders.iris.gl.uniform.UniformUpdateFrequency;
import net.irisshaders.iris.uniforms.FrameUpdateNotifier;
import net.irisshaders.iris.uniforms.custom.CustomUniformFixedInputUniformsHolder;
import net.irisshaders.iris.uniforms.custom.CustomUniforms;
import org.joml.Vector3d;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class IrisMetalUniformValuesTest {
    @Test
    void refreshesFogUniformsAtTheDrawBoundary() {
        List<IrisMetalGlslLinker.UniformMember> layout = List.of(
                new IrisMetalGlslLinker.UniformMember("vec3", "fogColor", 0, 0, 16),
                new IrisMetalGlslLinker.UniformMember("vec4", "iris_FogColor", 0, 16, 16),
                new IrisMetalGlslLinker.UniformMember("float", "iris_FogDensity", 0, 32, 4),
                new IrisMetalGlslLinker.UniformMember("float", "iris_FogStart", 0, 36, 4),
                new IrisMetalGlslLinker.UniformMember("float", "iris_FogEnd", 0, 40, 4)
        );
        ByteBuffer destination = ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder());
        FogParameters fog = new FogParameters(
                0.1F, 0.2F, 0.3F, 0.4F,
                7.0F, 91.0F, 3.0F, 120.0F
        );

        IrisMetalUniformValues.writeLiveFogUniforms(
                destination,
                layout,
                fog,
                new Vector3d(0.8, 0.7, 0.6),
                0.25F
        );

        assertEquals(0.8F, destination.getFloat(0));
        assertEquals(0.7F, destination.getFloat(4));
        assertEquals(0.6F, destination.getFloat(8));
        assertEquals(0.1F, destination.getFloat(16));
        assertEquals(0.2F, destination.getFloat(20));
        assertEquals(0.3F, destination.getFloat(24));
        assertEquals(0.4F, destination.getFloat(28));
        assertEquals(0.25F, destination.getFloat(32));
        assertEquals(7.0F, destination.getFloat(36));
        assertEquals(91.0F, destination.getFloat(40));
    }

    @Test
    void updatesFixedInputsOnlyOnceAfterCustomUniformDependencies() {
        AtomicInteger updates = new AtomicInteger();
        CustomUniformFixedInputUniformsHolder.Builder fixedBuilder =
                new CustomUniformFixedInputUniformsHolder.Builder();
        fixedBuilder.uniform1f(
                UniformUpdateFrequency.PER_FRAME,
                "fixedCounter",
                (java.util.function.IntSupplier) updates::incrementAndGet
        );
        CustomUniformFixedInputUniformsHolder fixedInputs = fixedBuilder.build();
        CustomUniforms customUniforms = new CustomUniforms.Builder().build(fixedInputs);

        IrisMetalUniformValues values = new IrisMetalUniformValues(
                0.0F,
                customUniforms,
                fixedInputs,
                new FrameUpdateNotifier(),
                () -> 0
        );
        values.updateFrame();

        assertEquals(1, updates.get(), "a fixed supplier must not advance twice in one frame");
        values.close();
    }

    @Test
    void writesFixedScalarVectorAndMatrixValuesThroughTheIrisHolder() {
        CustomUniformFixedInputUniformsHolder.Builder fixedBuilder =
                new CustomUniformFixedInputUniformsHolder.Builder();
        fixedBuilder.uniform1f(
                UniformUpdateFrequency.PER_FRAME,
                "fixedFloat",
                (net.irisshaders.iris.gl.uniform.FloatSupplier) () -> 2.5F
        );
        fixedBuilder.uniform3d(
                UniformUpdateFrequency.PER_FRAME,
                "fixedVector",
                () -> new Vector3d(1.25, -2.5, 3.75)
        );
        fixedBuilder.uniformMatrix(
                UniformUpdateFrequency.PER_FRAME,
                "fixedMatrix",
                () -> new Matrix4f().translation(4.0F, 5.0F, 6.0F)
        );
        CustomUniformFixedInputUniformsHolder fixedInputs = fixedBuilder.build();
        CustomUniforms customUniforms = new CustomUniforms.Builder().build(fixedInputs);
        fixedInputs.updateAll();

        try (IrisMetalUniformValues values = new IrisMetalUniformValues(
                0.0F,
                customUniforms,
                fixedInputs,
                new FrameUpdateNotifier(),
                () -> 0
        )) {
            ByteBuffer destination = ByteBuffer.allocateDirect(128).order(ByteOrder.nativeOrder());
            values.writeOfficialUniform(
                    destination,
                    member("float", "fixedFloat", 0, 0, 4)
            );
            values.writeOfficialUniform(
                    destination,
                    member("vec3", "fixedVector", 0, 16, 16)
            );
            values.writeOfficialUniform(
                    destination,
                    member("mat4", "fixedMatrix", 0, 32, 64)
            );

            assertEquals(2.5F, destination.getFloat(0));
            assertEquals(1.25F, destination.getFloat(16));
            assertEquals(-2.5F, destination.getFloat(20));
            assertEquals(3.75F, destination.getFloat(24));
            assertEquals(4.0F, destination.getFloat(80));
            assertEquals(5.0F, destination.getFloat(84));
            assertEquals(6.0F, destination.getFloat(88));
        }
    }

    @Test
    void rejectsUnknownStrictUniformBeforeItCanBeZeroFilled() {
        CustomUniformFixedInputUniformsHolder fixedInputs =
                new CustomUniformFixedInputUniformsHolder.Builder().build();
        CustomUniforms customUniforms = new CustomUniforms.Builder().build(fixedInputs);
        try (IrisMetalUniformValues values = new IrisMetalUniformValues(
                0.0F,
                customUniforms,
                fixedInputs,
                new FrameUpdateNotifier(),
                () -> 0
        )) {
            ByteBuffer destination = ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder());
            assertThrows(
                    IllegalStateException.class,
                    () -> values.writeOfficialUniform(
                            destination,
                            member("float", "notRegisteredByIris", 0, 0, 4)
                    )
            );
        }
    }

    private static IrisMetalGlslLinker.UniformMember member(
            final String type,
            final String name,
            final int arrayCount,
            final int offset,
            final int byteSize
    ) {
        return new IrisMetalGlslLinker.UniformMember(type, name, arrayCount, offset, byteSize);
    }
}

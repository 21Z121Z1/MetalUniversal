package com.metallum.client.metal.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

final class MetalShaderLodBiasTest {
    @Test
    void plainTwoArgumentSampleGainsBias() {
        String msl = "float4 c = Sampler0Tex.sample(Sampler0Smplr, in.uv);";
        String patched = MetalCrossShaderCompiler.applySampleLodBias(msl, -1.5F);
        assertEquals("float4 c = Sampler0Tex.sample(Sampler0Smplr, in.uv, bias(-1.5f));", patched);
    }

    @Test
    void nestedParenthesesResolveToCorrectClose() {
        String msl = "float4 c = tex.sample(smplr, fract(uv * float2(2.0, mix(a, b, t))));";
        String patched = MetalCrossShaderCompiler.applySampleLodBias(msl, -2.0F);
        assertEquals(
                "float4 c = tex.sample(smplr, fract(uv * float2(2.0, mix(a, b, t))), bias(-2.0f));",
                patched
        );
    }

    @Test
    void explicitLevelCallIsUntouched() {
        String msl = "float4 c = tex.sample(smplr, uv, level(0.0));";
        assertEquals(msl, MetalCrossShaderCompiler.applySampleLodBias(msl, -1.5F));
    }

    @Test
    void threeArgumentOffsetCallIsUntouched() {
        String msl = "float4 c = tex.sample(smplr, uv, int2(1, 0));";
        assertEquals(msl, MetalCrossShaderCompiler.applySampleLodBias(msl, -1.5F));
    }

    @Test
    void multipleCallsAreEachPatched() {
        String msl = "a = t0.sample(s0, uv0); b = t1.sample(s1, uv1, level(2.0)); c = t2.sample(s2, uv2);";
        String patched = MetalCrossShaderCompiler.applySampleLodBias(msl, -1.0F);
        assertEquals(
                "a = t0.sample(s0, uv0, bias(-1.0f)); b = t1.sample(s1, uv1, level(2.0)); c = t2.sample(s2, uv2, bias(-1.0f));",
                patched
        );
    }

    @Test
    void zeroBiasReturnsSameInstance() {
        String msl = "float4 c = tex.sample(smplr, uv);";
        assertSame(msl, MetalCrossShaderCompiler.applySampleLodBias(msl, 0.0F));
    }

}

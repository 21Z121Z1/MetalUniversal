package com.metallum.client.metal.render;

import org.junit.jupiter.api.Test;
import org.lwjgl.util.shaderc.Shaderc;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** Tests the real shaderc module, not a hand-authored imitation of its IR. */
final class MetalFxCutoutSpirvTest {
    @Test
    void preservesOriginalSamplingDiscardAndOutputsAndOnlyInstrumentsEntryReturns() {
        byte[] original = compile("""
                #version 450
                layout(set=0,binding=0) uniform sampler2D image;
                layout(location=0) in vec2 uv;
                layout(location=0) out vec4 color;
                float helper(float v) { if (v < 0.1) return 0.0; return v; }
                void main() {
                    vec4 sampled = texture(image, uv);
                    if (sampled.a < 0.5) discard;
                    color = vec4(sampled.rgb, helper(sampled.a));
                    if (uv.x < 0.5) return;
                    color.rgb *= 0.75;
                }
                """);
        ByteBuffer input = ByteBuffer.allocate(original.length + 12).order(ByteOrder.BIG_ENDIAN);
        input.position(8); input.put(original); input.flip(); input.position(8);
        int position = input.position(), limit = input.limit();
        byte[] transformed = MetalFxCutoutSpirv.addCoverage(input.asReadOnlyBuffer()).orElseThrow();
        assertEquals(position, input.position()); assertEquals(limit, input.limit());
        byte[] unchanged = new byte[original.length]; input.duplicate().get(unchanged);
        assertArrayEquals(original, unchanged);
        List<int[]> before = instructions(original), after = instructions(transformed);
        int bound = ByteBuffer.wrap(original).order(ByteOrder.LITTLE_ENDIAN).getInt(12);
        int newBound = ByteBuffer.wrap(transformed).order(ByteOrder.LITTLE_ENDIAN).getInt(12);
        assertTrue(newBound > bound && newBound <= bound + 3);
        int coverage = newBound - 1;
        int entry = before.stream().filter(i -> op(i) == 15).findFirst().orElseThrow()[2];
        assertTrue(after.stream().anyMatch(i -> Arrays.equals(i, new int[]{(4<<16)|71, coverage, 30, 1})));
        var entryDeclaration = after.stream().filter(i -> op(i) == 15).findFirst().orElseThrow();
        assertEquals(coverage, entryDeclaration[entryDeclaration.length - 1]);
        int function = -1, entryReturns = 0, coverageStores = 0;
        for (int index=0; index<after.size(); index++) {
            int[] i = after.get(index);
            if (op(i) == 54) function = i[2];
            if (op(i) == 62 && i[1] == coverage) {
                coverageStores++;
                assertEquals(entry, function, "helper functions must not mint entry coverage");
                assertEquals(253, op(after.get(index + 1)));
            }
            if (op(i) == 253 && function == entry) {
                entryReturns++;
                assertEquals(62, op(after.get(index-1)));
                assertEquals(coverage, after.get(index-1)[1]);
            }
            if (op(i) == 56) function = -1;
        }
        assertTrue(entryReturns >= 2); assertEquals(entryReturns, coverageStores);
        // Removing only newly allocated declarations/stores restores every old
        // instruction in order, including image sampling, branches and OpKill.
        List<int[]> recovered = new ArrayList<>();
        for (int[] i : after) {
            if ((op(i)==71 && i[1]==coverage) || (op(i)==59 && i[2]==coverage)
                    || (op(i)==32 && i[1]>=bound) || (op(i)==43 && i[2]>=bound)
                    || (op(i)==62 && i[1]==coverage)) continue;
            if (op(i)==15) {
                i=Arrays.copyOf(i,i.length-1); i[0]=(i.length<<16)|15;
            }
            recovered.add(i);
        }
        assertEquals(before.size(), recovered.size());
        for (int i=0; i<before.size(); i++) assertArrayEquals(before.get(i),recovered.get(i),"instruction="+i);
        assertTrue(before.stream().anyMatch(i -> op(i)==252 || op(i)==4416));
    }

    @Test
    void ordinaryOpaqueShaderHasNoCoverageAndExistingMrtIsNotOverwritten() {
        byte[] opaque=compile("#version 450\nlayout(location=0) out vec4 c; void main(){c=vec4(1);}");
        assertTrue(MetalFxCutoutSpirv.addCoverage(ByteBuffer.wrap(opaque)).isEmpty());
        byte[] occupied=compile("""
                #version 450
                layout(location=0) out vec4 c;
                layout(location=1) out float other;
                void main(){if(gl_FragCoord.x<2)discard;c=vec4(1);other=0.25;}
                """);
        assertThrows(IllegalArgumentException.class,()->MetalFxCutoutSpirv.addCoverage(ByteBuffer.wrap(occupied)));
    }

    @Test
    void malformedModulesAndAFragmentWithoutNormalExitFailClosed() {
        assertThrows(IllegalArgumentException.class,()->MetalFxCutoutSpirv.addCoverage(ByteBuffer.allocate(19)));
        byte[] valid=compile("#version 450\nlayout(location=0) out vec4 c; void main(){if(gl_FragCoord.x<2)discard;c=vec4(1);}");
        byte[] truncated=Arrays.copyOf(valid,valid.length-1);
        assertThrows(IllegalArgumentException.class,()->MetalFxCutoutSpirv.addCoverage(ByteBuffer.wrap(truncated)));
        byte[] zeroCount=valid.clone(); ByteBuffer.wrap(zeroCount).order(ByteOrder.LITTLE_ENDIAN).putInt(20,15);
        assertThrows(IllegalArgumentException.class,()->MetalFxCutoutSpirv.addCoverage(ByteBuffer.wrap(zeroCount)));
        byte[] onlyDiscard=compile("#version 450\nlayout(location=0) out vec4 c; void main(){discard;}");
        assertThrows(IllegalArgumentException.class,()->MetalFxCutoutSpirv.addCoverage(ByteBuffer.wrap(onlyDiscard)));
    }

    private static int op(int[] i){return i[0]&0xffff;}
    private static List<int[]> instructions(byte[] bytes){
        int[] words=new int[bytes.length/4]; ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(words);
        List<int[]> result=new ArrayList<>();
        for(int p=5;p<words.length;){int n=words[p]>>>16;assertTrue(n>0&&p+n<=words.length);result.add(Arrays.copyOfRange(words,p,p+n));p+=n;}
        return result;
    }
    private static byte[] compile(String source){
        long compiler=Shaderc.shaderc_compiler_initialize(); assertNotEquals(0,compiler);
        long options=Shaderc.shaderc_compile_options_initialize(); assertNotEquals(0,options);
        try{
            Shaderc.shaderc_compile_options_set_target_env(options,Shaderc.shaderc_target_env_vulkan,Shaderc.shaderc_env_version_vulkan_1_2);
            long result=Shaderc.shaderc_compile_into_spv(compiler,source,Shaderc.shaderc_fragment_shader,"coverage-contract","main",options);
            assertNotEquals(0,result);
            try{
                assertEquals(Shaderc.shaderc_compilation_status_success,Shaderc.shaderc_result_get_compilation_status(result),Shaderc.shaderc_result_get_error_message(result));
                ByteBuffer bytes=Shaderc.shaderc_result_get_bytes(result);assertNotNull(bytes);
                byte[] owned=new byte[bytes.remaining()];bytes.get(owned);return owned;
            }finally{Shaderc.shaderc_result_release(result);}
        }finally{Shaderc.shaderc_compile_options_release(options);Shaderc.shaderc_compiler_release(compiler);}
    }
}

package com.metallum.client.metal.render;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.lwjgl.util.shaderc.Shaderc;

import static org.junit.jupiter.api.Assertions.*;

/** Reflect real SPIR-V, including depth reads and unreachable depth-writing functions. */
final class MetalFragmentDepthReflectionTest {
    @ParameterizedTest
    @CsvSource({"color,false", "read_depth,false", "write_depth,true", "conditional_depth,true", "unused_depth,false"})
    void onlyAnActiveFragmentDepthOutputRequiresADepthAttachment(String kind,boolean expected) throws Exception {
        String main=switch(kind) {
            case "read_depth" -> "color=vec4(gl_FragCoord.z);";
            case "write_depth" -> "gl_FragDepth=.4; color=vec4(1);";
            case "conditional_depth" -> "if(gl_FragCoord.x>1)gl_FragDepth=.4; color=vec4(1);";
            default -> "color=vec4(1);";
        };
        String source="#version 450\nlayout(location=0) out vec4 color;\n"
                +(kind.equals("unused_depth") ? "void unreachable(){gl_FragDepth=.7;}\n" : "")
                +"void main(){"+main+"}\n";
        long compiler=Shaderc.shaderc_compiler_initialize();
        long result=Shaderc.shaderc_compile_into_spv(compiler,source,Shaderc.shaderc_fragment_shader,"fragment-depth-contract","main",0);
        try {
            assertEquals(Shaderc.shaderc_compilation_status_success,Shaderc.shaderc_result_get_compilation_status(result),
                    Shaderc.shaderc_result_get_error_message(result));
            var bytes=Shaderc.shaderc_result_get_bytes(result);
            int position=bytes.position(),limit=bytes.limit();
            assertEquals(expected,MetalCrossShaderCompiler.writesFragmentDepth(bytes));
            assertEquals(position,bytes.position());assertEquals(limit,bytes.limit());
            assertEquals(expected,MetalCrossShaderCompiler.writesFragmentDepth(bytes),"Cache-hit reflection must be repeatable");
        } finally {
            Shaderc.shaderc_result_release(result);Shaderc.shaderc_compiler_release(compiler);
        }
    }
}

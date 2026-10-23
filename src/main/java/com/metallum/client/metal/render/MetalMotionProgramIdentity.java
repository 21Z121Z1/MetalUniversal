package com.metallum.client.metal.render;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderType;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Diagnostic identity of original draw semantics; a location alone aliases shader variants. */
public final class MetalMotionProgramIdentity {
    public record Element(String semantic, int offset, String format) {}
    public record Binding(int slot, int stride, int stepRate, List<Element> elements) {}
    public record Description(String location, String vertexShader, String fragmentShader,
                       List<String> flags, Map<String, String> values, List<Binding> vertexBindings,
                       String topology, boolean cull, String polygonMode,
                       List<String> colorTargets, String depthStencil) {}
    record Identity(String key, Description source) {}

    private MetalMotionProgramIdentity() {}

    static Identity of(RenderPipeline source) {
        var bindings = new ArrayList<Binding>();
        for (int slot = 0; slot < source.getVertexFormatBindings().size(); slot++) {
            var format = source.getVertexFormatBinding(slot);
            if (format != null) bindings.add(new Binding(slot, format.getVertexSize(), format.getStepRate(),
                    format.getElements().stream().map(e -> new Element(e.name(), e.offset(), e.format().toString())).toList()));
        }
        var description = new Description(source.getLocation().toString(),
                String.valueOf(source.getShaders().get(ShaderType.VERTEX)),
                String.valueOf(source.getShaders().get(ShaderType.FRAGMENT)),
                source.getShaderDefines().flags().stream().sorted().toList(),
                Collections.unmodifiableSortedMap(new TreeMap<>(source.getShaderDefines().values())),
                List.copyOf(bindings), source.getPrimitiveTopology().toString(), source.isCull(), source.getPolygonMode().toString(),
                source.getColorTargetStates().stream().map(String::valueOf).toList(), String.valueOf(source.getDepthStencilState()));
        try {
            String canonical = new com.google.gson.Gson().toJson(description);
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
            return new Identity(description.location()+"#"+digest, description);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}

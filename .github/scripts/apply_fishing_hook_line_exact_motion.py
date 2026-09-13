from pathlib import Path


def once(path: str, old: str, new: str) -> None:
    p = Path(path)
    s = p.read_text()
    n = s.count(old)
    if n != 1:
        raise SystemExit(f"{path}: expected one anchor, got {n}: {old[:120]!r}")
    p.write_text(s.replace(old, new, 1))


# Exact line motion pipeline ABI.
p = "src/main/java/com/metallum/client/metal/render/MetalEntityMotionPipeline.java"
once(p,
     "import com.mojang.blaze3d.GpuFormat;\n",
     "import com.mojang.blaze3d.GpuFormat;\nimport com.mojang.blaze3d.PrimitiveTopology;\n")
once(p,
     '        WATER_MASK("core/position_previous_motion", "core/position_previous_motion", "position_previous_motion/");\n',
     '        WATER_MASK("core/position_previous_motion", "core/position_previous_motion", "position_previous_motion/"),\n'
     '        LINE("core/rendertype_lines_previous_motion", "core/rendertype_lines_previous_motion", "line_previous_motion/");\n')
once(p,
     '    private static final VertexFormat PREVIOUS_POSITION_FORMAT = VertexFormat.builder(0)\n'
     '            .addAttribute("PreviousPosition", GpuFormat.RGB32_FLOAT)\n'
     '            .build();\n',
     '    private static final VertexFormat PREVIOUS_POSITION_FORMAT = VertexFormat.builder(0)\n'
     '            .addAttribute("PreviousPosition", GpuFormat.RGB32_FLOAT)\n'
     '            .build();\n'
     '    /** Previous attributes needed to reproduce Minecraft 26.2 rendertype_lines extrusion. */\n'
     '    private static final VertexFormat PREVIOUS_LINE_FORMAT = VertexFormat.builder(0)\n'
     '            .addAttribute("PreviousPosition", GpuFormat.RGB32_FLOAT)\n'
     '            .addAttribute("PreviousNormal", GpuFormat.RGB32_FLOAT)\n'
     '            .addAttribute("PreviousLineWidth", GpuFormat.R32_FLOAT)\n'
     '            .build();\n')
once(p,
     '        if (shader.equals("core/rendertype_water_mask")\n'
     '                && DefaultVertexFormat.POSITION.equals(format)) {\n'
     '            return PreviousFamily.WATER_MASK;\n'
     '        }\n'
     '        return null;\n',
     '        if (shader.equals("core/rendertype_water_mask")\n'
     '                && DefaultVertexFormat.POSITION.equals(format)) {\n'
     '            return PreviousFamily.WATER_MASK;\n'
     '        }\n'
     '        if (shader.equals("core/rendertype_lines")\n'
     '                && source.getFragmentShader().getPath().equals("core/rendertype_lines")\n'
     '                && DefaultVertexFormat.POSITION_COLOR_NORMAL_LINE_WIDTH.equals(format)\n'
     '                && source.getPrimitiveTopology() == PrimitiveTopology.LINES) {\n'
     '            ColorTargetState target = source.getColorTargetState();\n'
     '            return target != null && target.blendFunction().isEmpty() ? PreviousFamily.LINE : null;\n'
     '        }\n'
     '        return null;\n')
once(p,
     '    static VertexFormat previousPositionFormat() {\n'
     '        return PREVIOUS_POSITION_FORMAT;\n'
     '    }\n',
     '    static VertexFormat previousPositionFormat() {\n'
     '        return PREVIOUS_POSITION_FORMAT;\n'
     '    }\n\n'
     '    static VertexFormat previousVertexFormat(final RenderPipeline source) {\n'
     '        return previousFamilyOf(source) == PreviousFamily.LINE\n'
     '                ? PREVIOUS_LINE_FORMAT\n'
     '                : PREVIOUS_POSITION_FORMAT;\n'
     '    }\n\n'
     '    static int previousVertexFloatsPerVertex(final RenderPipeline source) {\n'
     '        return previousFamilyOf(source) == PreviousFamily.LINE ? 7 : 3;\n'
     '    }\n')
once(p,
     '        if (previousPositions) {\n'
     '            builder.withVertexBinding(1, PREVIOUS_POSITION_FORMAT);\n'
     '        }\n',
     '        if (previousPositions) {\n'
     '            builder.withVertexBinding(1, previousFamily == PreviousFamily.LINE\n'
     '                    ? PREVIOUS_LINE_FORMAT\n'
     '                    : PREVIOUS_POSITION_FORMAT);\n'
     '        }\n')

# Transactional staged history: preserve the 12-byte fast path, use 7 floats only for line draws.
p = "src/main/java/com/metallum/client/metal/render/MetalPreviousVertexHistory.java"
once(p,
     "import com.mojang.blaze3d.vertex.ByteBufferBuilder;\n",
     "import com.mojang.blaze3d.vertex.ByteBufferBuilder;\nimport com.mojang.blaze3d.vertex.DefaultVertexFormat;\n")
once(p,
     '    record Snapshot(Signature signature, float[] positions) {\n'
     '        Snapshot {\n'
     '            positions = positions.clone();\n'
     '        }\n\n'
     '        @Override\n'
     '        public float[] positions() {\n'
     '            return positions.clone();\n'
     '        }\n\n'
     '        Snapshot copy() {\n'
     '            return new Snapshot(signature, positions);\n'
     '        }\n'
     '    }\n',
     '    record Snapshot(Signature signature, float[] previousVertexData, int floatsPerVertex) {\n'
     '        Snapshot {\n'
     '            previousVertexData = previousVertexData.clone();\n'
     '            if (floatsPerVertex != 3 && floatsPerVertex != 7) {\n'
     '                throw new IllegalArgumentException("Unsupported previous-vertex payload width: " + floatsPerVertex);\n'
     '            }\n'
     '            if (previousVertexData.length != signature.vertexCount() * floatsPerVertex) {\n'
     '                throw new IllegalArgumentException("Previous-vertex payload length does not match signature");\n'
     '            }\n'
     '        }\n\n'
     '        Snapshot(final Signature signature, final float[] positions) {\n'
     '            this(signature, positions, 3);\n'
     '        }\n\n'
     '        @Override\n'
     '        public float[] previousVertexData() {\n'
     '            return previousVertexData.clone();\n'
     '        }\n\n'
     '        float[] positions() {\n'
     '            if (floatsPerVertex == 3) {\n'
     '                return previousVertexData();\n'
     '            }\n'
     '            float[] positions = new float[signature.vertexCount() * 3];\n'
     '            for (int vertex = 0; vertex < signature.vertexCount(); vertex++) {\n'
     '                System.arraycopy(previousVertexData, vertex * floatsPerVertex, positions, vertex * 3, 3);\n'
     '            }\n'
     '            return positions;\n'
     '        }\n\n'
     '        Snapshot copy() {\n'
     '            return new Snapshot(signature, previousVertexData, floatsPerVertex);\n'
     '        }\n'
     '    }\n')
once(p,
     '        float[] positions = extractPositions(format, buffers, vertexCount);\n'
     '        if (positions == null) {\n'
     '            return;\n'
     '        }\n'
     '        Signature signature = new Signature(\n'
     '                token.pipelineKey(),\n'
     '                format.getElements(),\n'
     '                format.getVertexSize(),\n'
     '                topology,\n'
     '                vertexCount,\n'
     '                indexCount\n'
     '        );\n'
     '        stageSnapshot(token, signature, positions);\n'
     '        if (measure) {\n'
     '            long positionBytes = Math.multiplyExact((long) vertexCount, 3L * Float.BYTES);\n'
     '            MetalFxMotionTelemetry.recordHistoryCapture(\n'
     '                    vertexCount,\n'
     '                    positionBytes,\n'
     '                    positionBytes,\n'
     '                    System.nanoTime() - cpuStartNanos\n'
     '            );\n'
     '        }\n',
     '        boolean linePayload = DefaultVertexFormat.POSITION_COLOR_NORMAL_LINE_WIDTH.equals(format);\n'
     '        float[] previousVertexData = linePayload\n'
     '                ? extractLineAttributes(format, buffers, vertexCount)\n'
     '                : extractPositions(format, buffers, vertexCount);\n'
     '        if (previousVertexData == null) {\n'
     '            return;\n'
     '        }\n'
     '        Signature signature = new Signature(\n'
     '                token.pipelineKey(),\n'
     '                format.getElements(),\n'
     '                format.getVertexSize(),\n'
     '                topology,\n'
     '                vertexCount,\n'
     '                indexCount\n'
     '        );\n'
     '        stageSnapshotData(token, signature, previousVertexData, linePayload ? 7 : 3);\n'
     '        if (measure) {\n'
     '            long sourceBytesPerVertex = linePayload ? 20L : 3L * Float.BYTES;\n'
     '            long copiedBytesPerVertex = linePayload ? 7L * Float.BYTES : 3L * Float.BYTES;\n'
     '            MetalFxMotionTelemetry.recordHistoryCapture(\n'
     '                    vertexCount,\n'
     '                    Math.multiplyExact((long) vertexCount, sourceBytesPerVertex),\n'
     '                    Math.multiplyExact((long) vertexCount, copiedBytesPerVertex),\n'
     '                    System.nanoTime() - cpuStartNanos\n'
     '            );\n'
     '        }\n')
once(p,
     '    static void stageSnapshot(\n'
     '            final @Nullable DrawToken token,\n'
     '            final Signature signature,\n'
     '            final float[] positions\n'
     '    ) {\n'
     '        if (frameOpen && token != null && signature != null && positions != null\n'
     '                && positions.length == signature.vertexCount() * 3) {\n'
     '            PENDING.put(token.key(), new Snapshot(signature, positions));\n'
     '        }\n'
     '    }\n',
     '    static void stageSnapshot(\n'
     '            final @Nullable DrawToken token,\n'
     '            final Signature signature,\n'
     '            final float[] positions\n'
     '    ) {\n'
     '        stageSnapshotData(token, signature, positions, 3);\n'
     '    }\n\n'
     '    static void stageSnapshotData(\n'
     '            final @Nullable DrawToken token,\n'
     '            final Signature signature,\n'
     '            final float[] previousVertexData,\n'
     '            final int floatsPerVertex\n'
     '    ) {\n'
     '        if (frameOpen && token != null && signature != null && previousVertexData != null\n'
     '                && (floatsPerVertex == 3 || floatsPerVertex == 7)\n'
     '                && previousVertexData.length == signature.vertexCount() * floatsPerVertex) {\n'
     '            PENDING.put(token.key(), new Snapshot(signature, previousVertexData, floatsPerVertex));\n'
     '        }\n'
     '    }\n')
once(p,
     '        return previous.positions();\n'
     '    }\n\n'
     '    static int matchingManifestDrawCount',
     '        return previous.positions();\n'
     '    }\n\n'
     '    /** Returns the packed payload selected for the previous source draw. */\n'
     '    static @Nullable float[] matchedPreviousVertexData(final @Nullable DrawToken token) {\n'
     '        if (token == null || !objectManifestMatches(token.key().object())) {\n'
     '            return null;\n'
     '        }\n'
     '        Snapshot current = PENDING.get(token.key());\n'
     '        Snapshot previous = PREVIOUS.get(token.key());\n'
     '        if (current == null || previous == null || !current.signature().equals(previous.signature())\n'
     '                || current.floatsPerVertex() != previous.floatsPerVertex()) {\n'
     '            return null;\n'
     '        }\n'
     '        return previous.previousVertexData();\n'
     '    }\n\n'
     '    static int matchingManifestDrawCount')
once(p,
     '        return writtenVertices == expectedVertexCount ? output : null;\n'
     '    }\n'
     '}\n',
     '        return writtenVertices == expectedVertexCount ? output : null;\n'
     '    }\n\n'
     '    /**\n'
     '     * Packs the previous attributes used by Minecraft 26.2 core/rendertype_lines:\n'
     '     * float3 Position, decoded float3 Normal, float LineWidth. Color is irrelevant to clip.\n'
     '     */\n'
     '    static @Nullable float[] extractLineAttributes(\n'
     '            final VertexFormat format,\n'
     '            final Iterable<ByteBuffer> buffers,\n'
     '            final int expectedVertexCount\n'
     '    ) {\n'
     '        if (!DefaultVertexFormat.POSITION_COLOR_NORMAL_LINE_WIDTH.equals(format)\n'
     '                || buffers == null || expectedVertexCount <= 0 || format.getStepRate() != 0) {\n'
     '            return null;\n'
     '        }\n'
     '        VertexFormatElement position = format.getElement("Position");\n'
     '        VertexFormatElement normal = format.getElement("Normal");\n'
     '        VertexFormatElement lineWidth = format.getElement("LineWidth");\n'
     '        if (position == null || position.format() != GpuFormat.RGB32_FLOAT\n'
     '                || normal == null || normal.format() != GpuFormat.RGBA8_SNORM\n'
     '                || lineWidth == null || lineWidth.format() != GpuFormat.R32_FLOAT) {\n'
     '            return null;\n'
     '        }\n'
     '        int stride = format.getVertexSize();\n'
     '        if (stride <= 0 || position.offset() < 0 || position.offset() + 12 > stride\n'
     '                || normal.offset() < 0 || normal.offset() + 4 > stride\n'
     '                || lineWidth.offset() < 0 || lineWidth.offset() + 4 > stride) {\n'
     '            return null;\n'
     '        }\n'
     '        float[] output = new float[Math.multiplyExact(expectedVertexCount, 7)];\n'
     '        int writtenVertices = 0;\n'
     '        for (ByteBuffer source : buffers) {\n'
     '            if (source == null) return null;\n'
     '            ByteBuffer bytes = source.duplicate().order(ByteOrder.nativeOrder());\n'
     '            int remaining = bytes.remaining();\n'
     '            if (remaining % stride != 0) return null;\n'
     '            int vertices = remaining / stride;\n'
     '            if (writtenVertices + vertices > expectedVertexCount) return null;\n'
     '            int base = bytes.position();\n'
     '            for (int vertex = 0; vertex < vertices; vertex++) {\n'
     '                int vertexBase = base + vertex * stride;\n'
     '                int po = vertexBase + position.offset();\n'
     '                int no = vertexBase + normal.offset();\n'
     '                int wo = vertexBase + lineWidth.offset();\n'
     '                float x = bytes.getFloat(po);\n'
     '                float y = bytes.getFloat(po + 4);\n'
     '                float z = bytes.getFloat(po + 8);\n'
     '                float width = bytes.getFloat(wo);\n'
     '                if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z) || !Float.isFinite(width)) {\n'
     '                    return null;\n'
     '                }\n'
     '                int out = (writtenVertices + vertex) * 7;\n'
     '                output[out] = x; output[out + 1] = y; output[out + 2] = z;\n'
     '                output[out + 3] = decodeSnorm8(bytes.get(no));\n'
     '                output[out + 4] = decodeSnorm8(bytes.get(no + 1));\n'
     '                output[out + 5] = decodeSnorm8(bytes.get(no + 2));\n'
     '                output[out + 6] = width;\n'
     '            }\n'
     '            writtenVertices += vertices;\n'
     '        }\n'
     '        return writtenVertices == expectedVertexCount ? output : null;\n'
     '    }\n\n'
     '    private static float decodeSnorm8(final byte value) {\n'
     '        return Math.max(-1.0F, value / 127.0F);\n'
     '    }\n'
     '}\n')

# Replay chooses the payload width from the verified source pipeline.
p = "src/main/java/com/metallum/client/metal/render/MetalPreviousVertexReplay.java"
once(p,
     '    record Plan(GpuBufferSlice currentVertexBuffer, int replayBaseVertex, float[] previousPositions) {\n'
     '        Plan {\n'
     '            previousPositions = previousPositions.clone();\n'
     '        }\n\n'
     '        @Override\n'
     '        public float[] previousPositions() {\n'
     '            return previousPositions.clone();\n'
     '        }\n'
     '    }\n',
     '    record Plan(GpuBufferSlice currentVertexBuffer, int replayBaseVertex, float[] previousVertexData) {\n'
     '        Plan {\n'
     '            previousVertexData = previousVertexData.clone();\n'
     '        }\n\n'
     '        @Override\n'
     '        public float[] previousVertexData() {\n'
     '            return previousVertexData.clone();\n'
     '        }\n'
     '    }\n')
once(p,
     '        float[] previousPositions = MetalPreviousVertexHistory.matchedPreviousPositions(token);\n'
     '        if (previousPositions == null || previousPositions.length < 3 || previousPositions.length % 3 != 0) {\n'
     '            return null;\n'
     '        }\n\n'
     '        int sourceStride = source.getVertexFormatBinding(0).getVertexSize();\n'
     '        long vertexCount = previousPositions.length / 3L;\n',
     '        float[] previousVertexData = MetalPreviousVertexHistory.matchedPreviousVertexData(token);\n'
     '        int floatsPerVertex = MetalEntityMotionPipeline.previousVertexFloatsPerVertex(source);\n'
     '        if (previousVertexData == null || previousVertexData.length < floatsPerVertex\n'
     '                || previousVertexData.length % floatsPerVertex != 0) {\n'
     '            return null;\n'
     '        }\n\n'
     '        int sourceStride = source.getVertexFormatBinding(0).getVertexSize();\n'
     '        long vertexCount = previousVertexData.length / (long) floatsPerVertex;\n')
once(p,
     '                previousPositions\n'
     '        );\n',
     '                previousVertexData\n'
     '        );\n')

# Upload the selected packed previous-vertex payload; buffer slot stays binding 1.
p = "src/main/java/com/metallum/client/metal/render/MetalFxManager.java"
once(p,
     '                float[] previousPositions = exactPlan.previousPositions();\n'
     '                long previousByteCount = Math.multiplyExact((long) previousPositions.length, Float.BYTES);\n',
     '                float[] previousVertexData = exactPlan.previousVertexData();\n'
     '                long previousByteCount = Math.multiplyExact((long) previousVertexData.length, Float.BYTES);\n')
once(p,
     '                    for (float value : previousPositions) {\n',
     '                    for (float value : previousVertexData) {\n')

# Fishing Hook whole-object admission, only after both entity quad and line ABIs exist.
p = "src/main/java/com/metallum/client/metal/render/MetalFxMotionEligibility.java"
once(p,
     'import net.minecraft.client.renderer.entity.state.FireworkRocketRenderState;\n',
     'import net.minecraft.client.renderer.entity.state.FireworkRocketRenderState;\nimport net.minecraft.client.renderer.entity.state.FishingHookRenderState;\n')
once(p,
     '                || state instanceof FireworkRocketRenderState\n',
     '                || state instanceof FireworkRocketRenderState\n                || state instanceof FishingHookRenderState\n')
once(p,
     '        if (state instanceof ItemFrameRenderState) {\n',
     '        if (state instanceof FishingHookRenderState) {\n'
     '            // Hook quad and all 16 string segments are CustomGeometry submits carrying the\n'
     '            // same owner. The string uses the separately verified rendertype_lines exact\n'
     '            // previous Position/Normal/LineWidth ABI; any missing draw remains fail-closed.\n'
     '            return 0;\n'
     '        }\n'
     '        if (state instanceof ItemFrameRenderState) {\n')

# Shader reproduces the official 26.2 current line extrusion and independently reconstructs
# previous unjittered extrusion from previous Position/Normal/LineWidth.
Path("src/main/resources/assets/metallum/shaders/core/rendertype_lines_previous_motion.vsh").write_text(r'''#version 330

#moj_import <minecraft:globals.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;
layout(location = 2) in vec3 Normal;
layout(location = 3) in float LineWidth;
layout(location = 4) in vec3 PreviousPosition;
layout(location = 5) in vec3 PreviousNormal;
layout(location = 6) in float PreviousLineWidth;

layout(std140) uniform MetallumMotion {
    mat4 CurrentUnjitteredFromRaster;
    mat4 PreviousFromRaster;
};

noperspective out vec2 metallumObjectMotion;
flat out float metallumObjectValidity;

const float VIEW_SHRINK = 1.0 - (1.0 / 256.0);
const mat4 VIEW_SCALE = mat4(
    VIEW_SHRINK, 0.0, 0.0, 0.0,
    0.0, VIEW_SHRINK, 0.0, 0.0,
    0.0, 0.0, VIEW_SHRINK, 0.0,
    0.0, 0.0, 0.0, 1.0
);

bool finiteClip(vec4 clip) {
    return !any(isnan(clip)) && !any(isinf(clip)) && clip.w > 1.0e-6;
}

vec4 extrudeLine(vec4 startClip, vec4 endClip, float width) {
    if (!finiteClip(startClip) || !finiteClip(endClip) || !isfinite(width)) {
        return vec4(0.0);
    }
    vec3 ndc1 = startClip.xyz / startClip.w;
    vec3 ndc2 = endClip.xyz / endClip.w;
    vec2 delta = (ndc2.xy - ndc1.xy) * ScreenSize;
    float deltaLength = length(delta);
    if (!isfinite(deltaLength) || deltaLength <= 1.0e-6) {
        return vec4(0.0);
    }
    vec2 lineScreenDirection = delta / deltaLength;
    vec2 lineOffset = vec2(-lineScreenDirection.y, lineScreenDirection.x) * width / ScreenSize;
    if (lineOffset.x < 0.0) lineOffset *= -1.0;
    vec3 ndc = ndc1 + (gl_VertexID % 2 == 0 ? vec3(lineOffset, 0.0) : -vec3(lineOffset, 0.0));
    return vec4(ndc * startClip.w, startClip.w);
}

void main() {
    // Keep rasterization byte-for-byte equivalent in intent to Minecraft 26.2 rendertype_lines.
    vec4 rasterStart = ProjMat * VIEW_SCALE * ModelViewMat * vec4(Position, 1.0);
    vec4 rasterEnd = ProjMat * VIEW_SCALE * ModelViewMat * vec4(Position + Normal, 1.0);
    vec4 rasterClip = extrudeLine(rasterStart, rasterEnd, LineWidth);
    gl_Position = rasterClip;

    // CurrentUnjitteredFromRaster removes the source-frame projection jitter after the exact
    // current raster extrusion. Previous staged positions/normals are already pose transformed.
    vec4 currentClip = CurrentUnjitteredFromRaster * rasterClip;
    vec4 previousStart = PreviousFromRaster * VIEW_SCALE * vec4(PreviousPosition, 1.0);
    vec4 previousEnd = PreviousFromRaster * VIEW_SCALE * vec4(PreviousPosition + PreviousNormal, 1.0);
    vec4 previousClip = extrudeLine(previousStart, previousEnd, PreviousLineWidth);

    bool valid = finiteClip(rasterClip) && finiteClip(currentClip) && finiteClip(previousClip);
    if (valid) {
        vec2 currentNdc = currentClip.xy / currentClip.w;
        vec2 previousNdc = previousClip.xy / previousClip.w;
        vec2 motion = vec2(previousNdc.x - currentNdc.x, currentNdc.y - previousNdc.y);
        valid = !any(isnan(currentNdc)) && !any(isinf(currentNdc))
            && !any(isnan(previousNdc)) && !any(isinf(previousNdc))
            && !any(isnan(motion)) && !any(isinf(motion))
            && all(lessThanEqual(abs(motion), vec2(32.0)));
        metallumObjectMotion = valid ? motion : vec2(0.0);
    } else {
        metallumObjectMotion = vec2(0.0);
    }
    metallumObjectValidity = valid ? 1.0 : 0.0;
}
''')
Path("src/main/resources/assets/metallum/shaders/core/rendertype_lines_previous_motion.fsh").write_text(r'''#version 330

noperspective in vec2 metallumObjectMotion;
flat in float metallumObjectValidity;

layout(location = 0) out vec2 metallumMotionTarget;
layout(location = 1) out float metallumValidityTarget;

void main() {
    metallumMotionTarget = metallumObjectMotion;
    metallumValidityTarget = metallumObjectValidity;
}
''')

# Tests.
p = "src/test/java/com/metallum/client/metal/render/MetalEntityAuxiliaryMotionPipelineTest.java"
once(p,
     '    @Test\n    void wrongLayoutsRemainFailClosed() {\n',
     '    @Test\n'
     '    void lineUsesItsVerifiedPreviousPositionNormalWidthAbi() {\n'
     '        RenderPipeline line = pipeline("core/rendertype_lines", DefaultVertexFormat.POSITION_COLOR_NORMAL_LINE_WIDTH, PrimitiveTopology.LINES);\n'
     '        assertTrue(MetalEntityMotionPipeline.supportsPreviousPositions(line));\n'
     '        assertEquals(7, MetalEntityMotionPipeline.previousVertexFloatsPerVertex(line));\n'
     '        assertEquals(28, MetalEntityMotionPipeline.previousVertexFormat(line).getVertexSize());\n'
     '        assertEquals("core/rendertype_lines_previous_motion", MetalEntityMotionPipeline.forPreviousPositions(line).getVertexShader().getPath());\n'
     '        assertEquals("core/rendertype_lines_previous_motion", MetalEntityMotionPipeline.forPreviousPositions(line).getFragmentShader().getPath());\n'
     '        assertFalse(MetalEntityMotionPipeline.supportsPreviousPositions(\n'
     '                pipeline("core/rendertype_lines", DefaultVertexFormat.POSITION_COLOR_NORMAL_LINE_WIDTH, PrimitiveTopology.QUADS)));\n'
     '    }\n\n'
     '    @Test\n    void wrongLayoutsRemainFailClosed() {\n')
once(p,
     '    private static RenderPipeline pipeline(final String shader, final VertexFormat format, final String... defines) {\n'
     '        Identifier shaderId = Identifier.fromNamespaceAndPath("minecraft", shader);\n'
     '        RenderPipeline.Builder builder = RenderPipeline.builder()\n'
     '                .withLocation(Identifier.fromNamespaceAndPath("metallum", "test/" + shader.replace(\'/\', \'_\') + "/" + format.getVertexSize()))\n'
     '                .withVertexShader(shaderId)\n'
     '                .withFragmentShader(shaderId)\n'
     '                .withPrimitiveTopology(PrimitiveTopology.QUADS)\n'
     '                .withVertexBinding(0, format);\n',
     '    private static RenderPipeline pipeline(final String shader, final VertexFormat format, final String... defines) {\n'
     '        return pipeline(shader, format, PrimitiveTopology.QUADS, defines);\n'
     '    }\n\n'
     '    private static RenderPipeline pipeline(\n'
     '            final String shader, final VertexFormat format, final PrimitiveTopology topology, final String... defines\n'
     '    ) {\n'
     '        Identifier shaderId = Identifier.fromNamespaceAndPath("minecraft", shader);\n'
     '        RenderPipeline.Builder builder = RenderPipeline.builder()\n'
     '                .withLocation(Identifier.fromNamespaceAndPath("metallum", "test/" + shader.replace(\'/\', \'_\') + "/" + format.getVertexSize() + "/" + topology.name().toLowerCase()))\n'
     '                .withVertexShader(shaderId)\n'
     '                .withFragmentShader(shaderId)\n'
     '                .withPrimitiveTopology(topology)\n'
     '                .withVertexBinding(0, format);\n')

p = "src/test/java/com/metallum/client/metal/render/MetalPreviousVertexHistoryTest.java"
once(p,
     'import static org.junit.jupiter.api.Assertions.assertEquals;\n',
     'import static org.junit.jupiter.api.Assertions.assertArrayEquals;\nimport static org.junit.jupiter.api.Assertions.assertEquals;\n')
once(p,
     '    @Test\n    void previousPositionBindingIsPackedFloat3() {\n',
     '    @Test\n'
     '    void lineHistoryPacksPreviousPositionNormalAndWidthExactly() {\n'
     '        VertexFormat format = DefaultVertexFormat.POSITION_COLOR_NORMAL_LINE_WIDTH;\n'
     '        ByteBuffer buffer = ByteBuffer.allocate(format.getVertexSize()).order(ByteOrder.nativeOrder());\n'
     '        int positionOffset = format.getElement("Position").offset();\n'
     '        int normalOffset = format.getElement("Normal").offset();\n'
     '        int widthOffset = format.getElement("LineWidth").offset();\n'
     '        buffer.putFloat(positionOffset, 1.25F);\n'
     '        buffer.putFloat(positionOffset + 4, -2.5F);\n'
     '        buffer.putFloat(positionOffset + 8, 4.0F);\n'
     '        buffer.put(normalOffset, (byte) 127);\n'
     '        buffer.put(normalOffset + 1, (byte) 0);\n'
     '        buffer.put(normalOffset + 2, (byte) -128);\n'
     '        buffer.putFloat(widthOffset, 2.5F);\n'
     '        buffer.position(0);\n'
     '        buffer.limit(format.getVertexSize());\n'
     '        assertArrayEquals(new float[] {1.25F, -2.5F, 4.0F, 1.0F, 0.0F, -1.0F, 2.5F},\n'
     '                MetalPreviousVertexHistory.extractLineAttributes(format, List.of(buffer)), 0.0F);\n'
     '    }\n\n'
     '    @Test\n    void previousPositionBindingIsPackedFloat3() {\n')
# Fix call signature if test source has three-arg extractor only; exact anchor above intentionally uses explicit count next.
once(p,
     'MetalPreviousVertexHistory.extractLineAttributes(format, List.of(buffer)), 0.0F);',
     'MetalPreviousVertexHistory.extractLineAttributes(format, List.of(buffer), 1), 0.0F);')

p = "src/test/java/com/metallum/client/metal/render/MetalFxMotionEligibilityTest.java"
once(p,
     'import net.minecraft.client.renderer.entity.state.FireworkRocketRenderState;\n',
     'import net.minecraft.client.renderer.entity.state.FireworkRocketRenderState;\nimport net.minecraft.client.renderer.entity.state.FishingHookRenderState;\n')
once(p,
     '    @Test\n    void itemFrameIsWholeObjectExactCandidate() {\n',
     '    @Test\n'
     '    void fishingHookIsWholeObjectExactCandidate() {\n'
     '        FishingHookRenderState hook = new FishingHookRenderState();\n'
     '        assertEquals(0, MetalFxMotionEligibility.incompleteEntityReason(hook));\n'
     '        assertTrue(MetalFxMotionEligibility.requiresExactPreviousPositions(hook));\n'
     '    }\n\n'
     '    @Test\n    void itemFrameIsWholeObjectExactCandidate() {\n')

# Resource-level shader contract: previous direction and width must participate in previous clip.
test = Path("src/test/java/com/metallum/client/metal/render/MetalLinePreviousMotionShaderTest.java")
test.write_text(r'''package com.metallum.client.metal.render;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class MetalLinePreviousMotionShaderTest {
    @Test
    void shaderReconstructsBothCurrentAndPreviousMinecraftLineExtrusion() throws IOException {
        String path = "/assets/metallum/shaders/core/rendertype_lines_previous_motion.vsh";
        try (var in = MetalLinePreviousMotionShaderTest.class.getResourceAsStream(path)) {
            if (in == null) throw new AssertionError("Missing " + path);
            String shader = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(shader.contains("ProjMat * VIEW_SCALE * ModelViewMat * vec4(Position, 1.0)"));
            assertTrue(shader.contains("Position + Normal"));
            assertTrue(shader.contains("PreviousPosition + PreviousNormal"));
            assertTrue(shader.contains("PreviousLineWidth"));
            assertTrue(shader.contains("gl_VertexID % 2"));
            assertTrue(shader.contains("PreviousFromRaster * VIEW_SCALE"));
        }
    }
}
''')

print("Fishing Hook exact line-motion patch applied")

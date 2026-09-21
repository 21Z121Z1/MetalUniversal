from pathlib import Path

patcher = Path(".github/scripts/apply_fishing_hook_line_exact_motion.py")
source = patcher.read_text()

def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one anchor, got {count}")
    return text.replace(old, new, 1)

source = replace_once(
    source,
    '''once(p,
     '                || state instanceof FireworkRocketRenderState\\n',
     '                || state instanceof FireworkRocketRenderState\\n                || state instanceof FishingHookRenderState\\n')
''',
    '''once(p,
     '                || state instanceof ThrownItemRenderState\\n'
     '                || state instanceof FireworkRocketRenderState\\n'
     '                || state instanceof ItemClusterRenderState\\n',
     '                || state instanceof ThrownItemRenderState\\n'
     '                || state instanceof FireworkRocketRenderState\\n'
     '                || state instanceof FishingHookRenderState\\n'
     '                || state instanceof ItemClusterRenderState\\n')
''',
    "eligibility exact-required anchor",
)

source = replace_once(
    source,
    '''     '            ColorTargetState target = source.getColorTargetState();\\n'
     '            return target != null && target.blendFunction().isEmpty() ? PreviousFamily.LINE : null;\\n'
''',
    '''     '            return PreviousFamily.LINE;\\n'
''',
    "line blend gate",
)

source = replace_once(
    source,
    '''     '        boolean linePayload = DefaultVertexFormat.POSITION_COLOR_NORMAL_LINE_WIDTH.equals(format);\\n'
''',
    '''     '        boolean linePayload = DefaultVertexFormat.POSITION_COLOR_NORMAL_LINE_WIDTH.equals(format)\\n'
     '                && topology == PrimitiveTopology.LINES;\\n'
''',
    "line payload topology gate",
)

source = replace_once(
    source,
    '''once(p,
     'import static org.junit.jupiter.api.Assertions.assertEquals;\\n',
     'import static org.junit.jupiter.api.Assertions.assertArrayEquals;\\nimport static org.junit.jupiter.api.Assertions.assertEquals;\\n')
''',
    '''once(p,
     'import com.mojang.blaze3d.vertex.VertexFormat;\\n',
     'import com.mojang.blaze3d.vertex.DefaultVertexFormat;\\nimport com.mojang.blaze3d.vertex.VertexFormat;\\n')
''',
    "history test import",
)
source = source.replace(
    "void previousPositionBindingIsPackedFloat3()",
    "void compactPreviousPositionBindingMatchesConfirmedMinecraftAbi()",
)

exec(compile(source, str(patcher), "exec"), {"__name__": "__main__"})

shader = Path("src/main/resources/assets/metallum/shaders/core/rendertype_lines_previous_motion.vsh")
shader.write_text(r'''#version 330

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

bool finiteVec2(vec2 value) {
    return !any(isnan(value)) && !any(isinf(value));
}

bool finiteVec3(vec3 value) {
    return !any(isnan(value)) && !any(isinf(value));
}

bool finiteClip(vec4 clip) {
    return !any(isnan(clip)) && !any(isinf(clip)) && clip.w > 1.0e-6;
}

bool previousLineClip(out vec4 clip) {
    clip = vec4(0.0);
    if (!finiteVec3(PreviousPosition) || !finiteVec3(PreviousNormal)
            || isnan(PreviousLineWidth) || isinf(PreviousLineWidth)) {
        return false;
    }

    vec4 linePosStart = PreviousFromRaster * VIEW_SCALE * vec4(PreviousPosition, 1.0);
    vec4 linePosEnd = PreviousFromRaster * VIEW_SCALE * vec4(PreviousPosition + PreviousNormal, 1.0);
    if (!finiteClip(linePosStart) || !finiteClip(linePosEnd)) {
        return false;
    }

    vec3 ndc1 = linePosStart.xyz / linePosStart.w;
    vec3 ndc2 = linePosEnd.xyz / linePosEnd.w;
    vec2 delta = (ndc2.xy - ndc1.xy) * ScreenSize;
    float deltaLength = length(delta);
    if (!finiteVec2(delta) || isnan(deltaLength) || isinf(deltaLength) || deltaLength <= 1.0e-6) {
        return false;
    }

    vec2 lineScreenDirection = delta / deltaLength;
    vec2 lineOffset = vec2(-lineScreenDirection.y, lineScreenDirection.x)
            * PreviousLineWidth / ScreenSize;
    if (!finiteVec2(lineOffset)) {
        return false;
    }
    if (lineOffset.x < 0.0) {
        lineOffset *= -1.0;
    }

    vec3 previousNdc = ndc1;
    if (gl_VertexID % 2 == 0) {
        previousNdc += vec3(lineOffset, 0.0);
    } else {
        previousNdc -= vec3(lineOffset, 0.0);
    }
    clip = vec4(previousNdc * linePosStart.w, linePosStart.w);
    return finiteClip(clip);
}

void main() {
    vec4 linePosStart = ProjMat * VIEW_SCALE * ModelViewMat * vec4(Position, 1.0);
    vec4 linePosEnd = ProjMat * VIEW_SCALE * ModelViewMat * vec4(Position + Normal, 1.0);

    vec3 ndc1 = linePosStart.xyz / linePosStart.w;
    vec3 ndc2 = linePosEnd.xyz / linePosEnd.w;

    vec2 lineScreenDirection = normalize((ndc2.xy - ndc1.xy) * ScreenSize);
    vec2 lineOffset = vec2(-lineScreenDirection.y, lineScreenDirection.x) * LineWidth / ScreenSize;

    if (lineOffset.x < 0.0) {
        lineOffset *= -1.0;
    }

    if (gl_VertexID % 2 == 0) {
        gl_Position = vec4((ndc1 + vec3(lineOffset, 0.0)) * linePosStart.w, linePosStart.w);
    } else {
        gl_Position = vec4((ndc1 - vec3(lineOffset, 0.0)) * linePosStart.w, linePosStart.w);
    }

    vec4 currentClip = CurrentUnjitteredFromRaster * gl_Position;
    vec4 previousClip;
    bool valid = finiteClip(gl_Position)
            && finiteClip(currentClip)
            && previousLineClip(previousClip);

    if (valid) {
        vec2 currentNdc = currentClip.xy / currentClip.w;
        vec2 previousNdc = previousClip.xy / previousClip.w;
        vec2 motion = vec2(
            previousNdc.x - currentNdc.x,
            currentNdc.y - previousNdc.y
        );
        valid = finiteVec2(currentNdc)
                && finiteVec2(previousNdc)
                && finiteVec2(motion)
                && all(lessThanEqual(abs(motion), vec2(32.0)));
        metallumObjectMotion = valid ? motion : vec2(0.0);
    } else {
        metallumObjectMotion = vec2(0.0);
    }
    metallumObjectValidity = valid ? 1.0 : 0.0;
}
''')

test = Path("src/test/java/com/metallum/client/metal/render/MetalLinePreviousMotionShaderTest.java")
text = test.read_text()
needle = '            assertTrue(shader.contains("PreviousLineWidth"));\\n'
if text.count(needle) != 1:
    raise SystemExit(f"shader test anchor count={text.count(needle)}")
text = text.replace(
    needle,
    needle
    + '            assertTrue(shader.contains("vec4 linePosStart = ProjMat * VIEW_SCALE * ModelViewMat * vec4(Position, 1.0)"));\\n'
    + '            assertTrue(shader.contains("normalize((ndc2.xy - ndc1.xy) * ScreenSize)"));\\n'
    + '            assertTrue(shader.contains("CurrentUnjitteredFromRaster * gl_Position"));\\n'
    + '            assertTrue(shader.contains("previousLineClip(previousClip)"));\\n',
    1,
)
test.write_text(text)

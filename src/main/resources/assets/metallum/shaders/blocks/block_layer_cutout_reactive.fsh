#version 330 core

#moj_import <sodium:globals.glsl>
#moj_import <sodium:fog.glsl>
#moj_import <sodium:chunk_material.glsl>

in vec4 v_Color;
in vec2 v_TexCoord;
in vec2 v_FragDistance;
in float fadeFactor;

uniform sampler2D u_BlockTex;

layout(location = 0) out vec4 fragColor;
layout(location = 1) out vec4 metallumCutoutCoverage;

vec4 sampleNearest(sampler2D source, vec2 uv, vec2 pixelSize, vec2 du, vec2 dv, vec2 texelScreenSize) {
    vec2 uvTexelCoords = uv / pixelSize;
    vec2 texelCenter = round(uvTexelCoords) - 0.5f;
    vec2 texelOffset = uvTexelCoords - texelCenter;
    texelOffset = (texelOffset - 0.5f) * pixelSize / texelScreenSize + 0.5f;
    texelOffset = clamp(texelOffset, 0.0f, 1.0f);
    uv = (texelCenter + texelOffset) * pixelSize;
    return textureGrad(source, uv, du, dv);
}

vec4 sampleNearest(sampler2D source, vec2 uv, vec2 pixelSize) {
    vec2 du = dFdx(uv);
    vec2 dv = dFdy(uv);
    vec2 texelScreenSize = sqrt(du * du + dv * dv);
    return sampleNearest(source, uv, pixelSize, du, dv, texelScreenSize);
}

vec4 sampleRGSS(sampler2D source, vec2 uv, vec2 pixelSize) {
    vec2 du = dFdx(uv);
    vec2 dv = dFdy(uv);
    vec2 texelScreenSize = sqrt(du * du + dv * dv);
    float maxTexelSize = max(texelScreenSize.x, texelScreenSize.y);
    float minPixelSize = min(pixelSize.x, pixelSize.y);
    float transitionStart = minPixelSize;
    float transitionEnd = minPixelSize * 2.0;
    float blendFactor = smoothstep(transitionStart, transitionEnd, maxTexelSize);
    float duLength = length(du);
    float dvLength = length(dv);
    float effectiveDerivative = sqrt(min(duLength, dvLength) * max(duLength, dvLength));
    float mipLevelExact = max(0.0, log2(effectiveDerivative / minPixelSize));
    const vec2 offsets[4] = vec2[](
        vec2(0.125, 0.375),
        vec2(-0.125, -0.375),
        vec2(0.375, -0.125),
        vec2(-0.375, 0.125)
    );
    vec4 rgssColor = vec4(0.0);
    for (int i = 0; i < 4; ++i) {
        rgssColor += textureLod(source, uv + offsets[i] * pixelSize, mipLevelExact);
    }
    rgssColor *= 0.25;
    vec4 nearestColor = sampleNearest(source, uv, pixelSize, du, dv, texelScreenSize);
    return mix(nearestColor, rgssColor, blendFactor);
}

void main() {
    vec4 color = u_UseRGSS
        ? sampleRGSS(u_BlockTex, v_TexCoord, u_TexelSize)
        : sampleNearest(u_BlockTex, v_TexCoord, u_TexelSize);

#ifdef METALLUM_STABLE_ALPHA
    // Temporal-upscaling stabilization: nearest-path texel snapping makes the
    // sampled alpha flip by whole texels under subpixel camera jitter in the
    // 1-2 texels-per-pixel minification zone. Blending toward plain trilinear
    // as minification starts makes both the alpha-test signal and the
    // surviving color vary continuously with jitter, which temporal
    // accumulation can resolve. Magnified (close-up) texels keep the vanilla
    // nearest look; the smoothstep window matches sampleRGSS's transition.
    vec2 du = dFdx(v_TexCoord);
    vec2 dv = dFdy(v_TexCoord);
    vec2 texelScreenSize = sqrt(du * du + dv * dv);
    float maxTexelSize = max(texelScreenSize.x, texelScreenSize.y);
    float minPixelSize = min(u_TexelSize.x, u_TexelSize.y);
    float minified = smoothstep(minPixelSize, 2.0 * minPixelSize, maxTexelSize);
    if (minified > 0.0) {
        color = mix(color, textureGrad(u_BlockTex, v_TexCoord, du, dv), minified);
    }
#endif
    color *= v_Color;

#ifdef ALPHA_CUTOUT
    if (color.a < ALPHA_CUTOUT) {
        discard;
    }
#endif

    fragColor = _linearFog(
        color,
        v_FragDistance,
        u_FogColor,
        u_EnvironmentFog,
        u_RenderFog,
        fadeFactor
    );
    // This executes only for the exact samples that survived the scene-color
    // alpha test above. The reactive dilation pass classifies the coverage
    // into interior vs edge band; see
    // docs/cutout-shimmer-remediation-2026-07-27.md.
    metallumCutoutCoverage = vec4(1.0, 0.0, 0.0, 0.0);
}

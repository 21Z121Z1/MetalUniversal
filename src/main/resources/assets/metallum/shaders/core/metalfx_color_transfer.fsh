#version 330
#extension GL_ARB_separate_shader_objects : enable

uniform sampler2D InputColor;
layout(location = 0) out vec4 fragColor;

void main() {
    vec4 color = texelFetch(InputColor, ivec2(gl_FragCoord.xy), 0);
#ifdef METALLUM_SRGB_DECODE
    vec3 low = color.rgb / 12.92;
    vec3 high = pow((color.rgb + 0.055) / 1.055, vec3(2.4));
    color.rgb = mix(high, low, lessThanEqual(color.rgb, vec3(0.04045)));
#else
    // Clamp SDR overshoot before pow, including negative linear values.
    color.rgb = clamp(color.rgb, 0.0, 1.0);
    vec3 low = color.rgb * 12.92;
    vec3 high = 1.055 * pow(color.rgb, vec3(1.0 / 2.4)) - 0.055;
    color.rgb = mix(high, low, lessThanEqual(color.rgb, vec3(0.0031308)));
#endif
    fragColor = color;
}

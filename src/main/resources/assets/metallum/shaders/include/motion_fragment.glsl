#ifndef METALLUM_MOTION_FRAGMENT_GLSL
#define METALLUM_MOTION_FRAGMENT_GLSL

layout(location = 0) in vec4 metallumCurrentClip;
layout(location = 1) in vec4 metallumPreviousClip;

struct MetallumMotionSample {
    vec2 motion;
    float previousDepth;
    float validity;
};

MetallumMotionSample metallumMotionSample() {
    MetallumMotionSample result;
    result.motion = vec2(0.0);
    result.previousDepth = 0.0;
    result.validity = 0.0;
    if (!(metallumCurrentClip.w > 1.0e-6) || !(metallumPreviousClip.w > 1.0e-6)
            || any(isnan(metallumCurrentClip)) || any(isinf(metallumCurrentClip))
            || any(isnan(metallumPreviousClip)) || any(isinf(metallumPreviousClip))) return result;

    vec3 currentNdc = metallumCurrentClip.xyz / metallumCurrentClip.w;
    vec3 previousNdc = metallumPreviousClip.xyz / metallumPreviousClip.w;
    vec2 motion = vec2(previousNdc.x - currentNdc.x, currentNdc.y - previousNdc.y);
    if (any(isnan(currentNdc)) || any(isinf(currentNdc))
            || any(isnan(previousNdc)) || any(isinf(previousNdc))
            // There is no previous source sample outside its clip volume. In
            // particular w close to zero can amplify interpolation error into
            // huge off-screen vectors; these are disocclusions, not valid flow.
            || any(greaterThan(abs(previousNdc.xy), vec2(1.0)))
            || previousNdc.z < 0.0 || previousNdc.z > 1.0
            || any(isnan(motion)) || any(isinf(motion))
            || any(greaterThan(abs(motion), vec2(32.0)))) return result;
    result.motion = motion;
    result.previousDepth = previousNdc.z;
    result.validity = 1.0;
    return result;
}

#endif

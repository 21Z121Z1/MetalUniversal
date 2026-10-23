#ifndef METALLUM_MOTION_VERTEX_GLSL
#define METALLUM_MOTION_VERTEX_GLSL

// Preserve homogeneous coordinates until fragment interpolation. Dividing by w at each
// vertex and then interpolating screen-space velocity loses perspective correspondence.
layout(location = 0) out vec4 metallumCurrentClip;
layout(location = 1) out vec4 metallumPreviousClip;
layout(location = 5) noperspective out float metallumHistoryValidity;

void metallumSetClipHistory(vec4 currentClip, vec4 previousClip) {
    metallumCurrentClip = currentClip;
    metallumPreviousClip = previousClip;
#ifdef METALLUM_PREVIOUS_VALIDITY
    metallumHistoryValidity = PreviousValidity;
#else
    metallumHistoryValidity = 1.0;
#endif
}

#endif

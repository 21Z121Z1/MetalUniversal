# Terrain ICB resource-contract addendum

Branch: `feature/mobilegl-inspired-hotpath`

This addendum supersedes the terrain-ICB enablement lanes in
`mobilegl-command-packets-terrain-icb-agent-guide.md`.

## Why a second gate is required

The current Metal 3 Sodium terrain pipeline still supplies sampled textures and
samplers through ordinary render-encoder bindings. The pilot ICB descriptor
inherits pipeline state and buffer bindings from the parent encoder, but this is
not yet a complete formal contract for every non-buffer shader resource used by
terrain.

The production direction is to move the terrain material/resource set into a
stable Metal argument buffer or another ICB-compatible generated binding table.
Until that exists, the current direct-resource behavior is a diagnostic probe,
not an admitted renderer path.

## Actual runtime gates

```text
# Enables the Sodium scope marker, native feature negotiation, and ICB pilot
# code. By itself it does not execute an ICB.
-Dmetallum.opt.terrainIcbPilot=true

# Explicitly acknowledges the incomplete direct texture/sampler resource
# contract and permits ICB execution for framebuffer and Metal-validation
# experiments only.
-Dmetallum.opt.terrainIcbDirectResourceProbe=true
```

The ICB executes only when both properties are true and every other admission
condition holds:

```text
Metal 3 path
Sodium DefaultChunkRenderer scope
native indexed multi-draw admission
instanceCount > 0
terrainIcbMinDraws <= emittedDraws <= 16,384
feature ID 6 negotiated
complete native range validation succeeds
```

With only `terrainIcbPilot=true`, ordinary native multi-draw remains the draw
path. This lane is useful for proving that the conditional Mixin, scope marker,
feature table, and fallback path do not perturb rendering.

## Corrected validation lanes

```text
I0  terrainIcbPilot=false
    terrainIcbDirectResourceProbe=false
    nativeMultiDrawBatch=true
    metal4=false

I1  terrainIcbPilot=true
    terrainIcbDirectResourceProbe=false
    nativeMultiDrawBatch=true
    metal4=false

    Expected:
    - scope Mixin applies;
    - no ICB attempt or execution;
    - output equals I0;
    - ordinary native multi-draw remains active.

I2  terrainIcbPilot=true
    terrainIcbDirectResourceProbe=true
    nativeMultiDrawBatch=true
    metal4=false

    Purpose:
    - dedicated graphics-correctness experiment only;
    - enable Metal API Validation and shader validation;
    - collect fixed-camera framebuffer hashes and image diffs;
    - reject immediately on missing textures, invalid resources, blank terrain,
      command-buffer errors, or any ICB validation message.
```

Do not include I2 in performance comparisons until it has passed the full
correctness suite. A fresh ICB is currently allocated and populated for every
admitted batch, so the pilot is not representative of the intended persistent
ICB architecture.

## Required telemetry interpretation

```text
I1:
terrainIcbAttempts  = 0
terrainIcbAccepted  = 0
terrainIcbFallbacks = 0

I2:
terrainIcbAttempts  > 0 for a sufficiently large terrain scene
terrainIcbAccepted  may be > 0 only after native validation succeeds
terrainIcbFallbacks records zero-execution native rejection
```

An I2 run with accepted commands requires framebuffer and Metal-validation
evidence. It must not be described as successful solely because the accepted
counter increased.

## Formal next architecture

After the direct-resource probe establishes whether inherited parent state is
otherwise viable:

1. compile a terrain argument-buffer layout from the generated pipeline;
2. store textures, samplers, uniform addresses, and material constants in that
   argument buffer;
3. bind the argument buffer through the parent encoder or each indirect command
   according to the final shader ABI;
4. retain every referenced resource for the complete ICB lifetime;
5. allocate persistent ICBs per stable region/pass identity;
6. rewrite only dirty command ranges;
7. then evaluate GPU-generated visibility and command data.

Do not remove `terrainIcbDirectResourceProbe` or make it default-on before this
resource contract is complete and validated.
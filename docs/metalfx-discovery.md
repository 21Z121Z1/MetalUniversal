# MetalFX Discovery

The project targets Minecraft 26.2 on macOS Metal. The Apple M1 Pro test
device reports both spatial and temporal MetalFX support.

Minecraft 26.2's `improvedTransparency` screen-shader path exposes these
separate color targets when shader transparency is enabled:

- `translucent`: glass, water, and translucent terrain
- `itemEntity`: dropped items and item-like entities
- `particles`: particle effects
- `weather`: rain and snow
- `clouds`: cloud rendering

Leaves and grass use alpha cutout rendering in the opaque terrain group. They
do not have a separate translucent color target. Their discontinuous depth
edges are covered by a conservative 3x3 depth-boundary reactive response in
the motion reconstruction pass, including the cleared-depth side of the edge.

The temporal path reads the five optional targets in one Metal compute pass,
writes a per-pixel `R8_UNORM` reactive mask, then merges it with depth-edge and
invalid-reconstruction handling before the MetalFX scaler runs. The frame-graph pass is
scheduled after the transparency post chain and before the always-on-top pass,
so the source targets remain alive until the mask has been generated.

The Java 26.2 client does not contain the Bedrock/other-client `Vibrant Visuals`
option name. Compatibility testing for this repository therefore uses Mojang's
Java `improvedTransparency` path, with `graphicsPreset` left at `custom` so the
official transparency setting is not overwritten by a preset.

Sodium 0.9's public `sodium:config_api_user` entrypoint is now used for the
MetalFX settings page. Mode, 50/67/100% scene scale, transparent-target
reactivity, and frame generation are persisted outside Sodium's private option
classes and require a full game restart. This matches the actual renderer
lifetime: the main scene target is constructed during `GameRenderer` creation,
while the native MetalFX scaler is cached by device/format/dimensions.

The current render backend creates samplers from Minecraft's `GpuSampler`
contract. That contract carries filtering, anisotropy, and max LOD, but no
negative LOD bias. The cross-shader path is generated from SPIR-V at runtime;
there is no material-only sampling hook where a negative bias can be applied
without rewriting generated MSL. MIP bias is therefore documented as an
unimplemented quality optimization rather than approximated with a global
shader rewrite.

On 2026-07-26 the official Java transparency path was exercised with
`improvedTransparency:true` and `graphicsPreset:"custom"`. OFF, Spatial 0.67,
Temporal 0.67, and Temporal 0.67 with frame generation all entered `New World`.
The temporal runs reported all five transparency targets and produced no new
crash report. This validates resource routing and command-buffer lifetime for
Mojang's Java path; it is not a claim that the Bedrock `Vibrant Visuals` feature
is present in Java 26.2.

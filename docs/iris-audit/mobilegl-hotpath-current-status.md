# MobileGL-inspired hot path: current status

Branch: `feature/mobilegl-inspired-hotpath`

Base: `integration/iris-metal-next`

This file overrides the phase-status language in older planning sections. The
architecture document remains useful for rationale and prior layers, but the
current implementation/debug authority is:

- `mobilegl-inspired-hotpath-redesign.md` — original architecture and completed
  state-shadow/token/frame-arena work;
- `mobilegl-command-packets-terrain-icb-agent-guide.md` — authoritative guide for
  render command packets, compute command packets, debugging, validation, and
  agent handoff;
- `mobilegl-terrain-icb-resource-contract-addendum.md` — authoritative override
  for terrain-ICB enablement, direct texture/sampler risk, and corrected ICB
  validation lanes.

## Implemented but not yet validated

- ordered render state + draw command packet, feature ID 4;
- ordered compute state + dispatch command packet, feature ID 5;
- Metal 3 Sodium terrain indexed-draw ICB pilot, feature ID 6;
- full-packet native prevalidation;
- fail-stop behavior for ambiguous draw/dispatch execution;
- legacy replay only for proven zero-execution rejection;
- render validation/debug boundaries;
- compute fence/end boundaries;
- packet/ICB telemetry and periodic log reports;
- Java packet-layout and terrain-scope tests;
- default-off Mixin gating for render packet and terrain scope;
- a second default-off `terrainIcbDirectResourceProbe` gate, required before the
  current direct texture/sampler terrain path may execute ICB commands.

## Not proven

No claim is made yet for:

- Java compilation;
- Mixin application;
- Swift compilation/linking;
- feature negotiation at runtime;
- Metal API validation;
- framebuffer equivalence;
- Iris semantic equivalence;
- FPS, CPU encode, frame-time tail, allocation, or FFM improvement.

## Required next action

Follow `mobilegl-command-packets-terrain-icb-agent-guide.md` from Phase 0 and
apply the terrain addendum whenever testing ICB. Fix compile and Mixin errors
before changing architecture. Enable exactly one new feature per correctness
lane. Do not enable all experiments together for the first run.
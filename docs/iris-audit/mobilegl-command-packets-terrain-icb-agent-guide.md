# Agent guide: render command packets, compute command packets, and terrain ICB

Branch: `feature/mobilegl-inspired-hotpath`

Base: `integration/iris-metal-next`

Status: implementation scaffold complete; build, Mixin application, native ABI,
physical-GPU correctness, and performance are **not yet proven**.

This document is the authoritative handoff for another agent. Do not infer that
an implementation is admitted merely because it compiles. Every experimental
path is default-off and must pass its own isolated correctness and performance
lanes.

---

## 1. Objective and boundaries

The current Metal submission path has already removed many redundant Java state
changes, but direct draws and compute dispatches still cross Java/FFM one at a
time. This phase adds three independent experiments:

1. **Render command packet**
   - ordered state changes plus direct/indirect draws;
   - one ordinary Java-to-native call for multiple operations;
   - preserves original operation order;
   - default-off.

2. **Compute command packet**
   - ordered pipeline/resource changes plus direct/indirect dispatches;
   - one ordinary Java-to-native call for multiple operations;
   - flushes before fences and encoder end;
   - default-off.

3. **Sodium terrain ICB pilot**
   - converts an already-admitted indexed terrain multi-draw batch into one
     Metal 3 `MTLIndirectCommandBuffer` execution;
   - inherits parent render-pipeline and buffer state;
   - scopes admission to `DefaultChunkRenderer.render`;
   - default-off;
   - correctness pilot only, not the final persistent/GPU-generated ICB design.

The implementation does **not** yet add:

- GPU culling;
- GPU-written ICB commands;
- persistent per-region ICBs;
- Metal 4 ICB execution;
- compute ICBs;
- Iris composite/post-chain ICB execution;
- cross-pass command packets;
- one monolithic frame command stream;
- heap aliasing or async compute.

Do not widen scope until the current experiments have build and physical-GPU
evidence.

---

## 2. Non-negotiable correctness invariants

An agent modifying this work must preserve all of the following.

### 2.1 Ordered execution

Packet operations are serialized in the same order as the original Java calls.
State operation N applies before draw/dispatch N+1. No sorting, grouping, or
last-write elimination occurs inside the packet.

Java state shadows may suppress a change only before it enters a packet, using
the same existing equality rules as the legacy path.

### 2.2 Full validation before native execution

Render and compute native decoders validate the complete packet before applying
the first operation.

Native return contract:

```text
result == operationCount   complete packet executed
result < 0                 validation failed; zero operations executed
0 <= result < count        invalid implementation state; do not replay
```

Java may replay a packet through legacy calls only when the native result is
negative. A positive partial result is fail-stop because replay could duplicate
a draw or dispatch.

An FFM invocation exception is also fail-stop. Do not catch it and replay.
There is no proof that an exception occurred before native execution.

### 2.3 Explicit synchronization boundaries

Render command packets flush before:

- specialized triangle-fan draws;
- native multi-draw calls;
- clear helpers;
- render fence wait/update;
- deferred depth-store resolution;
- render encoder end;
- debug-group push/pop;
- render-contract producer recording.

Compute command packets flush before:

- compute fence wait/update;
- compute encoder end.

Do not move a fence, store action, clear, debug boundary, or validation record
inside a packet without defining and testing a new ABI opcode.

### 2.4 State-packet mutual exclusion

The old render-state-only packet and the new render-command packet must never
both mirror the same state change.

Selection rule:

```text
renderCommandPacket requested and negotiated -> command packet
otherwise, if renderStatePacket enabled       -> state-only packet
otherwise                                      -> legacy setters
```

### 2.5 Terrain ICB fallback guarantee

The terrain native function returns zero only before executing any draw. It
validates the complete batch before creating/executing the ICB.

After `executeCommandsInBuffer` is encoded, the function returns one. Do not add
a zero-return path after execution.

### 2.6 Legacy path remains available

Every experiment has a runtime switch. Do not remove legacy bridge functions,
ordinary direct draws/dispatches, or native multi-draw fallback until the new
path has long-run evidence and an explicit migration decision.

---

## 3. File map

### 3.1 Render command packet

```text
src/main/java/com/metallum/client/metal/render/bridge/
  MetalRenderCommandPacketBridge.java

src/main/java/com/metallum/client/metal/render/mtl/
  MetalRenderCommandPacket.java
  MetalRenderCommandPacketFacade.java

src/main/java/com/metallum/mixin/render/
  MTLRenderCommandEncoderPacketMixin.java
  MetalRenderPassCommandPacketBoundaryMixin.java
```

`MTLRenderCommandEncoderPacketMixin` is applied only when
`metallum.opt.renderCommandPacket=true`. With the switch off, the direct render
setter/draw bytecode is not redirected.

### 3.2 Compute command packet

```text
src/main/java/com/metallum/client/metal/render/bridge/
  MetalComputeCommandPacketBridge.java

src/main/java/com/metallum/client/metal/render/mtl/
  MetalComputeCommandPacket.java
  MetalComputeCommandPacketFacade.java
  MTLComputeCommandEncoder.java
```

Compute integration is in the encoder class rather than a conditional Mixin.
With the switch off, `commandPacket` is null and the existing state-shadow path
continues through a predictable null branch.

### 3.3 Terrain ICB pilot

```text
src/main/java/com/metallum/client/metal/render/bridge/
  MetalTerrainIcbBridge.java

src/main/java/com/metallum/client/metal/render/
  MetalTerrainIcbScope.java

src/main/java/com/metallum/mixin/sodium/
  DefaultChunkRendererTerrainIcbScopeMixin.java

src/main/java/com/metallum/mixin/render/
  MetalRenderPassMultiDrawBatchMixin.java
```

The Sodium scope Mixin is applied only when
`metallum.opt.terrainIcbPilot=true` and Sodium is loaded.

### 3.4 Native implementation

```text
src/main/native/MetallumInterface.swift
src/main/native/MetalFrameGenerationLifecycle.swift
```

`MetallumInterface.swift` contains the packet decoders, terrain ICB pilot, and
append-only feature tables.

`MetalFrameGenerationLifecycle.swift` contains narrow raw-pointer compute
encoder overloads and an `NSRange` to `Range<Int>` compatibility overload used
by the ICB pilot. These helpers have no `@_cdecl` symbol and do not change the
external ABI.

### 3.5 Telemetry and tests

```text
src/main/java/com/metallum/client/metal/render/mtl/
  MetalCommandPacketTelemetry.java

src/main/java/com/metallum/mixin/render/
  MetalHotPathTelemetryReportMixin.java

src/test/java/com/metallum/client/metal/render/mtl/
  MetalRenderCommandPacketTest.java
  MetalComputeCommandPacketTest.java

src/test/java/com/metallum/client/metal/render/
  MetalTerrainIcbScopeTest.java
```

---

## 4. Native feature negotiation

All new functions use the existing append-only `metallum_get_interface` API.
Do not add mandatory per-symbol lookup to `MetalNativeBridge`.

| Feature | ID | ABI | Capability bit | Entry 0 |
|---|---:|---:|---:|---|
| Core | 1 | 1 | mixed | existing core table |
| MetalFX | 2 | 1 | mixed | existing MetalFX table |
| Render state packet | 3 | 1 | `1 << 10` | `metallum_render_state_packet_apply_v1` |
| Render command packet | 4 | 1 | `1 << 11` | `metallum_render_command_packet_apply_v1` |
| Compute command packet | 5 | 1 | `1 << 12` | `metallum_compute_command_packet_apply_v1` |
| Terrain ICB | 6 | 1 | `1 << 13` | `metallum_terrain_icb_encode_indexed_v1` |

The table header remains:

```text
Offset  Type    Meaning
0       UInt32  headerSize
4       UInt32  total byteCount
8       UInt32  ABI version
12      Int32   feature ID
16      UInt32  entry count
20      UInt32  reserved = 0
24      UInt64  build capability bits
```

`DarwinLoadedSymbolLookup` must continue to locate the already-loaded temporary
Metallum dylib with `RTLD_NOLOAD`. Never load a second dylib to negotiate an
interface; that would create a second copy of Swift global state.

---

## 5. Render command packet ABI

### 5.1 Header

```text
Magic       MRCP / 0x4D524350
Version     1
Header      24 bytes
Entry       64 bytes
```

```text
Offset  Type    Meaning
0       UInt32  magic
4       UInt32  version
8       UInt32  total byteCount
12      UInt32  operationCount
16      UInt32  entrySize = 64
20      UInt32  reserved = 0
```

### 5.2 Entry

```text
Offset  Type    Meaning
0       UInt32  opcode
4       UInt32  flags / shader-stage mask
8       UInt64  a
16      UInt64  b
24      UInt64  c
32      UInt64  d
40      UInt64  e
48      UInt64  f
56      UInt64  g
```

### 5.3 Opcodes

```text
1   pipeline
2   depth-stencil
3   depth bias
4   front-face winding
5   cull mode
6   triangle fill mode
7   buffer
8   buffer offset
9   texture
10  texture + sampler
11  scissor

32  draw primitives
33  draw indexed primitives
34  draw primitives indirect
35  draw indexed primitives indirect
```

`draw indexed` packs signed `baseVertex` into the high 32 bits of `g` and
`baseInstance` into the low 32 bits.

The decoder calls the existing Swift render setter/draw functions after full
validation. Those functions already route a raw encoder pointer to either the
Metal 3 encoder or the Metal 4 render bridge and its argument tables.

### 5.4 Packet capacity

Default capacity is 512 operations. A full packet flushes and a new packet
continues in original order.

Capacity splitting is valid only because the first packet is completely
executed before Java resumes appending to the second packet.

---

## 6. Compute command packet ABI

### 6.1 Header and entry

The compute packet uses the same 24-byte header and 64-byte entry shape.

```text
Magic       MCCP / 0x4D434350
Version     1
```

### 6.2 Opcodes

```text
1   compute pipeline
2   buffer
3   texture
4   sampler
32  dispatch threadgroups
33  indirect dispatch threadgroups
```

The generic compute ABI currently returns a retained Metal 3
`MTLComputeCommandEncoder`. The raw-pointer adapter restores that object and
calls Metal directly.

Do not assume this packet supports the private Metal 4 compute bridge. A future
Metal 4 compute packet requires a separate admission and resource/argument-table
contract.

---

## 7. Terrain ICB pilot contract

### 7.1 Admission

Every condition below must hold:

```text
metallum.opt.terrainIcbPilot=true
metallum.opt.metal4=false
inside DefaultChunkRenderer.render scope
ordinary native indexed multi-draw batch is eligible
instanceCount > 0
emitted draw count >= terrainIcbMinDraws
emitted draw count <= 16,384
feature 6 negotiated
```

The batch contains:

```text
firstIndexOffsets  Int64 byte offsets
indexCounts        Int32 counts
vertexOffsets      Int32 base vertices
one index buffer
one primitive type
one index type
one instance count
one base instance
```

### 7.2 Inheritance

The ICB descriptor sets:

```text
commandTypes = drawIndexed
inheritPipelineState = true
inheritBuffers = true
maxVertexBufferBindCount = 0
maxFragmentBufferBindCount = 0
```

The parent encoder must already contain the correct render pipeline, vertex
buffers, descriptors, scissor, depth state, and raster state. Java flushes all
pending render state/command packets before ICB admission.

The index buffer is referenced directly by ICB commands and is declared through
`useResource(..., .read, .vertex)` before execution.

### 7.3 Current limitations

The pilot allocates and populates a new ICB for each admitted batch. This can be
slower than native multi-draw. Its first purpose is to prove:

- inherited state produces identical pixels;
- index offsets/counts/base vertices preserve order;
- resource residency and lifetime are correct;
- the native feature can reject and fall back without drawing twice.

Do not judge the final ICB opportunity from this allocation-heavy pilot alone.
After correctness, the next architecture is persistent per-region/per-pass ICB
storage or GPU-written commands.

---

## 8. Runtime switches

All new paths default to false.

```text
# Ordered render state + draw packet
-Dmetallum.opt.renderCommandPacket=true
-Dmetallum.opt.renderCommandPacketEntries=512
-Dmetallum.opt.renderCommandPacketMinOperations=2

# Ordered compute state + dispatch packet
-Dmetallum.opt.computeCommandPacket=true
-Dmetallum.opt.computeCommandPacketEntries=256
-Dmetallum.opt.computeCommandPacketMinOperations=2

# Sodium Metal 3 terrain ICB pilot
-Dmetallum.opt.terrainIcbPilot=true
-Dmetallum.opt.terrainIcbMinDraws=64

# Cumulative hot-path report
-Dmetallum.hotpath.telemetry=true
-Dmetallum.hotpath.telemetry.reportInterval=300
```

Relevant existing switches:

```text
-Dmetallum.opt.encoderStateShadow=true
-Dmetallum.opt.renderStatePacket=true
-Dmetallum.opt.nativeMultiDrawBatch=true
-Dmetallum.opt.nativeMultiDrawBatchThreshold=4
-Dmetallum.opt.noTraceDrawFastPath=true
-Dmetallum.opt.metal4=false
```

When `renderCommandPacket=true` and feature 4 negotiates, the old state-only
packet is not created. If feature 4 is unavailable, the requested command-packet
Mixin remains active but operations fall back and the state-only packet may
still be used.

---

## 9. Mandatory agent workflow

Do not skip phases or combine all switches on the first run.

### Phase 0: checkout and record provenance

```bash
git fetch origin
git switch feature/mobilegl-inspired-hotpath
git pull --ff-only

git rev-parse HEAD
git merge-base HEAD origin/integration/iris-metal-next
git status --short
```

Put both SHAs in the final report.

### Phase 1: static source inspection

Before compiling, inspect:

```bash
git diff origin/integration/iris-metal-next...HEAD -- \
  src/main/java/com/metallum/client/metal/render/bridge \
  src/main/java/com/metallum/client/metal/render/mtl \
  src/main/java/com/metallum/mixin/render \
  src/main/java/com/metallum/mixin/sodium \
  src/main/native/MetallumInterface.swift \
  src/main/native/MetalFrameGenerationLifecycle.swift
```

Verify:

- feature IDs are unique;
- Java/Swift magic, header size, entry size, and opcodes match;
- bridge `FunctionDescriptor` arguments match the Swift `@convention(c)` entry;
- no negative native result occurs after a draw/dispatch begins;
- command packet and state packet are mutually exclusive;
- all direct native multi-draw paths flush pending packets;
- compute fences flush dispatches;
- ICB returns zero before execution only.

### Phase 2: Java compile and unit tests

```bash
./gradlew clean compileJava test --stacktrace
```

Do not immediately edit around a failure. Classify it first:

```text
Java source/type error
Mixin annotation processor error
JUnit assertion failure
JDK native-access error
unrelated pre-existing test failure
```

Targeted tests:

```bash
./gradlew test \
  --tests '*MetalRenderCommandPacketTest' \
  --tests '*MetalComputeCommandPacketTest' \
  --tests '*MetalTerrainIcbScopeTest' \
  --tests '*MetalRenderStatePacketTest' \
  --tests '*MetalNativeInterfaceTableTest' \
  --stacktrace
```

Expected packet tests inspect memory layout only. They must not load the native
dylib or execute Metal.

### Phase 3: native compile

```bash
./gradlew buildMacNative --stacktrace --info
```

Then inspect exports:

```bash
nm -gU src/main/resources/natives/macos/libmetallum.dylib | grep -E \
  'metallum_get_interface|render_command_packet|compute_command_packet|terrain_icb'
```

Expected public symbols:

```text
_metallum_get_interface
_metallum_render_command_packet_apply_v1
_metallum_compute_command_packet_apply_v1
_metallum_terrain_icb_encode_indexed_v1
```

The interface table functions are public C symbols. Raw compute adapter overloads
must not appear as new C exports.

### Phase 4: full build

```bash
./gradlew buildMacNative build --stacktrace
```

A successful build is only a compile/link result. It is not runtime admission.

### Phase 5: Mixin application smoke

Start with all new paths off:

```bash
./gradlew runClientIris -Pworld='<WORLD>' \
  -Dmetallum.opt.renderCommandPacket=false \
  -Dmetallum.opt.computeCommandPacket=false \
  -Dmetallum.opt.terrainIcbPilot=false
```

Then enable one switch at a time. Search the log for:

```text
MixinApplyError
InvalidInjectionException
Critical injection failure
could not find target
@Redirect conflict
ClassCastException
Native bridge call failed
```

If a Mixin target descriptor fails, inspect compiled bytecode with:

```bash
javap -classpath build/classes/java/main -p -s \
  com.metallum.client.metal.render.mtl.MTLRenderCommandEncoder
```

Fix the descriptor; do not set `require = 0` merely to make startup continue.
These experimental Mixins are deliberately required when their switch is on.

---

## 10. Isolated correctness lanes

Use the same world, camera path, shader pack, render distance, resolution,
Retina scale, VSync state, and Sodium/Iris versions.

### 10.1 Render packet lanes

```text
R0  renderStatePacket=false
    renderCommandPacket=false

R1  renderStatePacket=true
    renderCommandPacket=false

R2  renderStatePacket=true
    renderCommandPacket=true
```

R2 should negotiate feature 4 and suppress creation of the state-only packet.
Compare R0/R1/R2 framebuffer hashes and fixed-camera captures.

Also run:

```text
R3  encoderStateShadow=false
    renderStatePacket=false
    renderCommandPacket=true
```

R3 isolates packet ordering from Java state suppression. It may be slower but
is useful for proving that every logical setter remains represented.

### 10.2 Compute packet lanes

```text
C0  computeCommandPacket=false
C1  computeCommandPacket=true
```

Use an Iris pack that exercises compute passes. Confirm identical output and no
new fence/visibility errors.

A C1 run with zero `computeCommandPacketCalls` is not evidence of failure until
it is confirmed that the selected workload actually created compute encoders
and dispatches.

### 10.3 Terrain ICB lanes

```text
I0  nativeMultiDrawBatch=true
    terrainIcbPilot=false
    metal4=false

I1  nativeMultiDrawBatch=true
    terrainIcbPilot=true
    terrainIcbMinDraws=64
    metal4=false
```

Then sweep threshold:

```text
16, 32, 64, 128, 256
```

Do not combine ICB with Metal 4. The current pilot must report fallback rather
than trying to reinterpret a private Metal 4 bridge object as a Metal 3 encoder.

---

## 11. Validation evidence

### 11.1 Metal API validation

Run local physical-GPU tests with:

```bash
export MTL_DEBUG_LAYER=1
export MTL_SHADER_VALIDATION=1
```

Reject any new message involving:

- indirect command buffer inheritance;
- undeclared resource use;
- invalid index-buffer ranges;
- encoder state incompatibility;
- command execution range;
- resource lifetime;
- fence ordering;
- render-pass/pipeline mismatch.

### 11.2 Render-contract validation

When producer tracing is enabled, `recordProducer` is a forced render-packet
flush boundary. This preserves the rule that a recorded producer corresponds to
native commands already submitted to the encoder.

Performance measurements must use the no-trace lane. Do not compare a traced
baseline with an untraced packet run.

### 11.3 Framebuffer/image validation

At minimum capture:

- fixed camera, opaque terrain;
- CUTOUT foliage;
- translucent water/glass;
- shadow pass;
- hand/held item;
- sky/clouds/weather;
- Iris deferred/composite/final;
- compute-driven pack stage if compute packet is tested.

Report:

```text
exact framebuffer hash match count
differing frame count
maximum absolute channel error
mean absolute error
SSIM or existing project image metric
first differing frame/pass
```

A small image difference is not automatically acceptable. First determine
whether it is expected nondeterminism or a command-order/resource bug.

---

## 12. Telemetry

Enable:

```text
-Dmetallum.hotpath.telemetry=true
-Dmetallum.hotpath.telemetry.reportInterval=300
```

The log emits cumulative lines beginning with:

```text
[metallum-hotpath]
```

Fields include:

```text
renderForwarded
renderSuppressed
renderOffsetOnly
computeForwarded
computeSuppressed
multiDrawBatches
multiDrawCommands
statePacketCalls
statePacketEntries
statePacketReplays
renderCommandPacketCalls
renderCommandOperations
renderCommandReplays
computeCommandPacketCalls
computeCommandOperations
computeCommandReplays
terrainIcbAttempts
terrainIcbAccepted
terrainIcbDraws
terrainIcbFallbacks
transientWrapperHits/Misses
multiUploadCalls/Items
backing pool trim/release/peak counters
```

Interpretation:

```text
renderCommandPacketCalls > 0
  feature 4 negotiated and packets executed

renderCommandReplays > 0
  native prevalidation rejected at least one packet; investigate immediately

computeCommandReplays > 0
  same for compute; do not accept performance data

terrainIcbAttempts > 0, accepted == 0
  admission reached native but all batches fell back

terrainIcbAccepted > 0
  ICB commands were executed; framebuffer/validation evidence is mandatory

terrainIcbFallbacks / attempts
  native rejection rate; high rate means admission is too broad or ABI data is invalid
```

The counters are cumulative. For controlled runs, start a fresh client process
per lane rather than subtracting manually across unrelated scenes.

---

## 13. Performance protocol

Only run after correctness passes.

Per lane:

```text
30 seconds warmup
120 seconds measurement
3 runs baseline + 3 runs candidate
same launch order randomized where practical
```

Report:

```text
FPS mean/median
frame time p50/p95/p99/p99.9
1% low
0.1% low
frames > 33.3 ms
frames > 50 ms
frames > 100 ms
missed display deadlines
CPU encode p50/p95/p99
GPU frame time
Java allocation bytes/frame
FFM calls/frame
packet operations/call
multi-draw/ICB batches
```

Admission rule:

- framebuffer and Metal validation pass;
- no unexplained crash/hang;
- no packet legacy replay in a candidate measurement;
- at least one target metric improves;
- no statistically meaningful regression in frame-time tails or GPU time.

A higher average FPS with worse p99/p99.9 or more 50/100 ms frames is not an
unqualified success.

---

## 14. Failure triage

### 14.1 Swift overload/type error in compute decoder

Symptom examples:

```text
Cannot convert value of type UnsafeMutableRawPointer...
No exact matches in call to ...dispatchThreadgroups
```

Check the raw overloads in `MetalFrameGenerationLifecycle.swift`. They must
accept exactly the argument types used by `MetallumInterface.swift`:

```text
UnsafeMutableRawPointer
UInt64 offsets
Int32 indices/dimensions
```

Do not change the public compute C ABI merely to satisfy this internal adapter.

### 14.2 Swift ICB API label mismatch

Inspect the current SDK overlay for:

```text
MTLIndirectRenderCommand.drawIndexedPrimitives
MTLRenderCommandEncoder.executeCommandsInBuffer
MTLRenderCommandEncoder.useResource
```

Keep the compatibility overload local. Do not replace inherited state with a
second copy of all pipeline/buffer state unless validation proves inheritance
unusable.

### 14.3 Feature does not negotiate

Check:

```bash
nm -gU .../libmetallum.dylib
```

Then verify:

- feature ID in Java and Swift;
- capability bit;
- entry count;
- interface table function pointer;
- currently loaded dylib is the rebuilt one;
- no stale jar contains an older bundled dylib.

Do not add a second `System.load` as a workaround.

### 14.4 Render/compute legacy replay increments

A negative native result means prevalidation failed. Instrument the native
error code or add a validation-only rejected-op index. Common causes:

- Java/Swift opcode mismatch;
- wrong entry size;
- enum raw-value mismatch;
- stale/invalid native object pointer;
- out-of-range integer conversion;
- invalid stage mask;
- old dylib with mismatched ABI.

Do not suppress the replay counter.

### 14.5 Positive partial result

This is a decoder bug. Stop immediately. The native implementation must either:

- reject before execution with a negative result; or
- execute every operation and return the exact count.

Do not change Java to replay a partial packet.

### 14.6 ICB renders nothing or corrupts terrain

Check in order:

1. parent pipeline was set before packet flush;
2. parent vertex buffers and descriptors are inherited;
3. byte offsets, not first-index units, are passed;
4. index type matches the buffer;
5. base vertex remains signed;
6. parent scissor/depth/raster state is unchanged;
7. index buffer is declared through `useResource`;
8. command execution range is correct;
9. the ICB path is not running on Metal 4;
10. the batch is genuinely Sodium terrain.

### 14.7 Terrain scope remains active

The scope is entered at method HEAD and exited at RETURN. A Java exception from
`DefaultChunkRenderer.render` can skip RETURN. Such an exception normally aborts
the render path, but an agent making the renderer recoverable must replace this
with a true try/finally-style injection or reset the scope at a guaranteed frame
boundary.

Do not ignore a nonzero scope depth after a recovered exception.

---

## 15. Rollback matrix

Fastest rollback:

```text
renderCommandPacket=false
computeCommandPacket=false
terrainIcbPilot=false
```

More isolated rollback:

```text
# Keep state-only batching, remove draws from packets
renderStatePacket=true
renderCommandPacket=false

# Keep native multi-draw, remove ICB
nativeMultiDrawBatch=true
terrainIcbPilot=false

# Disable every packet
renderStatePacket=false
renderCommandPacket=false
computeCommandPacket=false
```

Do not revert unrelated state-shadow/token/frame-arena work merely because one
new experiment fails.

---

## 16. Next implementation steps after acceptance

Proceed in this order only:

1. Fix all compile/Mixin/native ABI issues.
2. Prove render packet correctness and FFM reduction.
3. Prove compute packet correctness on real Iris compute workloads.
4. Prove terrain ICB output equivalence with the allocation-heavy pilot.
5. Add persistent ICB allocation keyed by stable terrain batch identity.
6. Re-record only dirty command ranges.
7. Add explicit resource-retention ownership for persistent ICBs.
8. Evaluate GPU-written visibility/command generation.
9. Design a separate Metal 4 terrain submission path using argument tables and
   MTL4 command-buffer semantics.

Do not jump directly from this pilot to GPU culling.

---

## 17. Required final report template

Every agent run should finish with:

```text
Branch/head SHA:
Base/merge-base SHA:
Hardware/macOS/JDK/Swift versions:
Minecraft/Sodium/Iris/shader-pack versions:

Build:
- compileJava:
- unit tests:
- buildMacNative:
- full build:
- Mixin startup:

Feature negotiation:
- render state packet feature 3:
- render command packet feature 4:
- compute command packet feature 5:
- terrain ICB feature 6:

Correctness:
- framebuffer hashes:
- image diff:
- render-contract validation:
- Metal API validation:
- crashes/hangs:

Telemetry:
- render command calls/operations/replays:
- compute command calls/operations/replays:
- ICB attempts/accepted/fallbacks/draws:
- state suppression and multi-draw counters:

Performance baseline -> candidate:
- FPS:
- p50/p95/p99/p99.9:
- 1%/0.1% low:
- >33.3/50/100 ms:
- CPU encode:
- GPU time:
- allocations/frame:
- FFM calls/frame:

Decision:
- admitted / rejected / blocked
- exact reason
- next smallest action
```

Never report “implemented successfully” without filling the build and
correctness sections.
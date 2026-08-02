# Iris-on-Metal lifecycle validation

The lifecycle driver belongs to the separate `validationJar`; it is not part of the production mod.

## Build

```bash
./gradlew validationJar verifyProductionJarIsolation
```

## Run profile

Launch the normal Iris client with the production and validation JARs present, an explicit world selected, and lifecycle validation enabled. A representative schedule is:

```text
-Dmetallum.iris.semantic=true
-Dmetallum.iris.validation.enabled=true
-Dmetallum.iris.validation.reloadFrame=120
-Dmetallum.iris.validation.disableFrame=240
-Dmetallum.iris.validation.enableFrame=360
-Dmetallum.iris.validation.stopFrame=480
-Dmetallum.iris.validation.worldTimeoutFrames=3600
-Dmetallum.iris.validation.controlReceipt=build/iris-runtime/iris-validation-control.jsonl
```

The reload, disable, and enable frames must be unique and occur before the stop frame. Disable and enable must either both be configured or both be omitted.

## Pass conditions

The JSONL receipt must show:

1. the requested world was entered;
2. `MetalWorldRenderingPipeline` became active;
3. every configured lifecycle action completed with its expected postcondition;
4. the final pipeline is active;
5. the terminal result is `passed`.

A missing receipt, malformed schedule, inactive pipeline, failed reload, or incorrect toggle state is not a pass. The driver stops the isolated client after either a pass or a failure.

## Scope boundary

This validates control-plane lifecycle semantics. It does not prove attachment contents, shader-pack visual parity, GPU synchronization, frame pacing, or MetalFX quality. Those remain separate correctness and attended Apple Silicon gates.

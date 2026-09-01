# Render-contract validation guide

Scope: `com.metallum.client.validation` and `validation/render-contract`. Global policy remains in the repository-root `AGENTS.md`.

This subsystem is an independent semantic oracle. It must describe observable rendering behavior without learning the candidate implementation's accidental identities.

Local invariants:

- Canonical joins use semantic pass IDs and generation-aware `ResourceIdentity`.
- Never weaken expectations, tolerances, fixtures or first-divergence reporting to make a renderer change pass.
- Pointer values, encoder ordinals, object addresses, timestamps and log line numbers are diagnostic metadata, not stable identity.
- When the oracle itself changes, require an independent fixture/self-test before using that same changed oracle to approve renderer behavior.
- Broad readback belongs to conformance/diagnostic runs, not performance instrumentation.

Use `python3 scripts/agent/context.py --task "<task>"` to obtain the minimum proof plan. Start with synthetic fixtures before paying for Minecraft E2E.

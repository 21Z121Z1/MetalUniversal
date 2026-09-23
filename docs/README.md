# MetalUniversal documentation map

The source, tests, build configuration, and exact commit evidence take precedence over documentation. Start with the repository `AGENTS.md`, then read the contract closest to the behavior being changed.

## Current contracts

- `agent/unified-evaluation-loop.md` and `agent/unified-evaluation-acceptance.json`: render correctness and performance evaluation.
- `render-contract-validation.md`: backend-neutral render semantics.
- `agent/frame-evidence-contract.md`: source, GPU, and presentation evidence boundaries.
- `agent/ci-proof-identity.md`: candidate-head and pull-request merge-result identity.
- `agent/minecraft-reference.md`: generated Minecraft source provenance.
- `agent/presentation-pacing-evidence.md`: presentation pacing evidence.

Read the directory-specific `AGENTS.md` under Java renderer, terrain, validation, native, or `scripts/agent` before changing that subsystem. The current product and branch authority is in the root `AGENTS.md` and `README.md`.

## Run the maintained tools

```bash
bash scripts/agent/doctor.sh
bash scripts/agent/verify_unified_eval.sh
```

`scripts/agent/record_ci_subject.py` records which exact Git object a workflow tested. For branch work, derive current refs and merge bases from Git; branch names and dated reports are not current-state proof. Generated run evidence belongs under ignored `build/` paths.

## Historical material

`handoffs/`, dated reports, old prompts, and earlier architecture plans preserve the reasoning and evidence from their original commit. The decisions under `agent/decisions/` explain choices made on earlier lines; check whether the referenced files still exist before using their procedures. The legacy Iris performance loop remains useful for focused compatibility, while the unified evaluation loop owns current acceptance.

Do not rewrite a historical result as a present-day measurement. Hosted CI, physical display, iOS device, visual parity, and performance evidence remain separate.

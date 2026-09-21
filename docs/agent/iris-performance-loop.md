# Superseded Iris-on-Metal performance loop

> **Historical compatibility stub. Do not use this file as the current agent execution protocol.**
>
> The canonical renderer correctness/performance workflow is `docs/agent/unified-evaluation-loop.md`, and the machine proof/ownership rules are in `docs/agent/system-registry.json`. Generate the current task-local schedule with `python3 scripts/agent/context.py --task "<task>"`.

The former protocol expected the retired `feature/iris-metal-performance` branch and drove `scripts/agent/run_iris_perf_cycle.sh` with `docs/agent/iris-performance-acceptance.json`. That lane predates the unified exact-SHA render evaluator, structured admission, interleaved paired acceptance and the proof-obligation/execution-schedule model. It is retained only for reproducing old experiments.

For historical reconstruction, the complete superseded document is preserved by Git at blob `56a0264be0615fad31c5759980d75c421c5e0c3a` and in commits preceding the agent-control-plane convergence. Do not copy its branch names, thresholds or command sequence into new work.

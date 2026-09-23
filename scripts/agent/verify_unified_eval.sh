#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
# Executable contracts supporting the migrated 26.3/P1 workflows. Generated
# historical agent plans and repository-convergence reports are not inputs.
for script in scripts/agent/*.sh; do bash -n "$script"; done
python3 -m compileall -q scripts/agent
python3 scripts/agent/verify_loaded_artifact.py --self-test
python3 scripts/agent/check_metal4_main_e2e_pair.py --self-test
python3 scripts/agent/check_metal4_main_profile_matrix.py --self-test
python3 scripts/agent/check_metal4_main_trial.py --self-test
python3 scripts/agent/check_unified_eval_admission.py --self-test
python3 scripts/agent/normalize_unified_trial.py --self-test
python3 scripts/agent/analyze_unified_eval.py --self-test
python3 scripts/agent/verify_benchmark_profiles.py
python3 scripts/agent/verify_p1_performance_route.py
python3 scripts/agent/verify_terrain_work_events.py --self-test
python3 scripts/agent/verify_metal4_main_hotpath.py
python3 scripts/agent/verify_migration_contract.py

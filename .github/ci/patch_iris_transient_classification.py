from pathlib import Path

plan_path = Path('src/main/java/com/metallum/client/metal/render/IrisMetalOptimizationPlan.java')
plan = plan_path.read_text()
old = '    enum LifetimeClassification { CONSERVATIVE_PERSISTENT }\n'
new = '    enum LifetimeClassification { CONSERVATIVE_PERSISTENT, PASS_LOCAL_TRANSIENT }\n'
if plan.count(old) != 1:
    raise SystemExit(f'lifetime enum anchor mismatch: {plan.count(old)}')
plan_path.write_text(plan.replace(old, new, 1))

compiler_path = Path('src/main/java/com/metallum/client/metal/render/IrisMetalAttachmentLifetimeCompiler.java')
compiler = compiler_path.read_text()
old = '''                    IrisMetalOptimizationPlan.AttachmentResolution.RESOLVED_RASTER,
                    IrisMetalOptimizationPlan.LifetimeClassification.CONSERVATIVE_PERSISTENT,
                    allocationKey,
                    Objects.requireNonNull(lifetimeByKey.get(allocationKey), allocationKey)
            ));
'''
new = '''                    IrisMetalOptimizationPlan.AttachmentResolution.RESOLVED_RASTER,
                    IrisMetalTransientAttachmentClassifier.classify(
                            candidate.load(),
                            candidate.store(),
                            candidate.passIndex(),
                            Objects.requireNonNull(lifetimeByKey.get(allocationKey), allocationKey),
                            unresolvedConsumers.isEmpty()
                    ),
                    allocationKey,
                    Objects.requireNonNull(lifetimeByKey.get(allocationKey), allocationKey)
            ));
'''
if compiler.count(old) != 1:
    raise SystemExit(f'resolved attachment anchor mismatch: {compiler.count(old)}')
compiler_path.write_text(compiler.replace(old, new, 1))
print('iris transient classification patch applied')

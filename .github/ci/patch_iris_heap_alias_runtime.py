from pathlib import Path

path = Path('src/main/java/com/metallum/client/metal/render/IrisMetalOptimizationBootstrap.java')
text = path.read_text()

old = '''    public static void onPostChainCreated(final Object chain) {\n        if (chain == null) return;\n        try {'''
new = '''    public static void onPostChainCreated(final Object chain) {\n        IrisMetalHeapAliasRuntime.clear();\n        if (chain == null) return;\n        try {'''
assert old in text
text = text.replace(old, new, 1)

old = '''    public static void onPostChainClosed() {\n        IrisMetalExperimentalOptimizer.clear();\n    }'''
new = '''    public static void onPostChainClosed() {\n        IrisMetalExperimentalOptimizer.clear();\n        IrisMetalHeapAliasRuntime.clear();\n    }'''
assert old in text
text = text.replace(old, new, 1)

old = '''            IrisMetalOptimizationPlan.AttachmentLifetimeReceipt receipt =\n                    IrisMetalAttachmentLifetimeCompiler.compile(plan, chain, targets);\n            IrisMetalExperimentalOptimizer.publishAttachmentLifetimeReceipt(plan, receipt);'''
new = '''            IrisMetalOptimizationPlan.AttachmentLifetimeReceipt receipt =\n                    IrisMetalAttachmentLifetimeCompiler.compile(plan, chain, targets);\n            IrisMetalExperimentalOptimizer.publishAttachmentLifetimeReceipt(plan, receipt);\n            // The receipt describes the allocation set that already exists.\n            // Publish only its stable semantic/physical recipe so a later\n            // resize in this same chain generation may choose heap-backed\n            // placement without carrying native allocation identities across\n            // generations.\n            IrisMetalHeapAliasRuntime.publish(receipt);'''
assert old in text
text = text.replace(old, new, 1)

old = '''            IrisMetalExperimentalOptimizer.publishAttachmentLifetimeReceipt(\n                    plan,\n                    IrisMetalAttachmentLifetimeCompiler.unresolvedReceipt(\n                            plan.chainGeneration(), targetEpoch, signature, "compiler-failure"\n                    )\n            );'''
new = '''            IrisMetalExperimentalOptimizer.publishAttachmentLifetimeReceipt(\n                    plan,\n                    IrisMetalAttachmentLifetimeCompiler.unresolvedReceipt(\n                            plan.chainGeneration(), targetEpoch, signature, "compiler-failure"\n                    )\n            );\n            IrisMetalHeapAliasRuntime.clear();'''
assert old in text
text = text.replace(old, new, 1)

path.write_text(text)

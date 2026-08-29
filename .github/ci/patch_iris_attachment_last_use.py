from pathlib import Path


def once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one match, got {count}")
    return text.replace(old, new, 1)

plan_path = Path('src/main/java/com/metallum/client/metal/render/IrisMetalOptimizationPlan.java')
plan = plan_path.read_text()
plan = once(
    plan,
    '''            int firstUse,
            int lastWrite,
            int nextUse,
            String nextUseAccess
''',
    '''            int firstUse,
            int lastWrite,
            int lastUse,
            int nextUse,
            String nextUseAccess
''',
    'lifetime lastUse field'
)
plan = once(
    plan,
    '''            if (mipLevel < 0 || firstUse < 0 || lastWrite < -1 || nextUse < -1) {
                throw new IllegalArgumentException("Invalid attachment lifetime range");
            }
            requireName(nextUseAccess, "nextUseAccess");
        }
    }
''',
    '''            if (mipLevel < 0 || firstUse < 0 || lastWrite < -1 || lastUse < firstUse
                    || lastUse < lastWrite || nextUse < -1) {
                throw new IllegalArgumentException("Invalid attachment lifetime range");
            }
            requireName(nextUseAccess, "nextUseAccess");
        }

        /**
         * Source-compatible constructor for tests/callers that only model the
         * former first-use/last-write/next-use receipt. Production compilation
         * supplies the exact lastUse from the full ordered event list.
         */
        AttachmentLifetime(
                String allocationKey,
                long allocationId,
                long allocationGeneration,
                int mipLevel,
                int firstUse,
                int lastWrite,
                int nextUse,
                String nextUseAccess
        ) {
            this(
                    allocationKey,
                    allocationId,
                    allocationGeneration,
                    mipLevel,
                    firstUse,
                    lastWrite,
                    Math.max(firstUse, Math.max(lastWrite, nextUse)),
                    nextUse,
                    nextUseAccess
            );
        }
    }
''',
    'lifetime validation and compatibility ctor'
)
plan_path.write_text(plan)

compiler_path = Path('src/main/java/com/metallum/client/metal/render/IrisMetalAttachmentLifetimeCompiler.java')
compiler = compiler_path.read_text()
compiler = once(
    compiler,
    '''            int firstUse = ordered.stream().mapToInt(Event::passIndex).min().orElseThrow();
            int lastWrite = ordered.stream()
''',
    '''            int firstUse = ordered.stream().mapToInt(Event::passIndex).min().orElseThrow();
            int lastUse = ordered.stream().mapToInt(Event::passIndex).max().orElseThrow();
            int lastWrite = ordered.stream()
''',
    'compiler exact lastUse'
)
compiler = once(
    compiler,
    '''                            firstUse,
                            lastWrite,
                            next == null ? -1 : next.passIndex(),
''',
    '''                            firstUse,
                            lastWrite,
                            lastUse,
                            next == null ? -1 : next.passIndex(),
''',
    'compiler emits lastUse'
)
compiler_path.write_text(compiler)

classifier_path = Path('src/main/java/com/metallum/client/metal/render/IrisMetalTransientAttachmentClassifier.java')
classifier = classifier_path.read_text()
classifier = once(
    classifier,
    '''                || lifetime.firstUse() != passIndex
                || lifetime.lastWrite() != passIndex
                || lifetime.nextUse() != -1
''',
    '''                || lifetime.firstUse() != passIndex
                || lifetime.lastWrite() != passIndex
                || lifetime.lastUse() != passIndex
                || lifetime.nextUse() != -1
''',
    'transient exact death gate'
)
classifier_path.write_text(classifier)

contract = Path('src/test/java/com/metallum/client/metal/render/IrisMetalAttachmentLastUseContractTest.java')
contract.write_text(r'''package com.metallum.client.metal.render;

import com.metallum.client.validation.contract.PassType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class IrisMetalAttachmentLastUseContractTest {
    @Test
    void lifetimeCarriesAnExactDeathPointIndependentOfLastWrite() {
        IrisMetalOptimizationPlan.AttachmentLifetime lifetime =
                new IrisMetalOptimizationPlan.AttachmentLifetime(
                        "allocation/1/generation/1/mip/0", 1L, 1L, 0,
                        2, 3, 7, 4, "SAMPLED_READ"
                );
        assertEquals(2, lifetime.firstUse());
        assertEquals(3, lifetime.lastWrite());
        assertEquals(7, lifetime.lastUse());
        assertEquals(4, lifetime.nextUse());
    }

    @Test
    void lifetimeRejectsDeathBeforeAReadOrWrite() {
        assertThrows(IllegalArgumentException.class, () ->
                new IrisMetalOptimizationPlan.AttachmentLifetime(
                        "allocation/1/generation/1/mip/0", 1L, 1L, 0,
                        2, 5, 4, 3, "SAMPLED_READ"
                ));
    }

    @Test
    void oldConstructorRemainsConservativeForFocusedFixtures() {
        IrisMetalOptimizationPlan.AttachmentLifetime lifetime =
                new IrisMetalOptimizationPlan.AttachmentLifetime(
                        "allocation/2/generation/1/mip/0", 2L, 1L, 0,
                        1, 2, 6, "SAMPLED_READ"
                );
        assertEquals(6, lifetime.lastUse());
    }
}
''')
print('authoritative attachment last-use fact staged')

package com.metallum.client.validation.reference;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Registration boundary for Iris program semantics. It deliberately keys on
 * program/stage/index, never on a shader-pack name.
 */
public final class IrisReferencePassRegistry {
    private final Map<Key, String> semanticPasses = new LinkedHashMap<>();

    public synchronized CapabilityStatus register(
            final String program,
            final int passIndex,
            final String stage,
            final String semanticPassId
    ) {
        if (program == null || program.isBlank() || passIndex < 0 || stage == null || stage.isBlank()
                || semanticPassId == null || semanticPassId.isBlank()) {
            throw new IllegalArgumentException("Invalid Iris reference pass registration");
        }
        if (!semanticPassId.startsWith("iris/") && !semanticPassId.startsWith("minecraft/")) {
            throw new IllegalArgumentException("Iris semantic pass must use iris/ or minecraft/ namespace");
        }
        semanticPasses.put(new Key(program, passIndex, stage), semanticPassId);
        return CapabilityStatus.SUPPORTED;
    }

    public synchronized String resolve(final String program, final int passIndex, final String stage) {
        return semanticPasses.get(new Key(program, passIndex, stage));
    }

    public synchronized CapabilityStatus statusFor(
            final String program,
            final int passIndex,
            final String stage
    ) {
        return resolve(program, passIndex, stage) == null
                ? CapabilityStatus.UNCLASSIFIED
                : CapabilityStatus.SUPPORTED;
    }

    public synchronized Map<String, String> snapshot() {
        Map<String, String> result = new LinkedHashMap<>();
        semanticPasses.forEach((key, value) -> result.put(key.toString(), value));
        return Map.copyOf(result);
    }

    private record Key(String program, int passIndex, String stage) {
        @Override
        public String toString() {
            return program + "#" + passIndex + "/" + stage;
        }
    }
}

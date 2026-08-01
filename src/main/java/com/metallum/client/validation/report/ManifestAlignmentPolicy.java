package com.metallum.client.validation.report;

import com.metallum.client.validation.contract.RenderPassRecord;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Machine-readable rules for aligning a reference manifest with a backend
 * manifest. The default policy is backend-neutral but fail-closed: only
 * explicitly declared aliases, optional passes, private passes, and
 * multiplicity changes are accepted.
 */
public record ManifestAlignmentPolicy(
        Map<String, String> semanticAliases,
        Set<String> backendPrivatePasses,
        Set<String> optionalPasses,
        Map<String, Multiplicity> multiplicityRules,
        boolean comparePipelineAndShaders,
        ResourceGenerationMode resourceGenerationMode
) {
    public enum ResourceGenerationMode {
        /** Generation numbers must match exactly; use within one runtime/run. */
        ABSOLUTE,
        /** Compare allocation lineage transitions, not process-local counters. */
        RELATIVE_LINEAGE
    }
    public enum Multiplicity {
        EXACT,
        ALLOW_SPLIT,
        ALLOW_FOLD,
        ALLOW_SPLIT_OR_FOLD;

        public boolean permits(final int expectedCount, final int actualCount) {
            if (expectedCount == actualCount) return true;
            if (expectedCount == 1 && actualCount > 1) {
                return this == ALLOW_SPLIT || this == ALLOW_SPLIT_OR_FOLD;
            }
            if (expectedCount > 1 && actualCount == 1) {
                return this == ALLOW_FOLD || this == ALLOW_SPLIT_OR_FOLD;
            }
            return false;
        }
    }

    public ManifestAlignmentPolicy {
        semanticAliases = immutableMap(semanticAliases);
        backendPrivatePasses = Set.copyOf(backendPrivatePasses == null ? Set.of() : backendPrivatePasses);
        optionalPasses = Set.copyOf(optionalPasses == null ? Set.of() : optionalPasses);
        multiplicityRules = immutableMap(multiplicityRules);
        resourceGenerationMode = resourceGenerationMode == null
                ? ResourceGenerationMode.ABSOLUTE : resourceGenerationMode;
        for (Map.Entry<String, String> entry : semanticAliases.entrySet()) {
            requireName(entry.getKey(), "semantic alias source");
            requireName(entry.getValue(), "semantic alias target");
        }
        for (String value : backendPrivatePasses) requireName(value, "backend-private pass");
        for (String value : optionalPasses) requireName(value, "optional pass");
        for (Map.Entry<String, Multiplicity> entry : multiplicityRules.entrySet()) {
            requireName(entry.getKey(), "multiplicity pass");
            Objects.requireNonNull(entry.getValue(), "multiplicity rule");
        }
    }

    /** Compatibility constructor for callers written against schema version 1. */
    public ManifestAlignmentPolicy(
            final Map<String, String> semanticAliases,
            final Set<String> backendPrivatePasses,
            final Set<String> optionalPasses,
            final Map<String, Multiplicity> multiplicityRules,
            final boolean comparePipelineAndShaders
    ) {
        this(
                semanticAliases,
                backendPrivatePasses,
                optionalPasses,
                multiplicityRules,
                comparePipelineAndShaders,
                ResourceGenerationMode.ABSOLUTE
        );
    }

    /** Backend-neutral default. Pipeline and shader hashes remain evidence, not identity. */
    public static ManifestAlignmentPolicy strict() {
        return new ManifestAlignmentPolicy(
                Map.of(), Set.of(), Set.of(), Map.of(), false, ResourceGenerationMode.ABSOLUTE
        );
    }

    /**
     * Policy for an independently executed reference backend and candidate
     * backend. Runtime generation counters may start at different values, but
     * reallocation transitions must still have the same semantic lineage.
     */
    public static ManifestAlignmentPolicy crossBackend() {
        return new ManifestAlignmentPolicy(
                Map.of(), Set.of(), Set.of(), Map.of(), false,
                ResourceGenerationMode.RELATIVE_LINEAGE
        );
    }

    public boolean compareResourceGenerationAbsolutely() {
        return resourceGenerationMode == ResourceGenerationMode.ABSOLUTE;
    }

    public String canonicalSemanticPassId(final String semanticPassId) {
        String current = requireName(semanticPassId, "semanticPassId");
        for (int depth = 0; depth < 32; depth++) {
            String next = semanticAliases.get(current);
            if (next == null || next.equals(current)) return next == null ? current : next;
            current = next;
        }
        throw new IllegalArgumentException("Semantic pass alias chain exceeds 32 entries: " + semanticPassId);
    }

    public boolean isBackendPrivate(final RenderPassRecord pass) {
        // A producer may classify a pass as private, but classification alone
        // is not permission to ignore it. The fixture/reference policy must
        // explicitly allow that semantic ID; otherwise an extra private-looking
        // pass remains a strict divergence.
        return backendPrivatePasses.contains(canonicalSemanticPassId(pass.semanticPassId()));
    }

    public boolean isOptional(final String semanticPassId) {
        return optionalPasses.contains(canonicalSemanticPassId(semanticPassId));
    }

    public Multiplicity multiplicityFor(final String semanticPassId) {
        return multiplicityRules.getOrDefault(canonicalSemanticPassId(semanticPassId), Multiplicity.EXACT);
    }

    private static <V> Map<String, V> immutableMap(final Map<String, V> values) {
        return Map.copyOf(new LinkedHashMap<>(values == null ? Map.of() : values));
    }

    private static String requireName(final String value, final String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}

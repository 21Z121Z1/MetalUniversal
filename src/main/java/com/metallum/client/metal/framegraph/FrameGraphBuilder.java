package com.metallum.client.metal.framegraph;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Declarative, single-use frame graph builder.
 *
 * <p>The baseline pipeline declares its resources and passes, then every enabled
 * {@link FrameGraphExtension} adds its own, then {@link #compile()} validates
 * the whole thing at once. An extension therefore cannot observe a partially
 * built graph or reorder anything the baseline declared.</p>
 */
public final class FrameGraphBuilder {
    private final Map<SemanticResource, ResourceDescriptor> resources = new EnumMap<>(SemanticResource.class);
    private final List<FramePass> passes = new ArrayList<>();
    private final Set<String> passNames = new LinkedHashSet<>();
    private boolean compiled;

    /**
     * Declares a resource. Declaring the same resource twice is allowed only if
     * both descriptors are identical, so two extensions that agree about a
     * shared resource compose, and two that disagree fail loudly instead of
     * silently taking whichever ran first.
     */
    public FrameGraphBuilder resource(final SemanticResource semantic, final ResourceDescriptor descriptor) {
        requireOpen();
        Objects.requireNonNull(semantic, "semantic");
        Objects.requireNonNull(descriptor, "descriptor");
        ResourceDescriptor existing = resources.putIfAbsent(semantic, descriptor);
        if (existing != null && !existing.equals(descriptor)) {
            throw new FrameGraphException("Conflicting declarations for " + semantic
                    + ": " + existing + " and " + descriptor);
        }
        return this;
    }

    public FrameGraphBuilder pass(final String name, final FramePass.Phase phase, final Consumer<PassBuilder> declaration) {
        requireOpen();
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Pass name must not be blank");
        }
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(declaration, "declaration");
        if (!passNames.add(name)) {
            throw new FrameGraphException("Duplicate pass name: " + name);
        }
        PassBuilder builder = new PassBuilder();
        declaration.accept(builder);
        passes.add(new FramePass(name, phase, builder.resources, builder.dependencies, passes.size()));
        return this;
    }

    public CompiledFrameGraph compile() {
        requireOpen();
        compiled = true;
        return FrameGraphCompiler.compile(resources, passes);
    }

    private void requireOpen() {
        if (compiled) {
            throw new FrameGraphException("This FrameGraphBuilder has already been compiled");
        }
    }

    public static final class PassBuilder {
        private final Map<SemanticResource, FramePass.Access> resources = new LinkedHashMap<>();
        private final Set<String> dependencies = new LinkedHashSet<>();

        public PassBuilder read(final SemanticResource resource) {
            return access(resource, FramePass.Access.READ);
        }

        public PassBuilder write(final SemanticResource resource) {
            return access(resource, FramePass.Access.WRITE);
        }

        public PassBuilder readWrite(final SemanticResource resource) {
            return access(resource, FramePass.Access.READ_WRITE);
        }

        public PassBuilder access(final SemanticResource resource, final FramePass.Access access) {
            Objects.requireNonNull(resource, "resource");
            Objects.requireNonNull(access, "access");
            if (resources.putIfAbsent(resource, access) != null) {
                throw new FrameGraphException("Resource " + resource + " is declared twice in one pass;"
                        + " use readWrite instead of separate read and write");
            }
            return this;
        }

        /**
         * An ordering edge that no resource access implies. Use this only for
         * genuine side-channel ordering; resource hazards are derived
         * automatically and do not need to be restated here.
         */
        public PassBuilder dependsOn(final String passName) {
            if (passName == null || passName.isBlank()) {
                throw new IllegalArgumentException("Dependency name must not be blank");
            }
            dependencies.add(passName);
            return this;
        }
    }
}

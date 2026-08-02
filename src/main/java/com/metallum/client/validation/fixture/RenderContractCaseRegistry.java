package com.metallum.client.validation.fixture;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** Versioned registry for deterministic render-contract fixtures. */
public final class RenderContractCaseRegistry {
    public static final int SUPPORTED_SCHEMA_VERSION = 1;
    private static final Gson GSON = new Gson();

    private final int schemaVersion;
    private final Defaults defaults;
    private final List<CaseDefinition> cases;

    private RenderContractCaseRegistry(
            final int schemaVersion,
            final Defaults defaults,
            final List<CaseDefinition> cases
    ) {
        if (schemaVersion != SUPPORTED_SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported render-contract cases schema " + schemaVersion);
        }
        this.schemaVersion = schemaVersion;
        this.defaults = defaults == null ? Defaults.defaults() : defaults;
        this.cases = List.copyOf(cases == null ? List.of() : cases);
        if (this.cases.isEmpty()) {
            throw new IllegalArgumentException("Render-contract registry must contain at least one case");
        }
        for (CaseDefinition definition : this.cases) definition.validate();
    }

    public static RenderContractCaseRegistry load(final Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        String json = Files.readString(path, StandardCharsets.UTF_8);
        try {
            JsonObject root = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
            RenderContractCaseRegistry registry = new RenderContractCaseRegistry(
                    root.has("schemaVersion") ? root.get("schemaVersion").getAsInt() : -1,
                    GSON.fromJson(root.get("defaults"), Defaults.class),
                    GSON.fromJson(root.get("cases"), CaseDefinition[].class) == null
                            ? List.of()
                            : List.of(GSON.fromJson(root.get("cases"), CaseDefinition[].class))
            );
            return registry;
        } catch (JsonParseException | IllegalStateException | NullPointerException exception) {
            throw new IllegalArgumentException("Invalid render-contract registry " + path, exception);
        }
    }

    public int schemaVersion() {
        return schemaVersion;
    }

    public Defaults defaults() {
        return defaults;
    }

    public List<CaseDefinition> cases() {
        return cases;
    }

    public CaseDefinition requireCase(final String name) {
        return cases.stream()
                .filter(definition -> definition.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown render-contract case " + name));
    }

    public record Defaults(
            int framebufferWidth,
            int framebufferHeight,
            boolean strictUnclassifiedPasses
    ) {
        static Defaults defaults() {
            return new Defaults(1708, 960, true);
        }
    }

    public record CaseDefinition(
            String name,
            String scenario,
            List<String> backendModes,
            String capturePolicy,
            String expectations,
            Boolean strictUnclassifiedPasses
    ) {
        void validate() {
            if (name == null || name.isBlank() || scenario == null || scenario.isBlank()
                    || backendModes == null || backendModes.isEmpty()
                    || capturePolicy == null || capturePolicy.isBlank()
                    || expectations == null || expectations.isBlank()) {
                throw new IllegalArgumentException("Invalid render-contract case " + name);
            }
            if (backendModes.stream().anyMatch(mode -> mode == null || mode.isBlank())) {
                throw new IllegalArgumentException("Case " + name + " has an empty backend mode");
            }
        }

        public boolean strictUnclassifiedPasses(final Defaults defaults) {
            return strictUnclassifiedPasses == null
                    ? defaults.strictUnclassifiedPasses()
                    : strictUnclassifiedPasses;
        }
    }
}

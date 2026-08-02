package com.metallum.client.validation.reference;

import java.util.List;
import java.util.Map;

/** Backend-neutral artifact envelope produced by a fixed Iris/OpenGL run. */
public record ReferenceRun(
        int schemaVersion,
        String runId,
        String backend,
        String minecraftVersion,
        String irisVersion,
        String shaderPackSha256,
        List<ReferenceFrame> frames,
        List<ReferenceExpectation> expectations,
        Map<String, CapabilityStatus> capabilities
) {
    public ReferenceRun {
        if (schemaVersion <= 0 || runId == null || runId.isBlank() || backend == null || backend.isBlank()) {
            throw new IllegalArgumentException("Invalid reference run");
        }
        frames = frames == null ? List.of() : List.copyOf(frames);
        expectations = expectations == null ? List.of() : List.copyOf(expectations);
        capabilities = capabilities == null ? Map.of() : Map.copyOf(capabilities);
    }
}

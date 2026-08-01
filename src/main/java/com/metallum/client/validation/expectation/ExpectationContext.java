package com.metallum.client.validation.expectation;

import com.metallum.client.validation.capture.CapturedResource;
import com.metallum.client.validation.contract.CapturePoint;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Context shared by expectations during one capture completion. */
public final class ExpectationContext {
    private final CapturePoint point;
    private final Path outputDirectory;
    private final Map<String, CapturedResource> previousResources;
    private final Map<String, Object> metadata;

    public ExpectationContext(
            final CapturePoint point,
            final Path outputDirectory,
            final Map<String, CapturedResource> previousResources,
            final Map<String, Object> metadata
    ) {
        this.point = point;
        this.outputDirectory = outputDirectory;
        this.previousResources = previousResources == null
                ? Map.of()
                : Map.copyOf(new LinkedHashMap<>(previousResources));
        this.metadata = metadata == null
                ? Map.of()
                : Map.copyOf(new LinkedHashMap<>(metadata));
    }

    public CapturePoint point() {
        return point;
    }

    public Path outputDirectory() {
        return outputDirectory;
    }

    public Map<String, CapturedResource> previousResources() {
        return previousResources;
    }

    public Map<String, Object> metadata() {
        return metadata;
    }
}

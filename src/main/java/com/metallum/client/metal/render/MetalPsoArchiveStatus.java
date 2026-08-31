package com.metallum.client.metal.render;

import org.jspecify.annotations.Nullable;

/**
 * Public, allocation-free-at-call-site view of the current PSO archive
 * identity.  Validation/reporting code lives in a different package and must
 * not reach through the package-private {@link MetalDevice} implementation.
 */
public final class MetalPsoArchiveStatus {
    private MetalPsoArchiveStatus() {
    }

    public static Snapshot snapshot() {
        MetalDevice device = MetalDevice.current();
        if (device == null) {
            return Snapshot.unavailable();
        }
        MetalPsoArchiveIdentity identity = device.psoArchiveIdentity();
        String path = device.psoArchivePath();
        return new Snapshot(
                path != null,
                path,
                identity.digest(),
                identity.filename(),
                identity.exactShaderPackIdentity(),
                identity.metal4()
        );
    }

    public record Snapshot(
            boolean open,
            @Nullable String path,
            String digest,
            String filename,
            boolean exactShaderPackIdentity,
            boolean metal4
    ) {
        private static Snapshot unavailable() {
            return new Snapshot(false, null, "unavailable", "unavailable", false, false);
        }
    }
}

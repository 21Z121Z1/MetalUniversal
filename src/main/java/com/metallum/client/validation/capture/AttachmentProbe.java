package com.metallum.client.validation.capture;

import com.metallum.client.validation.contract.AttachmentSemantic;
import com.metallum.client.validation.contract.CaptureFormat;
import com.metallum.client.validation.contract.ResourceIdentity;

import java.util.Objects;

/** Describes one logical attachment that a capture request may read back. */
public interface AttachmentProbe {
    String semanticName();

    ResourceIdentity resource();

    AttachmentSemantic semantic();

    CaptureFormat captureFormat();

    static AttachmentProbe of(
            final String semanticName,
            final ResourceIdentity resource,
            final AttachmentSemantic semantic,
            final CaptureFormat captureFormat
    ) {
        return new Basic(semanticName, resource, semantic, captureFormat);
    }

    record Basic(
            String semanticName,
            ResourceIdentity resource,
            AttachmentSemantic semantic,
            CaptureFormat captureFormat
    ) implements AttachmentProbe {
        public Basic {
            if (semanticName == null || semanticName.isBlank()
                    || resource == null || semantic == null || captureFormat == null) {
                throw new IllegalArgumentException("Invalid attachment probe");
            }
            Objects.requireNonNull(resource);
        }
    }
}

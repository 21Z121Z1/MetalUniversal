package com.metallum.client.validation.capture;

import com.metallum.client.validation.contract.CapturePoint;
import com.metallum.client.validation.expectation.ExpectationSpec;

import java.util.List;

/** Backend-independent capture boundary. GPU encoders may complete requests asynchronously. */
public interface ValidationCaptureService extends AutoCloseable {
    void requestCapture(
            CapturePoint point,
            List<AttachmentProbe> probes,
            List<ExpectationSpec> expectations
    );

    void completeCapture(
            CapturePoint point,
            List<CapturedResource> resources,
            List<ExpectationSpec> expectations
    );

    void cancelPending(String reason);

    int pendingCaptures();

    int completedCaptures();

    int failedCaptures();

    @Override
    void close();
}

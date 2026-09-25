package com.metallum.client.validation.capture;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class FileValidationCaptureServicePngOrientationTest {
    @Test
    void finalDrawableDiagnosticPngFlipsBackendRowsToTopLeftOrder() {
        assertEquals(2, FileValidationCaptureService.diagnosticPngSourceRow("final-drawable", 3, 0));
        assertEquals(1, FileValidationCaptureService.diagnosticPngSourceRow("final-drawable", 3, 1));
        assertEquals(0, FileValidationCaptureService.diagnosticPngSourceRow("final-drawable", 3, 2));
    }

    @Test
    void nonPresentationCapturesRetainExistingRowOrder() {
        assertEquals(0, FileValidationCaptureService.diagnosticPngSourceRow("scene-color", 3, 0));
        assertEquals(2, FileValidationCaptureService.diagnosticPngSourceRow("scene-color", 3, 2));
    }

    @Test
    void invalidRowsFailClosed() {
        assertThrows(IllegalArgumentException.class,
                () -> FileValidationCaptureService.diagnosticPngSourceRow("final-drawable", 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> FileValidationCaptureService.diagnosticPngSourceRow("final-drawable", 3, 3));
    }
}

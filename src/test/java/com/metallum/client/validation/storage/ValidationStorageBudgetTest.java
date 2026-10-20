package com.metallum.client.validation.storage;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ValidationStorageBudgetTest {
    @Test
    void streamingCleanupFailureKeepsTheOriginalSerializationFailure(@org.junit.jupiter.api.io.TempDir Path root) throws Exception {
        var budget=ValidationStorageBudget.shared(root,4096);
        Path file=root.resolve("manifest.json"),partial=root.resolve("manifest.json.partial");
        budget.writeAtomicUtf8(file,4096,writer->writer.write("previous complete evidence"));
        RuntimeException primary=new IllegalStateException("injected original serialization failure");
        var thrown=assertThrows(IllegalStateException.class,()->budget.writeAtomicUtf8(file,4096,writer->{
            writer.write("unpublished");
            // The owned temporary inode remains open; an independent filesystem
            // failure at the pathname makes finally-cleanup fail as well.
            Files.delete(partial);Files.createDirectory(partial);Files.writeString(partial.resolve("cause"),"retain diagnostic");
            throw primary;
        }));
        assertTrue(thrown==primary,"Cleanup must not replace the first failure");
        assertEquals(1,thrown.getSuppressed().length);
        assertTrue(thrown.getSuppressed()[0] instanceof java.nio.file.DirectoryNotEmptyException);
        assertEquals("previous complete evidence",Files.readString(file));
        assertEquals(Files.size(file),budget.artifactBytes());
        assertEquals("retain diagnostic",Files.readString(partial.resolve("cause")));
    }

    @Test
    void streamingCountsUtf8BytesAndRewritesOnlyCommittedSize() throws Exception {
        Path root = Files.createTempDirectory("render-contract-stream-utf8-");
        var budget = ValidationStorageBudget.shared(root, 32);
        Path file = root.resolve("manifest.json");
        budget.writeAtomicUtf8(file, 32, writer -> writer.write("old"));
        assertEquals(8L, budget.writeAtomicUtf8(file, 32, writer -> writer.write("雨😀\n")));
        assertEquals("雨😀\n", Files.readString(file));
        assertEquals(8L, Files.size(file));
        assertEquals(8L, budget.artifactBytes());
    }

    @Test
    void streamingFileLimitCountsWholeDocumentAndPreservesPreviousEvidence() throws Exception {
        Path root = Files.createTempDirectory("render-contract-stream-file-limit-");
        var budget = ValidationStorageBudget.shared(root, 32);
        Path file = root.resolve("manifest.json");
        budget.writeAtomicUtf8(file, 32, writer -> writer.write("ok"));
        var failure = assertThrows(ValidationStorageBudget.FileByteLimitExceededException.class,
                () -> budget.writeAtomicUtf8(file, 5, writer -> {
                    writer.write("雨"); writer.write("\uD83D"); writer.write("\uDE00");
                }));
        assertEquals(7L, failure.requiredBytes());
        assertEquals("ok", Files.readString(file));
        assertEquals(2L, budget.artifactBytes());
        assertFalse(budget.exceeded(), "A file limit does not consume the shared root's critical reserve");
        assertFalse(Files.exists(root.resolve("manifest.json.partial")));
    }

    @Test
    void streamingSharedLimitAndBodyFailureCannotPublishPartialEvidence() throws Exception {
        Path root = Files.createTempDirectory("render-contract-stream-failure-");
        var budget = ValidationStorageBudget.shared(root, 8);
        Path file = root.resolve("manifest.json");
        budget.writeAtomicUtf8(file, 32, writer -> writer.write("ok"));
        assertThrows(java.io.IOException.class, () -> budget.writeAtomicUtf8(file, 32, writer -> {
            writer.write("partial"); throw new java.io.IOException("injected write failure");
        }));
        assertEquals("ok", Files.readString(file));
        assertEquals(2L, budget.artifactBytes());
        assertFalse(Files.exists(root.resolve("manifest.json.partial")));
        var failure = assertThrows(ValidationStorageBudget.StorageBudgetExceededException.class,
                () -> budget.writeAtomicUtf8(file, 32, writer -> writer.write("123456789")));
        assertEquals(9L, failure.requiredBytes());
        assertEquals("ok", Files.readString(file));
        assertTrue(budget.exceeded());
        assertFalse(Files.exists(root.resolve("manifest.json.partial")));
    }

    @Test
    void streamingNeverDeletesAnExistingCheckpointOrEscapesTheRoot() throws Exception {
        Path root = Files.createTempDirectory("render-contract-stream-checkpoint-");
        var budget = ValidationStorageBudget.shared(root, 32);
        Path file = root.resolve("manifest.json"), partial = root.resolve("manifest.json.partial");
        Files.writeString(partial, "crash evidence");
        assertThrows(java.io.IOException.class, () -> budget.writeAtomicUtf8(file, 32, writer -> writer.write("new")));
        assertEquals("crash evidence", Files.readString(partial));
        assertThrows(IllegalArgumentException.class,
                () -> budget.writeAtomicUtf8(root.resolve("../outside.json"), 32, writer -> writer.write("no")));
    }

    @Test
    void budgetCountsFinalFileSizeAndRewritesDoNotDoubleCount() throws Exception {
        Path root = Files.createTempDirectory("render-contract-budget-");
        ValidationStorageBudget budget = ValidationStorageBudget.shared(root, 32L);

        budget.writeBytes(root.resolve("capture.bin"), new byte[]{1, 2, 3, 4});
        assertEquals(4L, budget.artifactBytes());

        budget.writeBytes(root.resolve("capture.bin"), new byte[]{1, 2});
        assertEquals(2L, budget.artifactBytes());
        assertEquals(30L, budget.remainingBytes());
        assertFalse(budget.exceeded());
    }

    @Test
    void budgetFailureIsStructuredAndRejectsFurtherWrites() throws Exception {
        Path root = Files.createTempDirectory("render-contract-budget-failure-");
        ValidationStorageBudget budget = ValidationStorageBudget.shared(root, 16L);

        assertThrows(
                ValidationStorageBudget.StorageBudgetExceededException.class,
                () -> budget.writeBytes(root.resolve("too-large.bin"), new byte[17])
        );
        assertTrue(budget.exceeded());
        assertTrue(budget.failureReason().contains("budget"));
        assertThrows(
                ValidationStorageBudget.StorageBudgetExceededException.class,
                () -> budget.writeBytes(root.resolve("second.bin"), new byte[]{1})
        );

        budget.writeCriticalString(root.resolve("run-state.json"), "{\"status\":\"failed\"}\n");
        assertTrue(Files.exists(root.resolve("run-state.json")));
    }

    @Test
    void artifactPathCannotEscapeValidationRoot() throws Exception {
        Path root = Files.createTempDirectory("render-contract-budget-path-");
        ValidationStorageBudget budget = ValidationStorageBudget.shared(root, 1024L);

        assertThrows(
                IllegalArgumentException.class,
                () -> budget.writeBytes(root.resolve("..").resolve("outside.bin"), new byte[]{1})
        );
    }

    @Test
    void managedTemporaryRunsUseTheSmallerDefaultBudget() throws Exception {
        Path root = Files.createTempDirectory("metallum-render-contract-budget-default-");
        ValidationStorageBudget budget = ValidationStorageBudget.shared(root);

        assertEquals(ValidationStorageBudget.DEFAULT_TEMP_MAX_BYTES, budget.maxBytes());
        assertEquals(ValidationStorageBudget.DEFAULT_TEMP_MAX_BYTES,
                ValidationStorageBudget.defaultMaxBytes(root));
        assertEquals(ValidationStorageBudget.DEFAULT_TEMP_MAX_BYTES,
                ValidationStorageBudget.defaultMaxBytes(root.resolve("render-contract")));
    }

    @Test
    void nonManagedRootsKeepThePersistentDefaultBudget() throws Exception {
        Path root = Files.createTempDirectory("render-contract-persistent-budget-default-");
        ValidationStorageBudget budget = ValidationStorageBudget.shared(root);

        assertEquals(ValidationStorageBudget.DEFAULT_MAX_BYTES, budget.maxBytes());
        assertEquals(ValidationStorageBudget.DEFAULT_MAX_BYTES,
                ValidationStorageBudget.defaultMaxBytes(root));
    }

    @Test
    void explicitArtifactPropertyOverridesTheTemporaryDefault() throws Exception {
        String previous = System.getProperty("metallum.renderContract.maxArtifactBytes");
        Path root = Files.createTempDirectory("metallum-render-contract-budget-override-");
        try {
            System.setProperty("metallum.renderContract.maxArtifactBytes", "12345");
            assertEquals(12345L, ValidationStorageBudget.shared(root).maxBytes());
        } finally {
            restoreProperty("metallum.renderContract.maxArtifactBytes", previous);
        }
    }

    @Test
    void atomicCheckpointRewritesCountOnlyCommittedBytes(@org.junit.jupiter.api.io.TempDir Path root) throws Exception {
        var budget = ValidationStorageBudget.shared(root, 32L);
        Path file = root.resolve("checkpoint.json");
        budget.writeAtomicBytes(file, new byte[]{1, 2, 3});
        budget.writeAtomicBytes(file, new byte[]{4, 5});
        assertEquals(2L, budget.artifactBytes());
        assertEquals(2L, Files.size(file));
        assertFalse(Files.exists(root.resolve("checkpoint.json.partial")));
    }

    @Test
    void failedAtomicCheckpointDoesNotReplacePriorEvidence(@org.junit.jupiter.api.io.TempDir Path root) throws Exception {
        var budget = ValidationStorageBudget.shared(root, 32L);
        Path file = root.resolve("checkpoint.json");
        budget.writeAtomicBytes(file, new byte[]{1, 2, 3});
        Files.writeString(root.resolve("checkpoint.json.partial"), "crash evidence");
        assertThrows(java.io.IOException.class, () -> budget.writeAtomicBytes(file, new byte[]{4}));
        assertEquals(3L, Files.size(file));
        assertEquals(3L, budget.artifactBytes());
        assertEquals("crash evidence", Files.readString(root.resolve("checkpoint.json.partial")));
    }

    private static void restoreProperty(final String name, final String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }
}

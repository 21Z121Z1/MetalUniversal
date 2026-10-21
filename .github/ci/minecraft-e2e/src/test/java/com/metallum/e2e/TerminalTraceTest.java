package com.metallum.e2e;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

final class TerminalTraceTest {
    @TempDir Path root;
    private static final String HEADER="\"status\":\"passed\",\"manifestComplete\":true,\"droppedEvents\":0,"
            +"\"forcedClosedPassCount\":0,\"invalidPassReferenceCount\":0";

    @Test void completeHeaderSkipsLargeProducerPayloadWithoutCopyingIt() throws Exception {
        Path file=root.resolve("trace.json");
        try(var writer=Files.newBufferedWriter(file)) {
            writer.write("{"+HEADER+",\"passes\":[");
            for(int i=0;i<100_000;i++) {
                if(i>0)writer.write(',');
                writer.write("{\"producer\":\""+"x".repeat(256)+"\"}");
            }
            writer.write("]}");
        }
        var header=TerminalTrace.read(file);TerminalTrace.requireComplete(header);
        assertFalse(header.has("passes"));assertEquals(5,header.size());
    }

    @Test void failedBudgetHeaderRetainsItsExactRequiredByteCount() throws Exception {
        Path file=root.resolve("budget.json");
        Files.writeString(file,"{\"status\":\"failed\",\"manifestComplete\":false,"
                +"\"manifestFailureReason\":\"render contract manifest byte budget exceeded\","
                +"\"requiredManifestBytes\":67108865}");
        var header=TerminalTrace.read(file);
        assertEquals(67108865L,header.get("requiredManifestBytes").getAsLong());
        assertThrows(IllegalStateException.class,()->TerminalTrace.requireComplete(header));
    }

    @Test void activeIncompleteDroppedOrMissingEvidenceCannotPass() throws Exception {
        for(String invalid:new String[]{HEADER.replace("passed","active"),HEADER.replace("true","false"),HEADER.replace("true","\"true\""),
                HEADER.replace("\"droppedEvents\":0","\"droppedEvents\":1"),"\"status\":\"passed\""}) {
            Path file=root.resolve("invalid.json");Files.writeString(file,"{"+invalid+"}");
            assertThrows(IllegalStateException.class,()->TerminalTrace.requireComplete(TerminalTrace.read(file)));
        }
    }

    @Test void duplicatedMalformedTruncatedAndTrailingDocumentsAreRejected() throws Exception {
        for(String invalid:new String[]{"{"+HEADER+",\"status\":\"active\"}","{"+HEADER+",\"passes\":[",
                "{"+HEADER+"} {}","{\"status\":[]}","{"+HEADER.replace("\"droppedEvents\":0","\"droppedEvents\":0.5")+"}"}) {
            Path file=root.resolve("invalid.json");Files.writeString(file,invalid);
            assertThrows(Exception.class,()->TerminalTrace.read(file));
        }
    }
}

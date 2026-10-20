package com.metallum.e2e;

import com.google.gson.JsonObject;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/** Reads terminal evidence without materializing the producer/resource payload. */
final class TerminalTrace {
    private static final Set<String> FIELDS=Set.of("schemaVersion","gitCommit","runId","status","manifestComplete",
            "frameCount","passCount","resourceCount","producerCount","droppedEvents","forcedClosedPassCount",
            "invalidPassReferenceCount","producerBudgetExceeded","tracePayloadOmitted","manifestFailureReason",
            "manifestRequiredBytes");

    static JsonObject read(Path path) throws IOException {
        JsonObject header=new JsonObject();
        Set<String> names=new HashSet<>();
        try(JsonReader reader=new JsonReader(Files.newBufferedReader(path))) {
            reader.beginObject();
            while(reader.hasNext()) {
                String name=reader.nextName();
                if(!names.add(name) || names.size()>256)throw new IllegalStateException("Ambiguous trace header: "+name);
                if(!FIELDS.contains(name)) {reader.skipValue();continue;}
                switch(reader.peek()) {
                    case STRING -> header.addProperty(name,reader.nextString());
                    case NUMBER -> header.addProperty(name,new java.math.BigDecimal(reader.nextString()).longValueExact());
                    case BOOLEAN -> header.addProperty(name,reader.nextBoolean());
                    default -> throw new IllegalStateException("Invalid trace header type: "+name);
                }
            }
            reader.endObject();
            if(reader.peek()!=JsonToken.END_DOCUMENT)throw new IllegalStateException("Trailing trace payload");
        }
        return header;
    }

    static void requireComplete(JsonObject header) {
        if(!header.has("status") || !header.getAsJsonPrimitive("status").isString()
                || !header.get("status").getAsString().equals("passed")
                || !header.has("manifestComplete") || !header.getAsJsonPrimitive("manifestComplete").isBoolean()
                || !header.get("manifestComplete").getAsBoolean())
            throw new IllegalStateException("Trace is not terminal passed: "+header);
        for(String name:new String[]{"droppedEvents","forcedClosedPassCount","invalidPassReferenceCount"})
            if(!header.has(name) || !header.getAsJsonPrimitive(name).isNumber() || header.get(name).getAsLong()!=0)
                throw new IllegalStateException("Trace has missing/nonzero "+name+": "+header);
    }

    private TerminalTrace() {}
}

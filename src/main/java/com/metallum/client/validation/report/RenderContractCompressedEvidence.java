package com.metallum.client.validation.report;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.FilterInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.zip.GZIPInputStream;

/** Independent reader of the bounded lossless trace representation. */
final class RenderContractCompressedEvidence {
    private static final long MAX_DECODED_BYTES=1024L*1024*1024;
    static JsonObject read(Path root,JsonObject index) throws IOException {
        try {
            String version=index.get("schemaVersion").getAsString();
            if(version.equals("1"))return index;
            if(!version.equals("2"))throw new IOException("Unsupported compressed trace schema");
            if(!index.get("payloadEncoding").getAsString().equals("gzip-json-v1")
                    || !index.has("tracePayloadOmitted") || index.get("tracePayloadOmitted").getAsBoolean())
                throw new IOException("Compressed trace is incomplete or unsupported");
            JsonObject descriptor=index.getAsJsonObject("payload"),limits=index.getAsJsonObject("limits");
            if(!descriptor.get("path").getAsString().equals("pass-manifest.payload.json.gz"))
                throw new IOException("Unsafe compressed trace path");
            Path path=root.resolve(descriptor.get("path").getAsString());
            if(Files.isSymbolicLink(path) || !path.toRealPath().getParent().equals(root.toRealPath()))
                throw new IOException("Compressed trace escapes its root");
            long bytes=positive(descriptor,"bytes"),decoded=positive(descriptor,"uncompressedBytes");
            long physicalLimit=positive(limits,"maxManifestBytes"),rawLimit=positive(limits,"maxUncompressedManifestBytes");
            if(rawLimit>MAX_DECODED_BYTES || decoded>rawLimit || Files.size(path)!=bytes
                    || Math.addExact(bytes,Files.size(root.resolve("pass-manifest.json")))>physicalLimit)
                throw new IOException("Compressed trace byte limits or counts differ");
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            try(InputStream input=Files.newInputStream(path)) {
                byte[] buffer=new byte[8192];for(int n;(n=input.read(buffer))!=-1;)digest.update(buffer,0,n);
            }
            if(!HexFormat.of().formatHex(digest.digest()).equals(descriptor.get("sha256").getAsString()))
                throw new IOException("Compressed trace digest mismatch");
            JsonObject payload;
            try(var input=new CountedInput(new GZIPInputStream(Files.newInputStream(path)),decoded);
                var reader=new InputStreamReader(input,StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT))) {
                payload=JsonParser.parseReader(reader).getAsJsonObject();
                if(input.read()!=-1 || input.count!=decoded)throw new IOException("Compressed trace decoded size differs");
            }
            if(payload.get("schemaVersion").getAsInt()!=1)throw new IOException("Unsupported compressed payload schema");
            for(String key:new String[]{"runId","gitCommit","status","manifestComplete","frameCount","passCount","resourceCount",
                    "producerCount","droppedEvents","forcedClosedPassCount","invalidPassReferenceCount","producerBudgetExceeded"})
                if(!index.has(key) || !index.get(key).equals(payload.get(key)))throw new IOException("Compressed trace header mismatch: "+key);
            for(String key:new String[]{"maxFrames","maxPasses","maxProducers","maxManifestBytes"})
                if(!limits.get(key).equals(payload.getAsJsonObject("limits").get(key)))throw new IOException("Compressed trace limit mismatch: "+key);
            if(index.getAsJsonObject("storageBudget").get("exceeded").getAsBoolean()
                    || payload.getAsJsonObject("storageBudget").get("exceeded").getAsBoolean())
                throw new IOException("Compressed trace storage budget failed");
            for(JsonObject document:new JsonObject[]{index,payload}) {
                var storage=document.getAsJsonObject("storageBudget");
                long used=storage.get("artifactBytes").getAsBigDecimal().longValueExact(),maximum=positive(storage,"maxBytes");
                if(used<0 || used>maximum)throw new IOException("Invalid compressed storage counters");
            }
            for(String[] pair:new String[][]{{"frameCount","frames"},{"passCount","passes"},{"resourceCount","resources"}})
                if(payload.get(pair[0]).getAsLong()!=payload.getAsJsonArray(pair[1]).size())
                    throw new IOException("Incomplete compressed trace array: "+pair[1]);
            return payload;
        } catch(IOException failure) {throw failure;}
        catch(Exception failure) {throw new IOException("Invalid compressed render-contract evidence",failure);}
    }
    private static long positive(JsonObject object,String name) throws IOException {
        if(!object.getAsJsonPrimitive(name).isNumber())throw new IOException("Invalid compressed byte field: "+name);
        long value=object.get(name).getAsBigDecimal().longValueExact();
        if(value<=0)throw new IOException("Invalid compressed byte field: "+name);return value;
    }
    private static final class CountedInput extends FilterInputStream {
        private final long limit;private long count;
        private CountedInput(InputStream input,long limit) {super(input);this.limit=limit;}
        private void consumed(int n) throws IOException {if(n>0 && (count=Math.addExact(count,n))>limit)throw new IOException("Compressed trace exceeds decoded bound");}
        @Override public int read() throws IOException {int value=super.read();if(value!=-1)consumed(1);return value;}
        @Override public int read(byte[] bytes,int offset,int length) throws IOException {int n=in.read(bytes,offset,length);consumed(n);return n;}
    }
    private RenderContractCompressedEvidence() {}
}

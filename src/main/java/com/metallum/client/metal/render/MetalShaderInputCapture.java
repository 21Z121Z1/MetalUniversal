package com.metallum.client.metal.render;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/** Hashes the actual reads of one compile; never treats a shader name as a content proof. */
final class MetalShaderInputCapture {
    private static final int MAX_SOURCE_BYTES=1024*1024, MAX_ENTRIES=256;
    private static final long MAX_TOTAL_BYTES=8L*1024*1024;
    record Input(long bytes,String sha256) {}
    record Snapshot(String contentSha256,Map<String,Input> shaders,Map<String,Input> includes,
                    boolean consistent,boolean sourceAdmissionGranted,String reason) {}
    private final Map<String,Input> shaders=new TreeMap<>(), includes=new TreeMap<>();
    private long totalBytes;
    private String failure;
    private Snapshot finished;

    synchronized void shader(String key,String source) {
        if(source==null) {reject("missing-shader-input");return;}
        if(source.length()>MAX_SOURCE_BYTES) {reject("shader-input-byte-limit");return;}
        record(shaders,key,ByteBuffer.wrap(source.getBytes(StandardCharsets.UTF_8)));
    }

    /** Borrows only the buffer view during this call; neither stores nor frees native include memory. */
    synchronized void include(String key,ByteBuffer content) {
        if(content==null) {reject("missing-include-input");return;}
        record(includes,key,content.duplicate());
    }

    private void record(Map<String,Input> target,String key,ByteBuffer content) {
        if(finished!=null)throw new IllegalStateException("Shader input capture already finished");
        if(content.remaining()>MAX_SOURCE_BYTES) {reject("shader-input-byte-limit");return;}
        var input=new Input(content.remaining(),sha256(content));
        Input previous=target.get(key);
        if(previous!=null) {
            if(!previous.equals(input))reject("shader-input-changed-during-compile");
            return;
        }
        if(shaders.size()+includes.size()>=MAX_ENTRIES || totalBytes+input.bytes()>MAX_TOTAL_BYTES) {
            reject("shader-input-capture-budget");return;
        }
        totalBytes+=input.bytes();target.put(key,input);
    }

    synchronized void reject(String reason) {
        if(finished!=null)throw new IllegalStateException("Shader input capture already finished");
        if(failure==null)failure=reason;
    }

    synchronized Snapshot finish() {
        if(finished==null) {
            String identity="shaders="+shaders+"\nincludes="+includes+"\nfailure="+failure;
            finished=new Snapshot(sha256(ByteBuffer.wrap(identity.getBytes(StandardCharsets.UTF_8))),
                    Map.copyOf(shaders),Map.copyOf(includes),failure==null && !shaders.isEmpty(),false,
                    failure==null ? "canonical-shader-and-include-content-not-yet-verified" : failure);
        }
        return finished;
    }

    private static String sha256(ByteBuffer bytes) {
        try {var hash=MessageDigest.getInstance("SHA-256");hash.update(bytes);return HexFormat.of().formatHex(hash.digest());}
        catch(NoSuchAlgorithmException impossible) {throw new AssertionError(impossible);}
    }
}

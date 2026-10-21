package com.metallum.e2e;

import com.google.gson.JsonObject;
import com.metallum.client.metal.render.MetalFxManager;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

/** Resource provenance after a raw capture. It is deliberately not a same-frame compile proof. */
final class RuntimeShaderSourceEvidence {
    private static JsonObject runtimeArtifact;

    static JsonObject capture(Minecraft client) {
        var result=new JsonObject();result.addProperty("diagnosticOnly",true);result.addProperty("sourceAdmissionGranted",false);
        result.addProperty("observation","resource-manager lookup after capture; not a same-frame compile-read proof");
        String source=System.getProperty("metallum.ci.expectedSourceSha","");
        if(!source.matches("[0-9a-f]{40}"))throw new IllegalStateException("Missing exact source for runtime shader diagnostics");
        result.addProperty("sourceCommit",source);result.addProperty("resourceLookupHistoryEpoch",MetalFxManager.scalingStatus().historyEpoch());
        result.add("runtimeMinecraftArtifact",artifact());var resources=new JsonObject();
        for(String name:new String[]{"animation_sprite.glsl","chunksection.glsl","dynamictransforms.glsl","fog.glsl",
                "globals.glsl","light.glsl","matrix.glsl","oit.glsl","oit_add_transmittance.glsl","oit_common.glsl",
                "oit_depth_bounds.glsl","oit_depth_sample.glsl","oit_sample.glsl","projection.glsl","sample_lightmap.glsl",
                "terrainglobals.glsl","texture_sampling.glsl"}) {
            var id=Identifier.fromNamespaceAndPath("minecraft","shaders/include/"+name);
            var resource=client.getResourceManager().getResource(id).orElseThrow(()->new IllegalStateException("Missing runtime shader resource "+id));
            try {
                String text=resource.readAllAsString();if(text.length()>1024*1024)throw new IOException("Runtime shader character budget exceeded");
                byte[] bytes=text.getBytes(StandardCharsets.UTF_8);if(bytes.length>1024*1024)throw new IOException("Runtime shader byte budget exceeded");
                var entry=new JsonObject();entry.addProperty("bytes",bytes.length);entry.addProperty("sha256",digest(bytes,"SHA-256"));
                entry.addProperty("sourcePackId",resource.sourcePackId());resources.add(id.toString(),entry);
            } catch(IOException failure) {throw new IllegalStateException("Runtime shader resource diagnostics failed for "+id,failure);}
        }
        result.add("resources",resources);return result;
    }

    private static synchronized JsonObject artifact() {
        if(runtimeArtifact!=null)return runtimeArtifact.deepCopy();
        var result=new JsonObject();
        try {
            var origin=Minecraft.class.getProtectionDomain().getCodeSource();
            if(origin==null || !origin.getLocation().getProtocol().equals("file")) {
                result.addProperty("available",false);result.addProperty("reason","runtime code source is not a file");
            } else {
                Path file=Path.of(origin.getLocation().toURI());
                if(!Files.isRegularFile(file) || Files.size(file)>512L*1024*1024) {
                    result.addProperty("available",false);result.addProperty("reason","runtime code source is not a bounded JAR");
                } else {
                    var sha1=MessageDigest.getInstance("SHA-1");var sha256=MessageDigest.getInstance("SHA-256");
                    try(var input=Files.newInputStream(file)) {
                        byte[] bytes=new byte[65536];int count;
                        while((count=input.read(bytes))>=0)if(count>0) {sha1.update(bytes,0,count);sha256.update(bytes,0,count);}
                    }
                    result.addProperty("available",true);result.addProperty("fileName",file.getFileName().toString());
                    result.addProperty("bytes",Files.size(file));result.addProperty("sha1",HexFormat.of().formatHex(sha1.digest()));
                    result.addProperty("sha256",HexFormat.of().formatHex(sha256.digest()));
                }
            }
        } catch(Exception unavailable) {
            result.addProperty("available",false);result.addProperty("reason",unavailable.getClass().getSimpleName());
        }
        runtimeArtifact=result;return result.deepCopy();
    }
    private static String digest(byte[] bytes,String algorithm) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(bytes));}
        catch(java.security.NoSuchAlgorithmException impossible) {throw new AssertionError(impossible);}
    }
    private RuntimeShaderSourceEvidence() {}
}

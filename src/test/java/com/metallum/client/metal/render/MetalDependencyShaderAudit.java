package com.metallum.client.metal.render;

import com.google.gson.JsonParser;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/** Inspects already-resolved, declared dependency bytes; does not download or admit shaders. */
final class MetalDependencyShaderAudit {
    static Map<String,Object> inspect(Map<String,Object> clientReference) throws Exception {
        var result=new LinkedHashMap<String,Object>();result.put("sourceAdmissionGranted",false);
        String manifest=System.getenv("METALLUM_SHADER_REFERENCE_DEPENDENCIES");
        if(manifest==null || manifest.isBlank()) {result.put("available",false);return result;}
        Path path=Path.of(manifest);if(Files.size(path)>2*1024*1024)throw new IOException("dependency-reference-manifest-budget");
        var input=JsonParser.parseString(Files.readString(path)).getAsJsonObject();
        if(!input.get("sourceCommit").getAsString().equals(System.getenv("METALLUM_SOURCE_SHA"))
                || !input.get("fabricApiVersion").getAsString().equals("0.160.5+26.3")
                || !input.get("sodiumVersion").getAsString().equals("mc26.3-0.9.2-fabric"))
            throw new IOException("dependency-reference-source-or-version");
        String home=System.getenv("GRADLE_USER_HOME");Path cache=(home==null ? Path.of(System.getProperty("user.home"),".gradle") : Path.of(home)).resolve("caches/fabric-loom");
        if(!Boolean.TRUE.equals(clientReference.get("canonicalClientJarVerified")))throw new IOException("missing-verified-client-reference");
        var variants=new ArrayList<Object>();var containers=new ArrayList<Object>();var seen=new java.util.HashSet<String>();
        try(var original=new ZipFile(cache.resolve((String)clientReference.get("clientJar")).toFile())) {
            for(var element:input.getAsJsonArray("artifacts")) {
                var artifact=element.getAsJsonObject();Path file=Path.of(artifact.get("file").getAsString());
                if(Files.size(file)>64*1024*1024)throw new IOException("dependency-jar-budget");
                byte[] bytes=Files.readAllBytes(file);
                String hash=sha(bytes);if(!hash.equals(artifact.get("sha256").getAsString()))throw new IOException("dependency-jar-digest-mismatch");
                if(!seen.add(hash))continue;
                var identity=new LinkedHashMap<String,Object>();
                for(String key:new String[]{"group","name","version","sha256","bytes"})identity.put(key,artifact.get(key).getAsString());
                containers.add(identity);scan(bytes,identity,original,variants,0);
            }
        }
        result.put("available",true);result.put("fabricApiVersion",input.get("fabricApiVersion").getAsString());
        result.put("sodiumVersion",input.get("sodiumVersion").getAsString());result.put("containers",containers);result.put("shaderVariants",variants);
        result.put("boundary","Declared artifact bytes and resource differences only. Unknown geometry/material semantics remain rejected; no observed hash is promoted.");
        return result;
    }

    private static void scan(byte[] jar,Map<String,Object> owner,ZipFile original,ArrayList<Object> variants,int depth) throws Exception {
        if(depth>2)throw new IOException("dependency-nested-jar-depth");
        var entries=new TreeMap<String,byte[]>();String modId="",modVersion="";long total=0;
        try(var zip=new ZipInputStream(new ByteArrayInputStream(jar))) {
            java.util.zip.ZipEntry entry;
            while((entry=zip.getNextEntry())!=null) {
                String name=entry.getName();boolean shader=name.contains("assets/minecraft/shaders/")
                        && (name.endsWith(".glsl") || name.endsWith(".vsh") || name.endsWith(".fsh"));
                boolean type=name.endsWith(".class");
                if(entry.isDirectory() || !(shader || type || name.endsWith(".jar") || name.equals("fabric.mod.json")))continue;
                int limit=name.endsWith(".jar") ? 32*1024*1024 : type ? 2*1024*1024 : 1024*1024;
                byte[] bytes=zip.readNBytes(limit+1);total+=bytes.length;
                if(bytes.length>limit || total>64L*1024*1024)throw new IOException("dependency-entry-byte-budget");
                if(type) {
                    String constants=new String(bytes,StandardCharsets.ISO_8859_1);
                    if(!(constants.contains("projection") || constants.contains("dynamictransforms") || constants.contains("texture_sampling")
                            || constants.contains("oit_common") || constants.contains("oit_depth_bounds")))continue;
                }
                entries.put(name,bytes);
            }
        }
        if(entries.containsKey("fabric.mod.json")) {
            var metadata=JsonParser.parseString(new String(entries.get("fabric.mod.json"),StandardCharsets.UTF_8)).getAsJsonObject();
            modId=metadata.get("id").getAsString();modVersion=metadata.get("version").getAsString();
        }
        for(var entry:entries.entrySet()) {
            String name=entry.getKey();byte[] bytes=entry.getValue();
            if(name.endsWith(".jar")) {
                var nested=new LinkedHashMap<>(owner);nested.put("nestedEntry",name);nested.put("nestedSha256",sha(bytes));
                scan(bytes,nested,original,variants,depth+1);continue;
            }
            if(name.endsWith(".class")) {
                variants.add(Map.of("kind","pinned-transform-bytecode","owner",owner,"modId",modId,"modVersion",modVersion,
                        "entry",name,"bytecode",MetalPrivateBindingHookDescriptorTest.includeProviderBytecode(bytes)));
                if(variants.size()>1024)throw new IOException("dependency-variant-count-budget");
                continue;
            }
            int start=name.indexOf("assets/minecraft/shaders/");if(start<0)continue;
            String canonical=name.substring(start);var baseline=original.getEntry(canonical);
            var description=new LinkedHashMap<String,Object>();description.put("owner",owner);description.put("modId",modId);
            description.put("modVersion",modVersion);description.put("entry",name);description.put("canonicalPath",canonical);
            description.put("bytes",bytes.length);description.put("sha256",sha(bytes));
            if(baseline!=null)try(var source=original.getInputStream(baseline)) {
                byte[] raw=source.readNBytes(1024*1024+1);if(raw.length>1024*1024)throw new IOException("original-source-byte-budget");
                description.put("originalSha256",sha(raw));description.put("difference",difference(new String(raw,StandardCharsets.UTF_8),new String(bytes,StandardCharsets.UTF_8)));
            }
            variants.add(description);if(variants.size()>1024)throw new IOException("dependency-variant-count-budget");
        }
    }
    private static Map<String,Object> difference(String before,String after) {
        int prefix=0;while(prefix<before.length() && prefix<after.length() && before.charAt(prefix)==after.charAt(prefix))prefix++;
        int suffix=0;while(suffix<before.length()-prefix && suffix<after.length()-prefix
                && before.charAt(before.length()-suffix-1)==after.charAt(after.length()-suffix-1))suffix++;
        String removed=before.substring(prefix,before.length()-suffix),added=after.substring(prefix,after.length()-suffix);
        return Map.of("commonPrefixChars",prefix,"commonSuffixChars",suffix,"removedText",removed.substring(0,Math.min(8192,removed.length())),
                "addedText",added.substring(0,Math.min(8192,added.length())),"truncated",removed.length()>8192 || added.length()>8192);
    }
    private static String sha(byte[] bytes) throws Exception {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    private MetalDependencyShaderAudit() {}
}

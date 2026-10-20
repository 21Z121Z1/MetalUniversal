package com.metallum.client.metal.render;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipFile;

/** Hash-only reference audit of already downloaded Loom inputs; performs no network requests. */
final class MetalMotionShaderAssetAudit {
    static Map<String,Object> inspect() throws Exception {
        var result=new LinkedHashMap<String,Object>();
        result.put("minecraftVersion","26.3");result.put("canonicalClientJarVerified",false);
        result.put("sourceAdmissionGranted",false);
        String home=System.getenv("GRADLE_USER_HOME");
        Path gradle=home==null || home.isBlank() ? Path.of(System.getProperty("user.home"),".gradle") : Path.of(home);
        Path cache=gradle.resolve("caches/fabric-loom");
        if(!Files.isDirectory(cache)) {result.put("reason","existing Loom cache is unavailable");return result;}
        var versionDirs=new ArrayList<Path>();
        try(var paths=Files.walk(cache,4)) {
            paths.filter(Files::isDirectory).filter(p->p.getFileName().toString().equals("26.3")).limit(16).forEach(versionDirs::add);
        }
        result.put("versionDirectories",versionDirs.stream().map(cache::relativize).map(Path::toString).toList());
        for(Path directory:versionDirs) {
            java.util.List<Path> files;
            try(var paths=Files.walk(directory,3)) {files=paths.filter(Files::isRegularFile).sorted().toList();}
            result.put("cacheFileNames",files.stream().map(directory::relativize).map(Path::toString).toList());
            for(Path metadata:files) {
                if(!metadata.getFileName().toString().endsWith(".json") || Files.size(metadata)>8*1024*1024)continue;
                JsonObject version;
                try {version=JsonParser.parseString(Files.readString(metadata)).getAsJsonObject();}
                catch(RuntimeException invalid) {continue;}
                if(!version.has("id") || !version.get("id").getAsString().equals("26.3") || !version.has("downloads"))continue;
                var downloads=version.getAsJsonObject("downloads");
                if(!downloads.has("client"))continue;
                var client=downloads.getAsJsonObject("client");
                if(!client.has("sha1") || !client.has("size"))continue;
                String expected=client.get("sha1").getAsString();long size=client.get("size").getAsLong();
                if(!expected.matches("[0-9a-f]{40}") || size<=0 || size>512L*1024*1024)continue;
                result.put("metadataFile",cache.relativize(metadata).toString());
                result.put("metadataSha256",digest(metadata,"SHA-256"));result.put("declaredClientSha1",expected);
                result.put("declaredClientBytes",size);
                for(Path jar:files) {
                    if(!jar.getFileName().toString().endsWith(".jar") || Files.size(jar)!=size)continue;
                    String actual=digest(jar,"SHA-1");
                    if(!actual.equals(expected))continue;
                    var assets=assets(jar);
                    if(assets.isEmpty())throw new IllegalStateException("Verified client has no core/include shader assets");
                    result.put("canonicalClientJarVerified",true);result.put("clientJar",cache.relativize(jar).toString());
                    result.put("actualClientSha1",actual);result.put("assets",assets);
                    result.put("reason","Existing client bytes match the pinned version metadata; content fingerprints only, no motion admission");
                    return result;
                }
            }
        }
        result.put("reason","No original client JAR matching the pinned metadata was found in the existing cache");
        return result;
    }

    private static Map<String,Object> assets(Path jar) throws Exception {
        var assets=new TreeMap<String,Object>();long total=0;
        var imports=java.util.regex.Pattern.compile("(?m)^\\s*#(?:include|moj_import)\\s+<([a-z0-9_./:-]+)>\\s*$");
        try(var zip=new ZipFile(jar.toFile())) {
            var entries=zip.entries();
            while(entries.hasMoreElements()) {
                var entry=entries.nextElement();String name=entry.getName();
                if(entry.isDirectory() || !(name.startsWith("assets/minecraft/shaders/core/")
                        || name.startsWith("assets/minecraft/shaders/include/"))
                        || !(name.endsWith(".vsh") || name.endsWith(".fsh") || name.endsWith(".glsl")))continue;
                byte[] bytes;
                try(var input=zip.getInputStream(entry)) {bytes=input.readNBytes(1024*1024+1);}
                total+=bytes.length;
                if(bytes.length>1024*1024 || total>16L*1024*1024)throw new IOException("Canonical shader audit byte limit exceeded");
                String text=new String(bytes,StandardCharsets.UTF_8);var matcher=imports.matcher(text);
                var dependencies=new ArrayList<String>();while(matcher.find())dependencies.add(matcher.group(1));
                assets.put(name,Map.of("bytes",bytes.length,"sha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),
                        "imports",dependencies,"locationMacros",text.contains("__FILE__") || text.contains("__LINE__")));
            }
        }
        return assets;
    }

    private static String digest(Path path,String algorithm) throws Exception {
        var digest=MessageDigest.getInstance(algorithm);
        try(var input=new DigestInputStream(Files.newInputStream(path),digest)) {input.transferTo(java.io.OutputStream.nullOutputStream());}
        return HexFormat.of().formatHex(digest.digest());
    }
    private MetalMotionShaderAssetAudit() {}
}

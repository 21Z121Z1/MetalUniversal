package com.metallum.client.metal.render;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Pinned client content reference. A match proves bytes, not complete producer or FG admission. */
final class MetalCanonicalShaderReference {
    static final String RESOURCE="/com/metallum/metalfx/minecraft-26.3-shader-fingerprints.json";
    static final String CLIENT_SHA1="e877b6a07acd633fb3bb475002175cec036e7b87";
    static final MetalCanonicalShaderReference CURRENT=load();
    record Asset(MetalShaderInputCapture.Input raw,MetalShaderInputCapture.Input expanded,
                 List<String> imports,boolean locationMacros) {
        Asset {imports=List.copyOf(imports);}
    }
    record Match(boolean canonicalInputsMatch,String reason,int shaderInputs,int includeInputs) {}
    private final Map<String,Asset> assets;
    private final String failure;

    private MetalCanonicalShaderReference(Map<String,Asset> assets,String failure) {
        this.assets=Map.copyOf(assets);this.failure=failure;
    }
    Map<String,Asset> assets() {return assets;}
    boolean ready() {return failure==null;}

    Match verify(MetalShaderInputCapture.Snapshot inputs,Set<String> expectedShaderKeys) {
        return verify(inputs,expectedShaderKeys,Map.of());
    }

    Match verify(MetalShaderInputCapture.Snapshot inputs,Set<String> expectedShaderKeys,
                 Map<String,MetalShaderInputCapture.Input> immutableLibraryIncludes) {
        if(failure!=null)return reject(inputs,"canonical-reference-unavailable:"+failure);
        if(inputs==null || !inputs.consistent())return reject(inputs,"missing-or-inconsistent-compile-inputs");
        if(expectedShaderKeys.isEmpty() || !expectedShaderKeys.equals(inputs.shaders().keySet()))
            return reject(inputs,"compiled-shader-stage-identity-mismatch");
        Set<String> requiredIncludes=new HashSet<>();
        for(var read:inputs.shaders().entrySet()) {
            String path=shaderAsset(read.getKey());Asset asset=assets.get(path);
            if(asset==null)return reject(inputs,"unreviewed-shader-input:"+read.getKey());
            if(asset.locationMacros())return reject(inputs,"location-dependent-shader-input:"+read.getKey());
            if(asset.expanded().equals(read.getValue()))continue;
            if(!asset.raw().equals(read.getValue()))return reject(inputs,"shader-content-mismatch:"+read.getKey());
            if(!collectImports(asset,requiredIncludes,new HashSet<>()))return reject(inputs,"unreviewed-include-dependency");
        }
        for(String id:requiredIncludes) {
            var observed=inputs.includes().get(id);
            if(observed!=null)continue; // A mismatching actual read must retain its own diagnostic below.
            observed=immutableLibraryIncludes.get(id);
            Asset asset=assets.get(includeAsset(id));
            if(observed==null || asset==null || !(asset.raw().equals(observed) || asset.expanded().equals(observed)))
                return reject(inputs,"canonical-include-content-not-observed");
        }
        for(var read:inputs.includes().entrySet()) {
            Asset asset=assets.get(includeAsset(read.getKey()));
            if(asset==null || asset.locationMacros() || !(asset.raw().equals(read.getValue()) || asset.expanded().equals(read.getValue())))
                return reject(inputs,"include-content-mismatch:"+read.getKey());
        }
        return new Match(true,"canonical-compile-inputs",inputs.shaders().size(),inputs.includes().size());
    }

    Match verifyLibrary(Map<String,MetalShaderInputCapture.Input> actual) {
        if(failure!=null)return new Match(false,"canonical-reference-unavailable:"+failure,0,0);
        if(!actual.keySet().equals(assets.keySet()))return new Match(false,"shader-library-asset-set-incomplete",0,0);
        int shaders=0,includes=0;
        for(var entry:actual.entrySet()) {
            Asset asset=assets.get(entry.getKey());
            if(!(asset.raw().equals(entry.getValue()) || asset.expanded().equals(entry.getValue())))
                return new Match(false,"shader-library-content-mismatch:"+entry.getKey(),shaders,includes);
            if(entry.getKey().contains("/include/"))includes++;else shaders++;
        }
        return new Match(true,"canonical-shader-library",shaders,includes);
    }

    private boolean collectImports(Asset asset,Set<String> result,Set<String> active) {
        for(String id:asset.imports()) {
            if(!active.add(id))return false;
            Asset dependency=assets.get(includeAsset(id));
            if(dependency==null || dependency.locationMacros() || !collectImports(dependency,result,active))return false;
            result.add(id);active.remove(id);
        }
        return true;
    }

    private static Match reject(MetalShaderInputCapture.Snapshot inputs,String reason) {
        return new Match(false,reason,inputs==null ? 0 : inputs.shaders().size(),inputs==null ? 0 : inputs.includes().size());
    }
    private static String shaderAsset(String key) {
        String suffix=key.startsWith("VERTEX:") ? ".vsh" : key.startsWith("FRAGMENT:") ? ".fsh" : null;
        if(suffix==null)return "";
        String id=key.substring(key.indexOf(':')+1);
        return id.startsWith("minecraft:core/") ? "assets/minecraft/shaders/"+id.substring("minecraft:".length())+suffix : "";
    }
    private static String includeAsset(String id) {
        return id.startsWith("minecraft:") ? "assets/minecraft/shaders/include/"+id.substring("minecraft:".length()) : "";
    }

    private static MetalCanonicalShaderReference load() {
        try(var stream=MetalCanonicalShaderReference.class.getResourceAsStream(RESOURCE)) {
            if(stream==null)throw new IOException("missing-packaged-reference");
            byte[] bytes=stream.readNBytes(512*1024+1);if(bytes.length>512*1024)throw new IOException("reference-byte-limit");
            var root=JsonParser.parseString(new String(bytes,StandardCharsets.UTF_8)).getAsJsonObject();
            if(root.get("schemaVersion").getAsBigDecimal().intValueExact()!=1 || !root.get("minecraftVersion").getAsString().equals("26.3")
                    || !root.get("clientJarSha1").getAsString().equals(CLIENT_SHA1)
                    || root.get("clientJarBytes").getAsBigDecimal().longValueExact()!=41483720L)
                throw new IOException("reference-version-or-client-identity");
            var assets=new TreeMap<String,Asset>();
            for(var entry:root.getAsJsonObject("assets").entrySet()) {
                String name=entry.getKey();
                if(!name.matches("assets/minecraft/shaders/(core|include)/[a-z0-9_./]+") || name.contains(".."))
                    throw new IOException("reference-asset-path");
                var value=entry.getValue().getAsJsonObject();var imports=new java.util.ArrayList<String>();
                value.getAsJsonArray("imports").forEach(id->imports.add(id.getAsString()));
                var raw=input(value.get("bytes").getAsBigDecimal().longValueExact(),value.get("sha256").getAsString());
                var expanded=input(value.get("expandedBytes").getAsBigDecimal().longValueExact(),value.get("expandedSha256").getAsString());
                if(!value.get("expandedStatus").getAsString().equals("exact-angle-import-expansion"))
                    throw new IOException("reference-expansion-not-verified");
                assets.put(name,new Asset(raw,expanded,imports,value.get("locationMacros").getAsBoolean()));
            }
            if(assets.size()!=80)throw new IOException("reference-asset-set-changed");
            return new MetalCanonicalShaderReference(assets,null);
        } catch(IOException | RuntimeException invalid) {
            return new MetalCanonicalShaderReference(Map.of(),invalid.getClass().getSimpleName()+":"+invalid.getMessage());
        }
    }
    private static MetalShaderInputCapture.Input input(long bytes,String sha256) throws IOException {
        if(bytes<0 || bytes>1024*1024 || !sha256.matches("[0-9a-f]{64}"))throw new IOException("invalid-reference-fingerprint");
        return new MetalShaderInputCapture.Input(bytes,sha256);
    }
}

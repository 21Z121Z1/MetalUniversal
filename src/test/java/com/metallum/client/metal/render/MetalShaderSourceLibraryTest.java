package com.metallum.client.metal.render;

import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.frontend.shaders.GlslCompiler;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;
import org.jspecify.annotations.Nullable;
import static org.junit.jupiter.api.Assertions.*;

/** Actual RenderPearl sources and owned native include records, with no GPU/device requirement. */
final class MetalShaderSourceLibraryTest {
    private static final Identifier ENTITY=Identifier.fromNamespaceAndPath("minecraft","core/entity");

    static ShaderSource rawLibrary(boolean changedRoot,boolean changedInclude) {
        return MetalBackend.prepareShaderSource(new RawSources(changedRoot,changedInclude));
    }

    static void verifyCanonicalRawLibraryWithInitializedDevice() throws com.mojang.renderpearl.util.ShaderCompileException {
        var original=new RawSources(false,false);
        try(ShaderSource source=MetalBackend.prepareShaderSource(original);var compiler=new GlslCompiler(true,true)) {
            var owner=MetalShaderSourceAdapters.libraryProof(source);assertNotNull(owner);
            assertTrue(owner.canonicalOpen(),owner.reason());
            var compile=MetalBackend.captureCompileInputs(source);
            var pipeline=MetalShaderCompilerFixtures.canonicalRawLibrary();
            for(var stage:new ShaderType[]{ShaderType.VERTEX,ShaderType.FRAGMENT}) {
                String glsl=compile.getShader(ENTITY,stage);assertNotNull(glsl);
                try(var module=compiler.compileToSpv(ENTITY.toString(),glsl,stage,pipeline.getShaderDefines(),compile)) {
                    assertNotNull(module); // Compilation failure throws; no mock shader intermediate is substituted.
                }
            }
            var inputs=MetalShaderSourceAdapters.compiledInputs(compile);assertNotNull(inputs);
            assertTrue(inputs.consistent());assertFalse(inputs.includes().isEmpty());
            assertTrue(owner.verify(inputs,Set.of("VERTEX:"+ENTITY,"FRAGMENT:"+ENTITY)).canonicalInputsMatch());
            assertFalse(inputs.sourceAdmissionGranted());
        }
        assertEquals(1,original.closes);
    }

    @Test void sameIdentifierSourceAndIncludeReplacementsStayRejected() {
        for(boolean include:new boolean[]{false,true}) {
            var original=new RawSources(!include,include);
            try(ShaderSource source=MetalBackend.prepareShaderSource(original)) {
                var owner=MetalShaderSourceAdapters.libraryProof(source);assertNotNull(owner);
                assertFalse(owner.canonicalOpen(),"The same minecraft Identifier cannot substitute for matching bytes");
                assertTrue(owner.reason().contains("content-mismatch"),owner.reason());
            }
            assertEquals(1,original.closes);
        }
    }

    @Test void generatedSourcesBorrowTheActualIncludeOwnerWithoutClosingOrRevivingIt() {
        var original=new RawSources(false,false);ShaderSource library=MetalBackend.prepareShaderSource(original);
        var owner=MetalShaderSourceAdapters.libraryProof(library);assertNotNull(owner);
        var generated=Identifier.fromNamespaceAndPath("metallum","generated/source");
        ShaderSource overlay=MetalShaderSourceAdapters.overlay(Map.of(generated,"// generated shader\n"),library);
        try {
            assertSame(owner,MetalShaderSourceAdapters.libraryProof(overlay));
            assertNotNull(overlay.getInclude(Identifier.fromNamespaceAndPath("minecraft","fog.glsl")));
            overlay.close();assertEquals(0,original.closes);assertTrue(owner.canonicalOpen());
            library.close();assertEquals(1,original.closes);
            assertThrows(IllegalStateException.class,()->overlay.getInclude(Identifier.fromNamespaceAndPath("minecraft","fog.glsl")));
            assertThrows(IllegalStateException.class,()->overlay.getShader(generated,ShaderType.VERTEX));
        } finally {library.close();}
    }

    @Test void closeInvalidatesCapturedOldInputsAndAnotherReloadCannotReviveThem() {
        var original=new RawSources(false,false);ShaderSource older=MetalBackend.prepareShaderSource(original);
        var owner=MetalShaderSourceAdapters.libraryProof(older);assertNotNull(owner);
        var compile=MetalBackend.captureCompileInputs(older);
        compile.getShader(ENTITY,ShaderType.VERTEX);compile.getShader(ENTITY,ShaderType.FRAGMENT);
        var inputs=MetalShaderSourceAdapters.compiledInputs(compile);assertNotNull(inputs);
        assertTrue(owner.verify(inputs,Set.of("VERTEX:"+ENTITY,"FRAGMENT:"+ENTITY)).canonicalInputsMatch());
        older.close();older.close();assertEquals(1,original.closes);
        assertFalse(owner.verify(inputs,Set.of("VERTEX:"+ENTITY,"FRAGMENT:"+ENTITY)).canonicalInputsMatch());
        assertThrows(IllegalStateException.class,()->older.getShader(ENTITY,ShaderType.VERTEX));
        assertThrows(IllegalStateException.class,()->older.getInclude(Identifier.fromNamespaceAndPath("minecraft","fog.glsl")));
        try(ShaderSource newer=MetalBackend.prepareShaderSource(new RawSources(false,false))) {
            var newOwner=MetalShaderSourceAdapters.libraryProof(newer);assertNotNull(newOwner);
            assertTrue(newOwner.canonicalOpen());assertNotEquals(owner.generation(),newOwner.generation());
            assertFalse(owner.canonicalOpen());
        }
    }

    @Test void compilerTaskRetainsNativeIncludeAcrossTheAccessorReturnUntilItFinishes() throws Exception {
        var original=new RawSources(false,false);ShaderSource source=MetalBackend.prepareShaderSource(original);
        var owner=MetalShaderSourceAdapters.libraryProof(source);assertNotNull(owner);
        var includeId=Identifier.fromNamespaceAndPath("minecraft","fog.glsl");
        var entered=new java.util.concurrent.CountDownLatch(1);
        var release=new java.util.concurrent.CountDownLatch(1);
        var closingStarted=new java.util.concurrent.CountDownLatch(1);
        try(var workers=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var capture=MetalBackend.captureCompileInputs(source);
            var compile=java.util.concurrent.CompletableFuture.runAsync(()->{
                var include=capture.getInclude(includeId);assertNotNull(include);
                var bytes=MetalShaderSourceAdapters.includeContent(include);
                assertNotNull(bytes);entered.countDown();
                try {assertTrue(release.await(5,java.util.concurrent.TimeUnit.SECONDS));}
                catch(InterruptedException interrupted) {Thread.currentThread().interrupt();throw new AssertionError(interrupted);}
                assertEquals(MetalCanonicalShaderReference.CURRENT.assets().get("assets/minecraft/shaders/include/fog.glsl").raw(),
                        MetalShaderInputCapture.fingerprint(bytes));
            },MetalBackend.captureCompileExecutor(capture,workers));
            assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS));
            var close=java.util.concurrent.CompletableFuture.runAsync(()->{closingStarted.countDown();source.close();},workers);
            try {
                assertTrue(closingStarted.await(5,java.util.concurrent.TimeUnit.SECONDS));
                assertThrows(java.util.concurrent.TimeoutException.class,()->close.get(100,java.util.concurrent.TimeUnit.MILLISECONDS));
                assertFalse(owner.closed(),"Closing cannot free an include while shaderc may still use the returned pointer");
            } finally {release.countDown();}
            compile.get(5,java.util.concurrent.TimeUnit.SECONDS);close.get(5,java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(owner.closed());assertEquals(1,original.closes);
        } finally {release.countDown();source.close();}
    }

    @Test void closedSourceAndFailedCompilerTasksCompleteWithoutStrandingOrLeakingTheLease() throws Exception {
        var original=new RawSources(false,false);ShaderSource source=MetalBackend.prepareShaderSource(original);
        try(var worker=java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var compileExecutor=MetalBackend.captureCompileExecutor(source,worker);
            var failed=java.util.concurrent.CompletableFuture.runAsync(()->{throw new IllegalArgumentException("compiler failed");},compileExecutor);
            var failure=assertThrows(java.util.concurrent.ExecutionException.class,()->failed.get(5,java.util.concurrent.TimeUnit.SECONDS));
            assertInstanceOf(IllegalArgumentException.class,failure.getCause());
            source.close();assertEquals(1,original.closes);
            var late=java.util.concurrent.CompletableFuture.runAsync(()->source.getInclude(Identifier.fromNamespaceAndPath("minecraft","fog.glsl")),compileExecutor);
            var closed=assertThrows(java.util.concurrent.ExecutionException.class,()->late.get(5,java.util.concurrent.TimeUnit.SECONDS));
            assertInstanceOf(IllegalStateException.class,closed.getCause());
        } finally {source.close();}
    }

    private static final class RawSources implements ShaderSource {
        private final boolean changedRoot,changedInclude;
        private final Map<Identifier,CachedIncludeSource> includes=new HashMap<>();
        private int closes;
        private RawSources(boolean changedRoot,boolean changedInclude) {this.changedRoot=changedRoot;this.changedInclude=changedInclude;}
        @Override public @Nullable String getShader(Identifier id,ShaderType stage) {
            String result=resource("assets/"+id.getNamespace()+"/shaders/"+id.getPath()+(stage==ShaderType.VERTEX ? ".vsh" : ".fsh"));
            return result!=null && changedRoot && id.equals(ENTITY) ? result+"\n// same-ID replacement\n" : result;
        }
        @Override public @Nullable CachedIncludeSource getInclude(Identifier id) {
            return includes.computeIfAbsent(id,key->{
                String text=resource("assets/"+key.getNamespace()+"/shaders/include/"+key.getPath());
                if(text==null)return null;
                if(changedInclude && key.getPath().equals("fog.glsl"))text+="\n// same-ID include replacement\n";
                return CachedIncludeSource.create(key,text);
            });
        }
        @Override public void close() {closes++;includes.values().forEach(CachedIncludeSource::close);includes.clear();}
        private static @Nullable String resource(String name) {
            try(var input=MetalShaderSourceLibraryTest.class.getClassLoader().getResourceAsStream(name)) {
                return input==null ? null : new String(input.readAllBytes(),StandardCharsets.UTF_8);
            } catch(IOException failure) {throw new UncheckedIOException(failure);}
        }
    }
}

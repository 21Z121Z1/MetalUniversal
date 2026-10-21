package com.metallum.client.metal.render;

import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.function.BiFunction;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Small 26.3 adapter for generated GLSL layered over Mojang's source provider. */
final class MetalShaderSourceAdapters {
    private MetalShaderSourceAdapters() {
    }

    static ShaderSource prepareLibrary(ShaderSource source) {
        if(source instanceof LibrarySource || source instanceof ClasspathSource)return source;
        return libraryLifetime(source)!=null ? withClasspathFallback(source) : new LibrarySource(withClasspathFallback(source));
    }

    static @Nullable MetalShaderLibraryLifetime libraryLifetime(@Nullable ShaderSource source) {
        if(source instanceof CompilationSource compile)return libraryLifetime(compile.delegate);
        if(source instanceof ClasspathSource fallback)return libraryLifetime(fallback.delegate);
        return source instanceof MetalShaderLibraryLifetime.Provider owner ? owner.metallum$shaderLibraryLifetime() : null;
    }

    static @Nullable MetalShaderLibraryProof libraryProof(@Nullable ShaderSource source) {
        var lifetime=libraryLifetime(source);return lifetime==null ? null : lifetime.proof();
    }

    /** The lease spans the compiler task, including shaderc's use after getInclude returns. */
    static java.util.concurrent.Executor compilationExecutor(ShaderSource source,java.util.concurrent.Executor executor) {
        // Let the future's own task observe closed-source errors through getShader/getInclude.
        // Throwing before AsyncSupply.run would strand its CompletableFuture forever.
        return task->executor.execute(()->withLibraryLease(source,()->{task.run();return null;},false));
    }

    static <T> T compileWithLibrary(ShaderSource source,java.util.function.Supplier<T> work) {
        return withLibraryLease(source,work,true);
    }

    private static <T> T withLibraryLease(ShaderSource source,java.util.function.Supplier<T> work,boolean requireOpen) {
        var lifetime=libraryLifetime(source);
        return lifetime==null ? work.get() : lifetime.read(work,requireOpen);
    }

    static MetalShaderLibraryProof inspectLibrary(ShaderSource delegate) {
        var inputs=new java.util.TreeMap<String,MetalShaderInputCapture.Input>();
        String failure=null;
        for(String path:new java.util.TreeSet<>(MetalCanonicalShaderReference.CURRENT.assets().keySet())) {
            try {
                String prefix="assets/minecraft/shaders/";String file=path.substring(prefix.length());
                java.nio.ByteBuffer content;
                if(file.startsWith("include/")) {
                    var include=delegate.getInclude(Identifier.fromNamespaceAndPath("minecraft",file.substring("include/".length())));
                    content=include==null || include.includeResultPtr()==0 ? null
                            : includeContent(include);
                } else {
                    ShaderType type=file.endsWith(".vsh") ? ShaderType.VERTEX : ShaderType.FRAGMENT;
                    String text=delegate.getShader(Identifier.fromNamespaceAndPath("minecraft",file.substring(0,file.length()-4)),type);
                    if(text!=null && text.length()>1024*1024)throw new IllegalArgumentException("shader-library-input-byte-limit");
                    content=text==null ? null : java.nio.ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8));
                }
                if(content==null) {failure="shader-library-input-missing:"+path;break;}
                inputs.put(path,MetalShaderInputCapture.fingerprint(content));
            } catch(RuntimeException invalid) {failure="shader-library-input-read-failed:"+path+":"+invalid.getClass().getSimpleName();break;}
        }
        return new MetalShaderLibraryProof(MetalCanonicalShaderReference.CURRENT,inputs,failure);
    }

    private static final class LibrarySource implements ShaderSource,MetalShaderLibraryLifetime.Provider {
        private final ShaderSource delegate;
        private final MetalShaderLibraryLifetime lifetime;
        private LibrarySource(ShaderSource delegate) {this.delegate=delegate;lifetime=new MetalShaderLibraryLifetime(delegate);}
        @Override public MetalShaderLibraryLifetime metallum$shaderLibraryLifetime() {return lifetime;}
        @Override public @Nullable String getShader(Identifier id,ShaderType type) {
            return compileWithLibrary(this,()->delegate.getShader(id,type));
        }
        @Override public @Nullable CachedIncludeSource getInclude(Identifier id) {
            return compileWithLibrary(this,()->delegate.getInclude(id));
        }
        @Override public void close() {lifetime.close(delegate::close);}
    }

    /** Each pending compile owns this capture; an async completion never consults the live library. */
    static ShaderSource captureCompileInputs(ShaderSource source) {
        return source instanceof CompilationSource ? source : new CompilationSource(source);
    }

    static String compileProviderType(ShaderSource source) {
        while(source instanceof CompilationSource compile)source=compile.delegate;
        return source.getClass().getName();
    }

    static MetalShaderInputCapture.@Nullable Snapshot compiledInputs(ShaderSource source) {
        return source instanceof CompilationSource capture ? capture.inputs.finish() : null;
    }

    private static final class CompilationSource implements ShaderSource {
        private final ShaderSource delegate;
        private final MetalShaderInputCapture inputs=new MetalShaderInputCapture();
        private CompilationSource(ShaderSource delegate) {this.delegate=delegate;}
        @Override public @Nullable String getShader(Identifier id,ShaderType type) {
            return compileWithLibrary(delegate,()->{
                String text=delegate.getShader(id,type);inputs.shader(type.name()+":"+id,text);return text;
            });
        }
        @Override public @Nullable CachedIncludeSource getInclude(Identifier id) {
            return compileWithLibrary(delegate,()->{
                CachedIncludeSource include=delegate.getInclude(id);captureIncludeBytes(id,include,inputs);return include;
            });
        }
        @Override public void close() {
            // The original provider and its native include allocations remain owned by the library.
        }
    }

    private static void captureIncludeBytes(Identifier id,ShaderSource.@Nullable CachedIncludeSource include,MetalShaderInputCapture inputs) {
        if(include==null || include.includeResultPtr()==0)inputs.reject("missing-include-input");
        else inputs.include(id.toString(),includeContent(include));
    }

    /** CachedIncludeSource uses memUTF8(text,false); content() incorrectly scans for a terminator. */
    static java.nio.ByteBuffer includeContent(ShaderSource.CachedIncludeSource include) {
        if(include.includeResultPtr()==0)throw new IllegalArgumentException("missing-include-input");
        var result=org.lwjgl.util.shaderc.ShadercIncludeResult.create(include.includeResultPtr());
        long length=result.content_length();
        if(length<0 || length>1024*1024)throw new IllegalArgumentException("shader-input-byte-limit");
        if(length==0)return java.nio.ByteBuffer.allocate(0);
        long address=org.lwjgl.system.MemoryUtil.memGetAddress(result.address()+org.lwjgl.util.shaderc.ShadercIncludeResult.CONTENT);
        if(address==0)throw new IllegalArgumentException("missing-include-input");
        return org.lwjgl.system.MemoryUtil.memByteBuffer(address,(int)length);
    }

    static ShaderSource overlay(
            final Map<Identifier, String> generated,
            @Nullable final ShaderSource fallback
    ) {
        return withIncludeLifetime(new ShaderSource() {
            @Override
            public @Nullable String getShader(final Identifier id, final ShaderType type) {
                String source = generated.get(id);
                return source != null ? source : fallback == null ? null : fallback.getShader(id, type);
            }

            @Override
            public @Nullable CachedIncludeSource getInclude(final Identifier id) {
                return fallback == null ? null : fallback.getInclude(id);
            }

            @Override
            public void close() {
                // The generated map and fallback provider are owned by the caller.
            }
        },fallback);
    }

    /** A generated provider borrows its fallback's includes; closing it does not close that owner. */
    static ShaderSource withIncludeLifetime(ShaderSource source,@Nullable ShaderSource includeOwner) {
        var lifetime=libraryLifetime(includeOwner);
        return lifetime==null ? source : new BorrowingSource(source,lifetime);
    }

    private static final class BorrowingSource implements ShaderSource,MetalShaderLibraryLifetime.Provider {
        private final ShaderSource delegate;
        private final MetalShaderLibraryLifetime lifetime;
        private BorrowingSource(ShaderSource delegate,MetalShaderLibraryLifetime lifetime) {this.delegate=delegate;this.lifetime=lifetime;}
        @Override public MetalShaderLibraryLifetime metallum$shaderLibraryLifetime() {return lifetime;}
        @Override public @Nullable String getShader(Identifier id,ShaderType type) {return lifetime.read(()->delegate.getShader(id,type),true);}
        @Override public @Nullable CachedIncludeSource getInclude(Identifier id) {return lifetime.read(()->delegate.getInclude(id),true);}
        @Override public void close() {delegate.close();}
    }

    /**
     * Adapts the one-function shader lookup used by small backend fixtures to
     * RenderPearl 26.3's shader/include provider.  The production frontend
     * always supplies a complete provider; this seam is intentionally limited
     * to tests that compile generated GLSL strings directly.
     */
    static ShaderSource from(
            final BiFunction<Identifier, ShaderType, @Nullable String> shaderLookup
    ) {
        return new ShaderSource() {
            @Override
            public @Nullable String getShader(final Identifier id, final ShaderType type) {
                return shaderLookup.apply(id, type);
            }

            @Override
            public @Nullable CachedIncludeSource getInclude(final Identifier id) {
                return null;
            }

            @Override
            public void close() {
            }
        };
    }

    static ShaderSource empty() {
        return from((identifier, type) -> null);
    }

    /**
     * Keeps the live Minecraft provider authoritative while making the
     * backend-owned sources available in Loom's directory-based dev runtime.
     * Fabric's production jar exposes the same files through its resource pack;
     * this fallback only runs when the provider reports a missing shader.
     */
    static ShaderSource withClasspathFallback(final ShaderSource delegate) {
        return delegate instanceof ClasspathSource ? delegate : new ClasspathSource(delegate);
    }

    private static final class ClasspathSource implements ShaderSource {
        private final ShaderSource delegate;
        private ClasspathSource(ShaderSource delegate) {this.delegate=delegate;}
            @Override
            public @Nullable String getShader(final Identifier id, final ShaderType type) {
                String source = delegate.getShader(id, type);
                if (source != null) {
                    return source;
                }
                Identifier file = type.idConverter().idToFile(id);
                String resourceName = "assets/" + file.getNamespace() + "/" + file.getPath();
                try (InputStream stream = MetalShaderSourceAdapters.class.getClassLoader()
                        .getResourceAsStream(resourceName)) {
                    return stream == null ? null : new String(stream.readAllBytes(), StandardCharsets.UTF_8);
                } catch (IOException exception) {
                    return null;
                }
            }

            @Override
            public @Nullable CachedIncludeSource getInclude(final Identifier id) {
                return delegate.getInclude(id);
            }

            @Override
            public void close() {
                delegate.close();
            }
    }
}

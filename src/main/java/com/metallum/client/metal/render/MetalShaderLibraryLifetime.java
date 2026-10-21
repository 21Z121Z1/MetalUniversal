package com.metallum.client.metal.render;

import com.mojang.renderpearl.api.pipeline.ShaderSource;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;

/** One source owner's native lifetime, shared by prepared and lazy compiler tasks. */
public final class MetalShaderLibraryLifetime {
    public interface Provider {
        MetalShaderLibraryLifetime metallum$shaderLibraryLifetime();
    }
    private final ReentrantReadWriteLock lifetime=new ReentrantReadWriteLock(true);
    private final MetalShaderLibraryProof proof;
    private final String ownerType;
    private volatile boolean retirementRequested;

    public MetalShaderLibraryLifetime(ShaderSource source) {
        ownerType=source.getClass().getName();
        proof=MetalShaderSourceAdapters.inspectLibrary(source);
    }

    MetalShaderLibraryProof proof() {return proof;}
    boolean retirementRequested() {return retirementRequested;}
    String ownerType() {return ownerType;}

    <T> T read(Supplier<T> work,boolean requireOpen) {
        var lease=lifetime.readLock();lease.lock();
        try {
            if(requireOpen && proof.closed())throw new IllegalStateException("Shader library is closed");
            return work.get();
        } finally {lease.unlock();}
    }

    public void close(Runnable releaseNativeIncludes) {
        retirementRequested=true;
        var retirement=lifetime.writeLock();retirement.lock();
        try {if(proof.close())releaseNativeIncludes.run();}
        finally {retirement.unlock();}
    }
}

package com.metallum.client.metal.render;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;

/** Test-only controller: the Fabric test thread must pump ticks, not poll a blocked client. */
final class MetalShaderReloadController {
    static CompletableFuture<Void> observeRetirement(Executor worker,BooleanSupplier requested,
            BooleanSupplier closed,CompletableFuture<?> compiler,Runnable release,long timeoutMillis) {
        checkTimeout(timeoutMillis);
        return CompletableFuture.runAsync(()->{
            long start=System.nanoTime();
            try {
                while(!requested.getAsBoolean()) {
                    if(compiler.isDone()) {
                        compiler.join();
                        throw new IllegalStateException("Compiler stopped borrowing before the Configs close request");
                    }
                    if(System.nanoTime()-start>=TimeUnit.MILLISECONDS.toNanos(timeoutMillis))
                        throw new IllegalStateException("Actual Configs close was not requested during the borrow");
                    Thread.sleep(1);
                }
                if(closed.getAsBoolean() || compiler.isDone())
                    throw new IllegalStateException("Actual Configs freed includes while shaderc still borrowed them");
            } catch(InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Actual Configs retirement observer interrupted",interrupted);
            } finally {release.run();}
        },worker);
    }

    static void awaitReload(Runnable pumpReload,CompletableFuture<Void> observer,
            CompletableFuture<?> compiler,long timeoutMillis)
            throws InterruptedException,ExecutionException,TimeoutException {
        checkTimeout(timeoutMillis);
        // Fabric's client waits during PHASE_TEST. Pump first; the independent observer
        // releases shaderc even when a client tick synchronously waits inside Configs.close.
        pumpReload.run();
        observer.get(timeoutMillis+5_000,TimeUnit.MILLISECONDS);
        compiler.get(5,TimeUnit.SECONDS);
    }

    private static void checkTimeout(long value) {
        if(value<=0 || value>120_000)throw new IllegalArgumentException("Invalid reload observation timeout");
    }
    private MetalShaderReloadController() {}
}

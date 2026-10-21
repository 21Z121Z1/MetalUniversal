package com.metallum.client.metal.render;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Cooperative scheduler controls, not a claim about actual Minecraft/native lifetime. */
final class MetalShaderReloadControllerTest {
    @Test void cooperativeClientIsPumpedWhileAnotherWorkerObservesTheBlockingClose() throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var requested=new AtomicBoolean();var closed=new AtomicBoolean();
        try(var workers=Executors.newFixedThreadPool(2)) {
            var compiler=CompletableFuture.runAsync(()->{
                entered.countDown();
                try {assertTrue(release.await(5,TimeUnit.SECONDS));}
                catch(InterruptedException failure) {Thread.currentThread().interrupt();throw new AssertionError(failure);}
                assertFalse(closed.get(),"The borrower must finish before native retirement");
            },workers);
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            var observer=MetalShaderReloadController.observeRetirement(workers,requested::get,closed::get,
                    compiler,release::countDown,2_000);
            try {
                MetalShaderReloadController.awaitReload(()->{
                    // Models Fabric: this queued client task cannot start before the test pumps.
                    requested.set(true);
                    compiler.join(); // A real Configs.close waits for this borrow on the client.
                    closed.set(true);
                },observer,compiler,2_000);
                assertTrue(closed.get());assertTrue(observer.isDone());
            } finally {release.countDown();}
        }
    }

    @Test void prematureNativeRetirementIsRejectedAndStillReleasesTheBorrower() throws Exception {
        var releases=new AtomicInteger();
        try(var worker=Executors.newSingleThreadExecutor()) {
            var observer=MetalShaderReloadController.observeRetirement(worker,()->true,()->true,
                    new CompletableFuture<>(),releases::incrementAndGet,1_000);
            var failure=assertThrows(ExecutionException.class,()->observer.get(5,TimeUnit.SECONDS));
            assertTrue(failure.getCause().getMessage().contains("freed includes"));assertEquals(1,releases.get());
        }
    }

    @Test void compilerCompletionCannotMasqueradeAsAnOverlappingClose() throws Exception {
        var releases=new AtomicInteger();
        try(var worker=Executors.newSingleThreadExecutor()) {
            var observer=MetalShaderReloadController.observeRetirement(worker,()->false,()->false,
                    CompletableFuture.completedFuture(null),releases::incrementAndGet,1_000);
            var failure=assertThrows(ExecutionException.class,()->observer.get(5,TimeUnit.SECONDS));
            assertTrue(failure.getCause().getMessage().contains("stopped borrowing"));assertEquals(1,releases.get());
        }
    }

    @Test void aMissingCloseRequestFailsWithItsCauseAndAlwaysReleasesTheBorrower() throws Exception {
        var releases=new AtomicInteger();
        try(var worker=Executors.newSingleThreadExecutor()) {
            var observer=MetalShaderReloadController.observeRetirement(worker,()->false,()->false,
                    new CompletableFuture<>(),releases::incrementAndGet,25);
            var failure=assertThrows(ExecutionException.class,()->observer.get(5,TimeUnit.SECONDS));
            assertTrue(failure.getCause().getMessage().contains("close was not requested"));assertEquals(1,releases.get());
        }
    }
}

package com.metallum.client.metal.render;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.frontend.shaders.GlslCompiler;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.resources.Identifier;

/** Forces an actual Configs close request while shaderc still borrows its native include. */
final class MetalShaderReloadLeaseEvidence {
    private static final Identifier FOG=Identifier.fromNamespaceAndPath("minecraft","fog.glsl");
    private static final String GLSL="""
            #version 450
            #include <minecraft:fog.glsl>
            layout(location=0) in vec3 Position;
            void main() { gl_Position=vec4(Position,1.0); }
            """;

    static Map<String,Object> reload(ClientGameTestContext context) {
        ShaderSource source=context.computeOnClient(client->{
            var device=MetalDevice.current();require(device!=null,"Missing actual Metal device for reload lease");
            return device.activeShaderSource();
        });
        var owner=MetalShaderSourceAdapters.libraryLifetime(source);
        require(owner!=null && owner.proof().canonicalOpen(),"Current library is not canonical/open");
        require(owner.ownerType().equals("net.minecraft.client.renderer.ShaderManager$Configs"),
                "The concurrency control must use the actual Configs owner: "+owner.ownerType());
        var expected=MetalCanonicalShaderReference.CURRENT.assets().get("assets/minecraft/shaders/include/fog.glsl").raw();
        var entered=new CountDownLatch(1);var continueBorrow=new CountDownLatch(1);
        var callbacks=new AtomicInteger();var problem=new AtomicReference<String>();
        var worker=Executors.newSingleThreadExecutor();
        try {
            ShaderSource blocking=new ShaderSource() {
                @Override public String getShader(Identifier id,ShaderType type) {return GLSL;}
                @Override public CachedIncludeSource getInclude(Identifier id) {
                    var include=source.getInclude(id);callbacks.incrementAndGet();
                    if(!id.equals(FOG) || include==null) {
                        problem.compareAndSet(null,"unexpected or missing actual include: "+id);return include;
                    }
                    entered.countDown();
                    try {
                        if(!continueBorrow.await(30,TimeUnit.SECONDS))problem.compareAndSet(null,"reload lease release timed out");
                    } catch(InterruptedException interrupted) {
                        Thread.currentThread().interrupt();problem.compareAndSet(null,"native include callback interrupted");
                    }
                    if(!expected.equals(MetalShaderInputCapture.fingerprint(MetalShaderSourceAdapters.includeContent(include))))
                        problem.compareAndSet(null,"native include changed before shaderc consumed it");
                    return include;
                }
                @Override public void close() {}
            };
            var compiled=CompletableFuture.runAsync(()->{
                var defines=RenderPipeline.builder().withLocation("metallum/reload_include_lease")
                        .withVertexShader("metallum:reload_include_lease").withFragmentShader("metallum:reload_include_lease")
                        .withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withColorTargetState(ColorTargetState.DEFAULT)
                        .build().getShaderDefines();
                try(var compiler=new GlslCompiler(true,true);
                    var module=compiler.compileToSpv("metallum:reload_include_lease",GLSL,ShaderType.VERTEX,defines,blocking)) {
                    require(module!=null,"Actual shaderc produced no module");
                }
            },MetalBackend.captureCompileExecutor(source,worker));
            if(!entered.await(10,TimeUnit.SECONDS)) {
                if(compiled.isDone())compiled.join();
                throw new IllegalStateException("Actual native include callback did not start");
            }
            var reloading=context.computeOnClient(client->client.reloadResourcePacks());
            try {
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
                // The render thread will wait in close; poll the owner's volatile state from this test thread.
                while(!owner.retirementRequested() && System.nanoTime()<deadline) {
                    if(reloading.isDone())reloading.join();
                    if(compiled.isDone())compiled.join();
                    Thread.sleep(10);
                }
                require(owner.retirementRequested(),"Actual Configs close was not requested during the borrow");
                require(!owner.proof().closed() && !compiled.isDone(),"Actual Configs freed includes while shaderc still borrowed them");
            } finally {continueBorrow.countDown();}
            compiled.get(5,TimeUnit.SECONDS);
            require(problem.get()==null,"Actual native include callback failed: "+problem.get());
            require(callbacks.get()>0,"No native include callback was exercised");
            context.waitFor(client->reloading.isDone(),600);reloading.join();
            var newer=context.computeOnClient(client->MetalShaderSourceAdapters.libraryLifetime(MetalDevice.current().activeShaderSource()));
            require(newer!=null && newer.proof().canonicalOpen() && newer!=owner
                    && newer.proof().generation()!=owner.proof().generation(),"Reload did not publish a different canonical source owner");
            require(owner.proof().closed(),"Retired Configs owner did not close after the compiler task returned");
            return Map.of("ownerType",owner.ownerType(),"oldGeneration",owner.proof().generation(),
                    "newGeneration",newer.proof().generation(),"retirementRequestedDuringBorrow",true,
                    "oldOwnerClosedAfterCompile",true,"nativeCallbacks",callbacks.get(),"include",expected,
                    "compiledSuccessfully",true,"sourceAdmissionGranted",false);
        } catch(InterruptedException interrupted) {
            Thread.currentThread().interrupt();throw new IllegalStateException("Actual reload lease control interrupted",interrupted);
        } catch(java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException failure) {
            throw new IllegalStateException("Actual reload lease compiler failed",failure);
        } finally {continueBorrow.countDown();worker.shutdownNow();}
    }

    static boolean canonicalSourceDrawObserved(long generation) {
        return MetalMotionDrawCoverage.census().programs().values().stream().anyMatch(row->
                row.actualDraws()>0 && row.source()!=null && row.source().vertexShader().startsWith("minecraft:core/")
                        && row.shaderInputs().canonicalProofs().values().stream().anyMatch(proof->
                        proof.libraryGeneration()==generation && proof.canonicalMatch().canonicalInputsMatch()));
    }
    private static void require(boolean value,String message) {if(!value)throw new IllegalStateException(message);}
    private MetalShaderReloadLeaseEvidence() {}
}

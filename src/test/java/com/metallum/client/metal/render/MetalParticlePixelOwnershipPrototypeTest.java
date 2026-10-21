package com.metallum.client.metal.render;

import com.mojang.renderpearl.api.pipeline.ShaderType;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;
import java.nio.ByteOrder;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

final class MetalParticlePixelOwnershipPrototypeTest {
    private static final class Resource implements AutoCloseable {
        boolean closed;@Override public void close() {assertFalse(closed);closed=true;}
    }
    @Test void sourceInstrumentationPreservesTheWholeOriginalFunctionAndDoesNotMoveDiscard() {
        try(var raw=MetalShaderSourceLibraryTest.rawLibrary(false,false)) {
            var source=Identifier.withDefaultNamespace("core/particle");
            for(var stage:List.of(ShaderType.VERTEX,ShaderType.FRAGMENT)) {
                String original=raw.getShader(source,stage);assertNotNull(original);
                String modified=MetalParticlePixelOwnershipPrototype.instrument(original,stage);
                int tail=modified.indexOf(stage==ShaderType.VERTEX?"// Diagnostic identity":"layout(location=15) flat in uvec4 proofElement;");
                assertFalse(java.util.regex.Pattern.compile("location\\s*=\\s*15").matcher(original).find(),"The canonical source must not already own the diagnostic varying location");
                String restored=modified.substring(0,tail).stripTrailing().replace("void metallumOriginalMain()","void main()");
                assertEquals(original.stripTrailing(),restored);
                if(stage==ShaderType.FRAGMENT)assertTrue(modified.lastIndexOf("metallumOriginalMain();")>modified.indexOf("discard"));
            }
        }
        assertThrows(IllegalArgumentException.class,()->MetalParticlePixelOwnershipPrototype.instrument("void main(){} void main(){}",ShaderType.FRAGMENT));
    }
    @Test void inputIdentityKeepsAll64BitsAndUnknownOrAliasedEmittersBecomeUnknown() {
        var registry=new MetalParticlePixelOwnershipPrototype.Registry();var b=new Object();
        long id=(1L<<40)+(1L<<24)+11,generation=(1L<<35)+(1L<<24)+17;
        var h=registry.capture(b,id,generation);var data=registry.freeze(List.of(h)).order(ByteOrder.nativeOrder());
        assertEquals(id,Integer.toUnsignedLong(data.getInt(0))|(Integer.toUnsignedLong(data.getInt(4))<<32));
        assertEquals(generation,Integer.toUnsignedLong(data.getInt(8))|(Integer.toUnsignedLong(data.getInt(12))<<32));
        assertNull(registry.capture(new Object(),id,generation));assertFalse(registry.live(h));
        data=registry.freeze(List.of(h));for(int i=0;i<data.remaining();i++)assertEquals(0,data.get(i));
        registry.reset();assertFalse(registry.live(h));
    }
    @Test void noSwapUntilGpuCompletionAndSourceParityAreBothAccepted() {
        try(var history=new MetalParticlePixelOwnershipPrototype.History<Resource>()) {
            var first=history.begin(64,48,"source",Resource::new);assertNull(first.previous);
            assertThrows(IllegalStateException.class,()->history.accept(first,true));
            history.submitted(first);assertThrows(IllegalStateException.class,()->history.accept(first,true));
            history.completed(first,true);assertTrue(history.accept(first,true));
            var second=history.begin(64,48,"source",Resource::new);assertSame(first.current,second.previous);
            history.submitted(second);history.completed(second,true);assertFalse(history.accept(second,false));
            var third=history.begin(64,48,"source",Resource::new);assertSame(first.current,third.previous);history.discard(third);
        }
    }
    @Test void discardAndFailedCompletionCannotAdvanceOrReuseInFlightSlots() {
        try(var history=new MetalParticlePixelOwnershipPrototype.History<Resource>()) {
            var first=history.begin(64,48,"source",Resource::new);history.submitted(first);history.completed(first,true);history.accept(first,true);
            var pending=history.begin(64,48,"source",Resource::new);history.submitted(pending);history.discard(pending);
            assertThrows(IllegalStateException.class,()->history.begin(64,48,"source",Resource::new));
            assertThrows(IllegalStateException.class,history::invalidate);
            history.completed(pending,true);assertFalse(history.accept(pending,true));
            var failed=history.begin(64,48,"source",Resource::new);assertSame(first.current,failed.previous);
            history.submitted(failed);history.completed(failed,false);
            var after=history.begin(64,48,"source",Resource::new);assertSame(first.current,after.previous);history.discard(after);
        }
    }
    @Test void resizeReloadAndLateCallbacksCannotPromoteRetiredResources() {
        try(var history=new MetalParticlePixelOwnershipPrototype.History<Resource>()) {
            var old=history.begin(64,48,"generation1",Resource::new);history.submitted(old);history.completed(old,true);history.accept(old,true);
            var resized=history.begin(80,48,"generation1",Resource::new);assertTrue(old.current.closed);assertNull(resized.previous);
            history.completed(old,true);history.discard(resized);
            var reloaded=history.begin(80,48,"generation2",Resource::new);assertNull(reloaded.previous);assertTrue(resized.current.closed);history.discard(reloaded);
        }
    }
    @Test void partialAllocationFailureClosesTheResourcesAlreadyCreated() {
        var count=new AtomicInteger();var made=new java.util.ArrayList<Resource>();
        try(var history=new MetalParticlePixelOwnershipPrototype.History<Resource>()) {
            assertThrows(IllegalStateException.class,()->history.begin(64,48,"source",()->{
                if(count.incrementAndGet()==2)throw new IllegalStateException("injected allocation failure");
                var resource=new Resource();made.add(resource);return resource;
            }));
            assertEquals(1,made.size());assertTrue(made.getFirst().closed);assertEquals(-1,history.acceptedSlot());
            var retry=history.begin(64,48,"source",Resource::new);assertNull(retry.previous);history.discard(retry);
        }
    }
    @Test void memoryBudgetIsCheckedWithoutSilentOverflowOrUnboundedAllocation() {
        assertEquals(331_776_000L,MetalParticlePixelOwnershipPrototype.bytes(3840,2160));
        assertEquals(16_396_800L,MetalParticlePixelOwnershipPrototype.bytes(854,480));
        for(int[] size:List.of(new int[]{0,48},new int[]{64,-1},new int[]{Integer.MAX_VALUE,2160}))
            assertThrows(IllegalArgumentException.class,()->MetalParticlePixelOwnershipPrototype.bytes(size[0],size[1]));
    }
}

package com.metallum.mixin.render;

import com.metallum.client.metal.render.MetalParticleBatchMotion;
import com.metallum.client.metal.render.MetalParticleIdentityAccess;
import net.minecraft.client.particle.SingleQuadParticle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(SingleQuadParticle.class)
public abstract class SingleQuadParticleMetalFxIdentityMixin implements MetalParticleIdentityAccess {
    @Unique
    private final long metallum$motionIdentity = MetalParticleBatchMotion.allocateParticleIdentity();

    @Override
    public long metallum$motionIdentity() {
        return metallum$motionIdentity;
    }
}

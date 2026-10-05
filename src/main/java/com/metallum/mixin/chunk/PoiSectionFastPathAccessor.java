package com.metallum.mixin.chunk;

import net.minecraft.core.Holder;
import net.minecraft.world.entity.ai.village.poi.PoiRecord;
import net.minecraft.world.entity.ai.village.poi.PoiSection;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;
import java.util.Set;

@Mixin(PoiSection.class)
public interface PoiSectionFastPathAccessor {
    @Accessor("byType")
    Map<Holder<PoiType>, Set<PoiRecord>> metallum$recordsByType();
}

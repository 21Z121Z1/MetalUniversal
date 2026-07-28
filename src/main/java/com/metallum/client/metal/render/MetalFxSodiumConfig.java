package com.metallum.client.metal.render;

import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.EnumOptionBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.ModOptionsBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.OptionGroupBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.OptionPageBuilder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/** Sodium 0.9 configuration page for the startup-owned MetalFX renderer. */
public final class MetalFxSodiumConfig implements ConfigEntryPoint {
    private static final Identifier MODE_ID = Identifier.fromNamespaceAndPath("metallum", "metalfx_mode");
    private static final Identifier SCALE_ID = Identifier.fromNamespaceAndPath("metallum", "metalfx_scale");
    private static final Identifier REACTIVE_MASK_ID = Identifier.fromNamespaceAndPath("metallum", "metalfx_transparency_reactive");
    private static final Identifier FRAME_GENERATION_ID = Identifier.fromNamespaceAndPath("metallum", "metalfx_frame_generation");
    private static final Identifier METAL_HUD_ID = Identifier.fromNamespaceAndPath("metallum", "metal_hud");

    @Override
    public void registerConfigLate(final ConfigBuilder builder) {
        ModOptionsBuilder modOptions = builder.registerOwnModOptions()
                .setName("MetalUniversal")
                .setVersion("1.0.1");
        OptionPageBuilder page = builder.createOptionPage()
                .setName(Component.translatable("metallum.options.metalfx.page"));

        OptionGroupBuilder quality = builder.createOptionGroup()
                .setName(Component.translatable("metallum.options.metalfx.group"));
        quality.addOption(modeOption(builder));
        quality.addOption(scaleOption(builder));
        quality.addOption(transparencyReactiveOption(builder));
        quality.addOption(frameGenerationOption(builder));
        quality.addOption(metalHudOption(builder));
        page.addOptionGroup(quality);
        modOptions.addPage(page);
    }

    private static EnumOptionBuilder<MetalFxConfig.Mode> modeOption(final ConfigBuilder builder) {
        return builder.createEnumOption(MODE_ID, MetalFxConfig.Mode.class)
                .setName(Component.translatable("metallum.options.metalfx.mode"))
                .setTooltip(Component.translatable("metallum.options.metalfx.mode.tooltip"))
                .setElementNameProvider(MetalFxSodiumConfig::modeLabel)
                .setDefaultValue(MetalFxConfig.Mode.OFF)
                .setStorageHandler(MetalFxConfig::flushPersistent)
                .setImpact(net.caffeinemc.mods.sodium.api.config.option.OptionImpact.VARIES)
                .setEnabled(!MetalFxConfig.hasSystemPropertyOverride(MetalFxConfig.MODE_PROPERTY))
                .setBinding(MetalFxConfig::setModeFromSodium, MetalFxConfig::configuredModeForSodium);
    }

    private static EnumOptionBuilder<MetalFxConfig.Scale> scaleOption(final ConfigBuilder builder) {
        return builder.createEnumOption(SCALE_ID, MetalFxConfig.Scale.class)
                .setName(Component.translatable("metallum.options.metalfx.scale"))
                .setTooltip(Component.translatable("metallum.options.metalfx.scale.tooltip"))
                .setElementNameProvider(value -> Component.literal(value.label))
                .setDefaultValue(MetalFxConfig.Scale.QUALITY)
                .setStorageHandler(MetalFxConfig::flushPersistent)
                .setImpact(net.caffeinemc.mods.sodium.api.config.option.OptionImpact.VARIES)
                .setEnabled(!MetalFxConfig.hasSystemPropertyOverride(MetalFxConfig.SCALE_PROPERTY))
                .setBinding(MetalFxConfig::setScaleFromSodium, MetalFxConfig::configuredScaleForSodium);
    }

    private static net.caffeinemc.mods.sodium.api.config.structure.BooleanOptionBuilder transparencyReactiveOption(
            final ConfigBuilder builder
    ) {
        return builder.createBooleanOption(REACTIVE_MASK_ID)
                .setName(Component.translatable("metallum.options.metalfx.reactive_mask"))
                .setTooltip(Component.translatable("metallum.options.metalfx.reactive_mask.tooltip"))
                .setDefaultValue(true)
                .setStorageHandler(MetalFxConfig::flushPersistent)
                .setImpact(net.caffeinemc.mods.sodium.api.config.option.OptionImpact.MEDIUM)
                .setEnabledProvider(
                        state -> {
                            MetalFxConfig.Mode mode = state.readEnumOption(MODE_ID, MetalFxConfig.Mode.class);
                            return mode == MetalFxConfig.Mode.TEMPORAL || mode == MetalFxConfig.Mode.AUTO;
                        },
                        MODE_ID
                )
                .setEnabled(!MetalFxConfig.hasSystemPropertyOverride(MetalFxConfig.REACTIVE_MASK_PROPERTY))
                .setBinding(
                        MetalFxConfig::setTransparencyReactiveMaskFromSodium,
                        MetalFxConfig::configuredTransparencyReactiveMaskForSodium
                );
    }

    private static net.caffeinemc.mods.sodium.api.config.structure.BooleanOptionBuilder frameGenerationOption(
            final ConfigBuilder builder
    ) {
        return builder.createBooleanOption(FRAME_GENERATION_ID)
                .setName(Component.translatable("metallum.options.metalfx.frame_generation"))
                .setTooltip(Component.translatable("metallum.options.metalfx.frame_generation.tooltip"))
                .setDefaultValue(false)
                .setStorageHandler(MetalFxConfig::flushPersistent)
                .setImpact(net.caffeinemc.mods.sodium.api.config.option.OptionImpact.HIGH)
                .setEnabledProvider(
                        state -> {
                            MetalFxConfig.Mode mode = state.readEnumOption(MODE_ID, MetalFxConfig.Mode.class);
                            return mode == MetalFxConfig.Mode.TEMPORAL || mode == MetalFxConfig.Mode.AUTO;
                        },
                        MODE_ID
                )
                .setEnabled(!MetalFxConfig.hasSystemPropertyOverride(MetalFxConfig.FRAME_GENERATION_PROPERTY))
                .setBinding(
                        MetalFxConfig::setFrameGenerationFromSodium,
                        MetalFxConfig::configuredFrameGenerationForSodium
                );
    }

    private static net.caffeinemc.mods.sodium.api.config.structure.BooleanOptionBuilder metalHudOption(
            final ConfigBuilder builder
    ) {
        return builder.createBooleanOption(METAL_HUD_ID)
                .setName(Component.translatable("metallum.options.metal_hud"))
                .setTooltip(Component.translatable("metallum.options.metal_hud.tooltip"))
                .setDefaultValue(false)
                .setStorageHandler(MetalFxConfig::flushPersistent)
                .setImpact(net.caffeinemc.mods.sodium.api.config.option.OptionImpact.LOW)
                .setEnabled(!MetalFxConfig.hasSystemPropertyOverride(MetalFxConfig.METAL_HUD_PROPERTY))
                .setBinding(MetalFxConfig::setMetalHudFromSodium, MetalFxConfig::configuredMetalHudForSodium);
    }

    private static Component modeLabel(final MetalFxConfig.Mode mode) {
        return Component.translatable(switch (mode) {
            case OFF -> "metallum.options.metalfx.mode.off";
            case SPATIAL -> "metallum.options.metalfx.mode.spatial";
            case TEMPORAL -> "metallum.options.metalfx.mode.temporal";
            case AUTO -> "metallum.options.metalfx.mode.auto";
        });
    }
}

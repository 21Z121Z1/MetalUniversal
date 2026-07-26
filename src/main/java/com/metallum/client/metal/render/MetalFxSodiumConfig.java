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

    @Override
    public void registerConfigLate(final ConfigBuilder builder) {
        ModOptionsBuilder modOptions = builder.registerOwnModOptions()
                .setName("MetalUniversal")
                .setVersion("1.0.1");
        OptionPageBuilder page = builder.createOptionPage()
                .setName(Component.literal("MetalFX"));

        OptionGroupBuilder quality = builder.createOptionGroup()
                .setName(Component.literal("MetalFX Rendering"));
        quality.addOption(modeOption(builder));
        quality.addOption(scaleOption(builder));
        quality.addOption(transparencyReactiveOption(builder));
        quality.addOption(frameGenerationOption(builder));
        page.addOptionGroup(quality);
        modOptions.addPage(page);
    }

    private static EnumOptionBuilder<MetalFxConfig.Mode> modeOption(final ConfigBuilder builder) {
        return builder.createEnumOption(MODE_ID, MetalFxConfig.Mode.class)
                .setName(Component.literal("MetalFX mode"))
                .setTooltip(Component.literal("Select native rendering, spatial upscaling, temporal upscaling, or automatic capability selection."))
                .setElementNameProvider(MetalFxSodiumConfig::modeLabel)
                .setDefaultValue(MetalFxConfig.Mode.OFF)
                .setStorageHandler(MetalFxConfig::flushPersistent)
                .setImpact(net.caffeinemc.mods.sodium.api.config.option.OptionImpact.VARIES)
                .setFlags(net.caffeinemc.mods.sodium.api.config.option.OptionFlag.REQUIRES_GAME_RESTART)
                .setEnabled(!MetalFxConfig.hasSystemPropertyOverride(MetalFxConfig.MODE_PROPERTY))
                .setBinding(MetalFxConfig::setModeFromSodium, MetalFxConfig::configuredModeForSodium);
    }

    private static EnumOptionBuilder<MetalFxConfig.Scale> scaleOption(final ConfigBuilder builder) {
        return builder.createEnumOption(SCALE_ID, MetalFxConfig.Scale.class)
                .setName(Component.literal("Internal render resolution"))
                .setTooltip(Component.literal("Render the 3D scene at this fraction of the display resolution before MetalFX upscaling."))
                .setElementNameProvider(value -> Component.literal(value.label))
                .setDefaultValue(MetalFxConfig.Scale.QUALITY)
                .setStorageHandler(MetalFxConfig::flushPersistent)
                .setImpact(net.caffeinemc.mods.sodium.api.config.option.OptionImpact.VARIES)
                .setFlags(net.caffeinemc.mods.sodium.api.config.option.OptionFlag.REQUIRES_GAME_RESTART)
                .setEnabled(!MetalFxConfig.hasSystemPropertyOverride(MetalFxConfig.SCALE_PROPERTY))
                .setBinding(MetalFxConfig::setScaleFromSodium, MetalFxConfig::configuredScaleForSodium);
    }

    private static net.caffeinemc.mods.sodium.api.config.structure.BooleanOptionBuilder transparencyReactiveOption(
            final ConfigBuilder builder
    ) {
        return builder.createBooleanOption(REACTIVE_MASK_ID)
                .setName(Component.literal("Transparent reactive mask"))
                .setTooltip(Component.literal("Reject history for glass, water, particles, weather, clouds, and other transparent targets."))
                .setDefaultValue(true)
                .setStorageHandler(MetalFxConfig::flushPersistent)
                .setImpact(net.caffeinemc.mods.sodium.api.config.option.OptionImpact.MEDIUM)
                .setFlags(net.caffeinemc.mods.sodium.api.config.option.OptionFlag.REQUIRES_GAME_RESTART)
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
                .setName(Component.literal("Metal frame generation"))
                .setTooltip(Component.literal("Generate an interpolated frame between rendered frames on supported macOS systems."))
                .setDefaultValue(false)
                .setStorageHandler(MetalFxConfig::flushPersistent)
                .setImpact(net.caffeinemc.mods.sodium.api.config.option.OptionImpact.HIGH)
                .setFlags(net.caffeinemc.mods.sodium.api.config.option.OptionFlag.REQUIRES_GAME_RESTART)
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

    private static Component modeLabel(final MetalFxConfig.Mode mode) {
        return Component.literal(switch (mode) {
            case OFF -> "Off";
            case SPATIAL -> "Spatial";
            case TEMPORAL -> "Temporal";
            case AUTO -> "Auto";
        });
    }
}

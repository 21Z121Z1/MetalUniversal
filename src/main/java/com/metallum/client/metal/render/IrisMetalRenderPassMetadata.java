package com.metallum.client.metal.render;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The Iris-only part of a render-pass descriptor.
 *
 * <p>Blaze3D's public {@code RenderPassDescriptor} intentionally exposes only
 * clear values. Iris also needs the load/store contract to survive lowering
 * to Metal, where those actions are properties of the native pass descriptor.
 * Keeping this metadata separate preserves the vanilla backend ABI while
 * making the Iris path fail closed when a native bridge cannot express it.</p>
 */
@Environment(EnvType.CLIENT)
final class IrisMetalRenderPassMetadata {
    enum LoadAction {
        LOAD(0),
        CLEAR(1),
        DONT_CARE(2);

        private final int nativeValue;

        LoadAction(final int nativeValue) {
            this.nativeValue = nativeValue;
        }

        int nativeValue(final boolean clearRequested) {
            // A frame clear is registered through the ordinary Blaze3D
            // pending-clear path. LOAD therefore means "load that clear when
            // one is pending, otherwise load the existing attachment".
            return this == LOAD && clearRequested ? CLEAR.nativeValue : nativeValue;
        }
    }

    enum StoreAction {
        STORE(0),
        DISCARD(1);

        private final int nativeValue;

        StoreAction(final int nativeValue) {
            this.nativeValue = nativeValue;
        }

        int nativeValue() {
            return nativeValue;
        }
    }

    record ColorAttachment(
            int logicalTarget,
            int physicalSlot,
            GpuFormat format,
            int writeMask,
            LoadAction load,
            StoreAction store
    ) {
        ColorAttachment {
            if (logicalTarget < 0) {
                throw new IllegalArgumentException("Iris logical attachment target must be non-negative");
            }
            if (physicalSlot < 0) {
                throw new IllegalArgumentException("Iris physical attachment slot must be non-negative");
            }
            Objects.requireNonNull(format, "format");
            Objects.requireNonNull(load, "load");
            Objects.requireNonNull(store, "store");
            if ((writeMask & ~com.mojang.blaze3d.pipeline.ColorTargetState.WRITE_ALL) != 0) {
                throw new IllegalArgumentException("Invalid Iris attachment write mask " + writeMask);
            }
        }
    }

    record DepthAttachment(
            GpuFormat format,
            LoadAction load,
            StoreAction store
    ) {
        DepthAttachment {
            Objects.requireNonNull(format, "format");
            Objects.requireNonNull(load, "load");
            Objects.requireNonNull(store, "store");
        }
    }

    private final List<ColorAttachment> colorAttachments;
    private final Optional<DepthAttachment> depthAttachment;

    private IrisMetalRenderPassMetadata(
            final List<ColorAttachment> colorAttachments,
            final Optional<DepthAttachment> depthAttachment
    ) {
        this.colorAttachments = List.copyOf(colorAttachments);
        this.depthAttachment = Objects.requireNonNull(depthAttachment, "depthAttachment");
        for (int index = 0; index < this.colorAttachments.size(); index++) {
            if (this.colorAttachments.get(index).physicalSlot() != index) {
                throw new IllegalArgumentException(
                        "Iris attachment metadata is not in physical slot order at index " + index
                );
            }
        }
    }

    static IrisMetalRenderPassMetadata from(
            final List<IrisMetalExecutionGraph.AttachmentState> attachments
    ) {
        Objects.requireNonNull(attachments, "attachments");
        List<ColorAttachment> result = new ArrayList<>(attachments.size());
        for (IrisMetalExecutionGraph.AttachmentState attachment : attachments) {
            Objects.requireNonNull(attachment, "attachment");
            result.add(new ColorAttachment(
                    attachment.logicalTarget(),
                    attachment.physicalSlot(),
                    attachment.format(),
                    attachment.writeMask(),
                    switch (attachment.load()) {
                        case LOAD -> LoadAction.LOAD;
                        case CLEAR -> LoadAction.CLEAR;
                        case DONT_CARE -> LoadAction.DONT_CARE;
                    },
                    switch (attachment.store()) {
                        case STORE -> StoreAction.STORE;
                        case DISCARD -> StoreAction.DISCARD;
                    }
            ));
        }
        return new IrisMetalRenderPassMetadata(result, Optional.empty());
    }

    static IrisMetalRenderPassMetadata forOverride(
            final List<IrisMetalExecutionGraph.AttachmentState> attachments,
            final GpuFormat overrideFormat
    ) {
        Objects.requireNonNull(attachments, "attachments");
        Objects.requireNonNull(overrideFormat, "overrideFormat");
        if (attachments.size() != 1) {
            throw new IllegalArgumentException(
                    "Iris final override must have exactly one color attachment, got " + attachments.size()
            );
        }
        IrisMetalExecutionGraph.AttachmentState attachment = attachments.get(0);
        if (attachment.logicalTarget() != 0 || attachment.physicalSlot() != 0) {
            throw new IllegalArgumentException(
                    "Iris final override must target logical/physical slot 0"
            );
        }
        return new IrisMetalRenderPassMetadata(List.of(new ColorAttachment(
                0,
                0,
                overrideFormat,
                attachment.writeMask(),
                switch (attachment.load()) {
                    case LOAD -> LoadAction.LOAD;
                    case CLEAR -> LoadAction.CLEAR;
                    case DONT_CARE -> LoadAction.DONT_CARE;
                },
                switch (attachment.store()) {
                    case STORE -> StoreAction.STORE;
                    case DISCARD -> StoreAction.DISCARD;
                }
        )), Optional.empty());
    }

    static IrisMetalRenderPassMetadata fromDescriptor(
            final RenderPassDescriptor descriptor,
            final int[] logicalTargets
    ) {
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(logicalTargets, "logicalTargets");
        List<RenderPassDescriptor.Attachment<java.util.Optional<org.joml.Vector4fc>>> descriptorAttachments =
                descriptor.colorAttachments();
        if (descriptorAttachments.size() != logicalTargets.length) {
            throw new IllegalArgumentException(
                    "Iris descriptor color slots " + descriptorAttachments.size()
                            + " do not match DRAWBUFFERS slots " + logicalTargets.length
            );
        }
        List<ColorAttachment> colors = new ArrayList<>(logicalTargets.length);
        for (int slot = 0; slot < logicalTargets.length; slot++) {
            RenderPassDescriptor.Attachment<java.util.Optional<org.joml.Vector4fc>> attachment =
                    descriptorAttachments.get(slot);
            GpuFormat format = attachment == null
                    ? GpuFormat.RGBA8_UNORM
                    : attachment.textureView().texture().getFormat();
            LoadAction load = attachment != null && attachment.clearValue().isPresent()
                    ? LoadAction.CLEAR
                    : LoadAction.LOAD;
            colors.add(new ColorAttachment(
                    logicalTargets[slot],
                    slot,
                    format,
                    com.mojang.blaze3d.pipeline.ColorTargetState.WRITE_ALL,
                    load,
                    StoreAction.STORE
            ));
        }

        Optional<DepthAttachment> depth = Optional.empty();
        if (descriptor.depthAttachment() != null) {
            RenderPassDescriptor.Attachment<java.util.OptionalDouble> attachment =
                    descriptor.depthAttachment();
            depth = Optional.of(new DepthAttachment(
                    attachment.textureView().texture().getFormat(),
                    attachment.clearValue().isPresent() ? LoadAction.CLEAR : LoadAction.LOAD,
                    StoreAction.STORE
            ));
        }
        return new IrisMetalRenderPassMetadata(colors, depth);
    }

    static IrisMetalRenderPassMetadata withDepth(
            final List<IrisMetalExecutionGraph.AttachmentState> attachments,
            final GpuFormat depthFormat,
            final LoadAction depthLoad,
            final StoreAction depthStore
    ) {
        IrisMetalRenderPassMetadata colors = from(attachments);
        return new IrisMetalRenderPassMetadata(
                colors.colorAttachments,
                Optional.of(new DepthAttachment(depthFormat, depthLoad, depthStore))
        );
    }

    int colorCount() {
        return colorAttachments.size();
    }

    int[] nativeLoadActions(final int[] clearColorEnabled) {
        Objects.requireNonNull(clearColorEnabled, "clearColorEnabled");
        if (clearColorEnabled.length != colorAttachments.size()) {
            throw new IllegalArgumentException("Iris load-action array does not match color attachment count");
        }
        int[] result = new int[colorAttachments.size()];
        for (int index = 0; index < result.length; index++) {
            result[index] = colorAttachments.get(index).load()
                    .nativeValue(clearColorEnabled[index] != 0);
        }
        return result;
    }

    int[] nativeStoreActions() {
        int[] result = new int[colorAttachments.size()];
        for (int index = 0; index < result.length; index++) {
            result[index] = colorAttachments.get(index).store().nativeValue();
        }
        return result;
    }

    void validateClearInputs(
            final int[] clearColorEnabled,
            final boolean clearDepthEnabled,
            final boolean[] attachedColors
    ) {
        Objects.requireNonNull(clearColorEnabled, "clearColorEnabled");
        Objects.requireNonNull(attachedColors, "attachedColors");
        if (clearColorEnabled.length != colorAttachments.size()) {
            throw new IllegalArgumentException("Iris clear-color array does not match color attachment count");
        }
        if (attachedColors.length != colorAttachments.size()) {
            throw new IllegalArgumentException("Iris attached-color array does not match color attachment count");
        }
        for (int index = 0; index < colorAttachments.size(); index++) {
            if (attachedColors[index]
                    && colorAttachments.get(index).load() == LoadAction.CLEAR
                    && clearColorEnabled[index] == 0) {
                throw new IllegalStateException(
                        "Iris attachment " + index + " declares CLEAR without a clear color"
                );
            }
        }
        if (depthAttachment.isPresent()
                && depthAttachment.get().load() == LoadAction.CLEAR
                && !clearDepthEnabled) {
            throw new IllegalStateException("Iris depth attachment declares CLEAR without a clear depth");
        }
    }

    boolean hasDepthMetadata() {
        return depthAttachment.isPresent();
    }

    int nativeDepthLoadAction(final boolean clearDepthRequested) {
        return depthAttachment.orElseThrow().load().nativeValue(clearDepthRequested);
    }

    int nativeDepthStoreAction() {
        return depthAttachment.orElseThrow().store().nativeValue();
    }

    Optional<DepthAttachment> depthAttachment() {
        return depthAttachment;
    }

    List<ColorAttachment> colorAttachments() {
        return colorAttachments;
    }
}

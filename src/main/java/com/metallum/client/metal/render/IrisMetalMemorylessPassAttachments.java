package com.metallum.client.metal.render;

import com.metallum.client.validation.contract.PassType;
import org.jspecify.annotations.Nullable;

import java.util.Arrays;
import java.util.BitSet;
import java.util.Objects;

/**
 * Generation-scoped execution binding for pass-local memoryless color targets.
 *
 * <p>This is deliberately narrower than the lifetime compiler. The compiler may
 * prove that a physical allocation is dead after a pass; this class additionally
 * requires a fully resolved, current-generation receipt, exact live allocation
 * identity, V3 attachment-action support, and a DONT_CARE/DONT_CARE attachment.
 * Any mismatch returns {@code null} and preserves the normal private/placement
 * texture path.</p>
 */
final class IrisMetalMemorylessPassAttachments implements AutoCloseable {
    private static final boolean ENABLED = Boolean.getBoolean(
            "metallum.iris.experimental.memorylessAttachments"
    );

    private final MetalGpuTexture[] textures;
    private final MetalGpuTextureView[] views;
    private final int[] loadActions;
    private final int[] storeActions;
    private boolean closed;

    private IrisMetalMemorylessPassAttachments(
            final MetalGpuTexture[] textures,
            final MetalGpuTextureView[] views,
            final int[] loadActions,
            final int[] storeActions
    ) {
        this.textures = textures;
        this.views = views;
        this.loadActions = loadActions;
        this.storeActions = storeActions;
    }

    static @Nullable IrisMetalMemorylessPassAttachments tryCreate(
            final MetalDevice device,
            final int chainGeneration,
            final IrisMetalRenderTargets targets,
            final IrisMetalPostChain.PassInfo info,
            final int renderOrdinal
    ) {
        Objects.requireNonNull(device, "device");
        Objects.requireNonNull(targets, "targets");
        Objects.requireNonNull(info, "info");
        if (!ENABLED || renderOrdinal < 0 || !MetalCommandEncoder.explicitColorActionsAvailable()) {
            return null;
        }

        IrisMetalOptimizationPlan plan = IrisMetalExperimentalOptimizer.active();
        if (plan == null || plan.chainGeneration() != chainGeneration) {
            return null;
        }
        IrisMetalOptimizationPlan.AttachmentLifetimeReceipt receipt = plan.attachmentLifetimeReceipt();
        if (receipt == null
                || receipt.chainGeneration() != chainGeneration
                || receipt.targetEpoch() != targets.allocationStamp()
                || !"RESOLVED_CONSERVATIVE".equals(receipt.status())
                || !receipt.unresolvedConsumers().isEmpty()
                || !receipt.targetSignature().equals(IrisMetalAttachmentLifetimeCompiler.targetSignature(targets))) {
            return null;
        }

        int[] drawBuffers = info.drawBuffers();
        if (drawBuffers.length == 0) {
            return null;
        }
        String planPassKey = IrisMetalOptimizationPlan.stablePlanPassKey(
                info.stage().name(), PassType.RENDER, renderOrdinal, info.name()
        );
        IrisMetalPingPongTargets colors = targets.colorTargets();
        BitSet readsFromAlt = info.readsFromAlt();
        MetalGpuTexture[] textures = new MetalGpuTexture[drawBuffers.length];
        MetalGpuTextureView[] views = new MetalGpuTextureView[drawBuffers.length];
        int[] loads = new int[drawBuffers.length];
        int[] stores = new int[drawBuffers.length];
        Arrays.fill(loads, -1);
        Arrays.fill(stores, -1);
        boolean any = false;

        try {
            for (IrisMetalOptimizationPlan.ResolvedAttachment attachment : receipt.attachments()) {
                if (!planPassKey.equals(attachment.planPassKey())
                        || !IrisMetalMemorylessAttachmentAllocator.eligible(attachment)
                        // First execution version intentionally excludes CLEAR.
                        // DONT_CARE avoids needing a separate clear-value authority.
                        || attachment.load() != IrisMetalOptimizationPlan.LoadAction.DONT_CARE) {
                    continue;
                }
                int slot = attachment.slot();
                if (slot < 0 || slot >= drawBuffers.length || views[slot] != null) {
                    closePartial(views, textures);
                    return null;
                }
                int logicalTarget = drawBuffers[slot];
                if (logicalTarget < 0 || logicalTarget >= colors.targetCount()
                        || !attachment.logicalResource().equals("colortex" + logicalTarget)
                        || attachment.mipLevel() != 0) {
                    closePartial(views, textures);
                    return null;
                }

                MetalGpuTexture persistentWrite = colors.writeTexture(logicalTarget);
                String expectedSide = readsFromAlt.get(logicalTarget) ? "main" : "alt";
                if (!expectedSide.equals(attachment.physicalSide())
                        || persistentWrite.allocationId() != attachment.allocationId()
                        || persistentWrite.allocationIdentity().generation() != attachment.allocationGeneration()) {
                    closePartial(views, textures);
                    return null;
                }

                MetalGpuTexture transientTexture = IrisMetalMemorylessAttachmentAllocator.tryCreate(
                        device,
                        attachment,
                        "iris-memoryless/" + info.stage().name().toLowerCase(java.util.Locale.ROOT)
                                + "/" + info.name() + "/slot" + slot,
                        persistentWrite.getFormat(),
                        persistentWrite.getWidth(0),
                        persistentWrite.getHeight(0)
                );
                if (transientTexture == null) {
                    closePartial(views, textures);
                    return null;
                }
                transientTexture.registerAllocationIdentity();
                MetalGpuTextureView view = new MetalGpuTextureView(transientTexture, 0, 1);
                textures[slot] = transientTexture;
                views[slot] = view;
                loads[slot] = 0;  // MTLLoadAction.dontCare
                stores[slot] = 0; // MTLStoreAction.dontCare
                any = true;
            }
        } catch (RuntimeException failure) {
            closePartial(views, textures);
            return null;
        }

        if (!any) {
            closePartial(views, textures);
            return null;
        }
        return new IrisMetalMemorylessPassAttachments(textures, views, loads, stores);
    }

    MetalGpuTextureView[] views() {
        return views;
    }

    int[] loadActions() {
        return loadActions;
    }

    int[] storeActions() {
        return storeActions;
    }

    private static void closePartial(
            final MetalGpuTextureView[] views,
            final MetalGpuTexture[] textures
    ) {
        for (MetalGpuTextureView view : views) {
            if (view != null && !view.isClosed()) {
                view.close();
            }
        }
        for (MetalGpuTexture texture : textures) {
            if (texture != null && !texture.isClosed()) {
                texture.close();
            }
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        closePartial(views, textures);
    }
}

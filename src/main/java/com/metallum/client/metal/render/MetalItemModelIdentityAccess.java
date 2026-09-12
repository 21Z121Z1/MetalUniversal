package com.metallum.client.metal.render;

import java.util.List;

/** Runtime sidecar populated from ItemStackRenderState's model-identity callbacks. */
public interface MetalItemModelIdentityAccess {
    List<Object> metallum$modelIdentity();

    boolean metallum$hasSpecialLayer();
}

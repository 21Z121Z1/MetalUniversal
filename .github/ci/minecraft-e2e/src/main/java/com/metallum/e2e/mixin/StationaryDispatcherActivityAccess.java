package com.metallum.e2e.mixin;

/** Test-only view of active SectionRenderDispatcher.runTask invocations. */
public interface StationaryDispatcherActivityAccess {
    int metallum$activeTaskInvocations();
}

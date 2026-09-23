package com.metallum.client.validation.contract;

public record AttachmentBindingRecord(
        int slot,
        ResourceIdentity resource,
        AttachmentSemantic semantic,
        String loadAction,
        String storeAction,
        boolean writable
) {
    public AttachmentBindingRecord {
        if (slot < 0 || resource == null || semantic == null) {
            throw new IllegalArgumentException("Invalid attachment binding");
        }
        loadAction = loadAction == null ? "unknown" : loadAction;
        storeAction = storeAction == null ? "unknown" : storeAction;
    }
}

package com.campusplug.api.listings.dto;

/**
 * Communicates to the frontend exactly which action buttons should be
 * enabled for a listing in its current state.
 *
 * State → allowed actions
 * ─────────────────────────────────────────────────────────────────────
 * ACTIVE  → canEdit, canMarkSold, canDelete
 * SOLD    → (none – terminal state)
 * DELETED → canRestore, canPurge
 * PENDING → (none – awaiting activation)
 */
public record ListingActions(
        boolean canEdit,
        boolean canMarkSold,
        boolean canDelete,
        boolean canRestore,
        boolean canPurge
) {
    public static ListingActions forActive() {
        return new ListingActions(true, true, true, false, false);
    }

    public static ListingActions forSold() {
        return new ListingActions(false, false, false, false, false);
    }

    public static ListingActions forDeleted() {
        return new ListingActions(false, false, false, true, true);
    }

    public static ListingActions forPending() {
        return new ListingActions(false, false, false, false, false);
    }
}

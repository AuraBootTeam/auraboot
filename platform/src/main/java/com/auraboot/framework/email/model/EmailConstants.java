package com.auraboot.framework.email.model;

/**
 * Constants for the Email CRM integration module.
 *
 * <p>All status/type/direction values that are stored in the database
 * must be referenced through this class — no magic strings allowed.</p>
 *
 * @since 6.5.0
 */
public final class EmailConstants {

    private EmailConstants() {
        // utility class
    }

    // ── Account type ─────────────────────────────────────────────────────────
    public static final String ACCOUNT_TYPE_PERSONAL = "personal";
    public static final String ACCOUNT_TYPE_SHARED   = "shared";

    // ── Provider ─────────────────────────────────────────────────────────────
    public static final String PROVIDER_GMAIL = "gmail";

    // ── Account status ───────────────────────────────────────────────────────
    public static final String ACCOUNT_STATUS_ACTIVE   = "active";
    public static final String ACCOUNT_STATUS_INACTIVE = "inactive";
    public static final String ACCOUNT_STATUS_ERROR    = "error";

    // ── Sync mode ─────────────────────────────────────────────────────────────
    public static final String SYNC_MODE_MANUAL = "manual";
    public static final String SYNC_MODE_AUTO   = "auto";

    // ── Account member role ───────────────────────────────────────────────────
    public static final String MEMBER_ROLE_OWNER  = "owner";
    public static final String MEMBER_ROLE_MEMBER = "member";

    // ── Message direction ─────────────────────────────────────────────────────
    public static final String DIRECTION_INBOUND  = "inbound";
    public static final String DIRECTION_OUTBOUND = "outbound";

    // ── Record link type ──────────────────────────────────────────────────────
    public static final String LINK_TYPE_AUTO   = "auto";
    public static final String LINK_TYPE_MANUAL = "manual";

    // ── Tracking event type ───────────────────────────────────────────────────
    public static final String TRACKING_OPEN   = "open";
    public static final String TRACKING_CLICK  = "click";
    public static final String TRACKING_BOUNCE = "bounce";

    // ── Sequence status ───────────────────────────────────────────────────────
    public static final String SEQ_STATUS_DRAFT    = "draft";
    public static final String SEQ_STATUS_ACTIVE   = "active";
    public static final String SEQ_STATUS_PAUSED   = "paused";
    public static final String SEQ_STATUS_ARCHIVED = "archived";

    // ── Enrollment status ─────────────────────────────────────────────────────
    public static final String ENROLLMENT_ACTIVE      = "active";
    public static final String ENROLLMENT_PAUSED      = "paused";
    public static final String ENROLLMENT_COMPLETED   = "completed";
    public static final String ENROLLMENT_FAILED      = "failed";
    public static final String ENROLLMENT_UNSUBSCRIBED = "unsubscribed";
}

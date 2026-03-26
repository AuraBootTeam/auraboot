package com.auraboot.framework.common.constant;

/**
 * Unified status value constants.
 * All status values are lowercase to match the database convention.
 */
public final class StatusConstants {

    // Common lifecycle statuses
    public static final String ACTIVE = "active";
    public static final String INACTIVE = "inactive";
    public static final String DRAFT = "draft";
    public static final String PUBLISHED = "published";
    public static final String ARCHIVED = "archived";
    public static final String PENDING = "pending";
    public static final String ENABLED = "enabled";
    public static final String DISABLED = "disabled";

    // Process statuses
    public static final String RUNNING = "running";
    public static final String COMPLETED = "completed";
    public static final String FAILED = "failed";
    public static final String CANCELLED = "cancelled";
    public static final String SUSPENDED = "suspended";

    // Approval statuses
    public static final String APPROVED = "approved";
    public static final String REJECTED = "rejected";
    public static final String EXPIRED = "expired";

    // Import statuses
    public static final String IMPORTING = "importing";
    public static final String SUCCESS = "success";
    public static final String ROLLED_BACK = "rolled_back";
    public static final String PARSING = "parsing";
    public static final String VALIDATING = "validating";
    public static final String PREVIEWING = "previewing";

    // Plugin statuses
    public static final String INSTALLED = "installed";

    // Review statuses
    public static final String VISIBLE = "visible";
    public static final String HIDDEN = "hidden";
    public static final String REPORTED = "reported";

    // Payment statuses
    public static final String PAID = "paid";
    public static final String REFUNDED = "refunded";
    public static final String PAST_DUE = "past_due";
    public static final String TRIAL = "trial";
    public static final String GRACE = "grace";

    // Grant type
    public static final String GRANT = "grant";
    public static final String DENY = "deny";

    // Submission statuses
    public static final String SUBMITTED = "submitted";
    public static final String CONFIRMED = "confirmed";
    public static final String IN_PROGRESS = "in_progress";
    public static final String IN_REVIEW = "in_review";
    public static final String DEPRECATED = "deprecated";
    public static final String DELISTED = "delisted";

    // Task statuses
    public static final String TESTING = "testing";
    public static final String SKIPPED = "skipped";
    public static final String ACKNOWLEDGED = "acknowledged";

    // Deployment
    public static final String DEPLOYED = "deployed";

    // Issue tracking
    public static final String OPEN = "open";
    public static final String RESOLVED = "resolved";
    public static final String CLOSED = "closed";

    // Notification / delivery
    public static final String SENT = "sent";

    // SLA / monitoring
    public static final String WARNING = "warning";
    public static final String OVERDUE = "overdue";
    public static final String PAUSED = "paused";
    public static final String ESCALATED = "escalated";

    // Reconciliation
    public static final String APPLIED = "applied";

    // Entitlement
    public static final String RENEW = "renew";
    public static final String GRANT_FREE = "grant_free";
    public static final String ACTIVATE = "activate";

    // Rollback
    public static final String ROLLBACK = "rollback";

    // Upgrade
    public static final String UPGRADE = "upgrade";

    private StatusConstants() {
        throw new UnsupportedOperationException("Constants class");
    }
}

package com.auraboot.framework.workbench.mapper;

/** Projection queries must not expose old announcement hints to a replacement membership. */
public final class AnnouncementProjectionVisibility {
    private AnnouncementProjectionVisibility() {}

    // Literal concatenation keeps these predicates usable in MyBatis annotation constants.
    private static final String RECIPIENT="""
        EXISTS (SELECT 1 FROM ab_announcement_recipient ar
          JOIN ab_announcement_recipient_snapshot ars ON ars.tenant_id=ar.tenant_id AND ars.version_pid=ar.version_pid AND ars.sealed=TRUE
          JOIN ab_announcement aa ON aa.tenant_id=ar.tenant_id AND aa.active_version_pid=ar.version_pid AND aa.deleted_flag=FALSE
          JOIN ab_announcement_version_control ac ON ac.tenant_id=ar.tenant_id AND ac.version_pid=ar.version_pid AND ac.publication_state='published'
          JOIN ab_tenant_member am ON am.tenant_id=ar.tenant_id AND am.pid=ar.member_pid AND am.user_id=
        """;
    private static final String MEMBERSHIP="""
        .user_id AND am.status='active' AND am.deleted_flag=FALSE
          JOIN ab_user au ON au.id=am.user_id AND au.pid=ar.user_pid AND au.is_enabled=TRUE AND au.deleted_flag=FALSE
          WHERE ar.tenant_id=
        """;
    private static final String VERSION=".tenant_id AND ar.version_pid=";
    private static final String EXPIRY=".source_id AND (ac.expires_at IS NULL OR ac.expires_at>clock_timestamp()))";

    public static final String NOTIFICATION="(source_type IS DISTINCT FROM 'announcement' OR "+RECIPIENT+
        "ab_notification"+MEMBERSHIP+"ab_notification"+VERSION+"ab_notification"+EXPIRY+")";
    public static final String INBOX="(item_type IS DISTINCT FROM 'announcement_ack' OR "+RECIPIENT+
        "ab_inbox_item"+MEMBERSHIP+"ab_inbox_item"+VERSION+"ab_inbox_item"+EXPIRY+")";
}

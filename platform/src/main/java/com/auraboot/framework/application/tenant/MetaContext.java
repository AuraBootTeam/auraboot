package com.auraboot.framework.application.tenant;

import lombok.Getter;
import org.springframework.util.StringUtils;

/**
 * 租户上下文管理
 * 使用ThreadLocal存储当前线程的租户信息
 */
public class MetaContext {

    private static final ThreadLocal<MetaContext> HOLDER = new ThreadLocal<>();
    private static final ThreadLocal<Long> MEMBER_ID = new ThreadLocal<>();

    @Getter
    private final Long tenantId;



    @Getter
    private final Long userId;

    @Getter
    private final String userPid;

    @Getter
    private final String username;


    private MetaContext(Long tenantId, Long userId,String userPid,String username) {
        this.tenantId = tenantId;

        this.userId = userId;
        this.userPid= userPid;
        this.username = username;
    }

    /* ---------- static API ---------- */

    public static void setContext(Long tenantId, Long userId,String userPid,String username) {
        HOLDER.set(new MetaContext(tenantId,userId,userPid,username));
    }

    public static MetaContext get() {
        MetaContext ctx = HOLDER.get();
        if (ctx == null) {
            throw new IllegalStateException(
                    "MetaContext not initialized for current thread"
            );
        }
        return ctx;
    }

    public static boolean exists() {
        return HOLDER.get() != null;
    }

    public static void clear() {
        HOLDER.remove();
        MEMBER_ID.remove();
    }

    public static Long getCurrentMemberId() {
        return MEMBER_ID.get();
    }

    public static void setMemberId(Long memberId) {
        MEMBER_ID.set(memberId);
    }

    /**
     * Set tenant-scoped context for system/background work with no user identity.
     */
    public static void setSystemTenantContext(Long tenantId) {
        HOLDER.set(new MetaContext(tenantId, null, null, null));
    }


    public static   Long getCurrentTenantId() {
        return get().getTenantId();
    }

    public static   String getCurrentTenantIdAsString() {
        return get().getTenantId()+"";
    }

    public static   Long getCurrentUserId() {
        return get().getUserId();
    }

    public static   String getCurrentUserPid() {
        return get().getUserPid();
    }




    public static String getCurrentUsername() {
        return get().getUsername();
    }

    /**
     * @deprecated Use {@link #setContext(Long, Long, String, String)} instead.
     * Setting tenantId alone may leave userId/userPid/username as null, causing inconsistency.
     * Only acceptable in scheduled tasks or batch jobs where no user context exists.
     */
    @Deprecated(since = "6.1.0")
    public static void setCurrentTenantId(Long tenantId) {
        MetaContext current = HOLDER.get();
        if (current != null) {
            HOLDER.set(new MetaContext(tenantId, current.userId, current.userPid, current.username));
            return;
        }
        setSystemTenantContext(tenantId);
    }





    /**
     * @deprecated Use {@link #setContext(Long, Long, String, String)} instead.
     * Setting userId alone may leave tenantId as null, causing inconsistency.
     */
    @Deprecated(since = "6.1.0")
    public static void setCurrentUserId(Long userId) {
        MetaContext current = HOLDER.get();
        if (current != null) {
            HOLDER.set(new MetaContext(current.tenantId, userId, current.userPid, current.username));
        } else {
            HOLDER.set(new MetaContext(null, userId, null, null));
        }
    }



}

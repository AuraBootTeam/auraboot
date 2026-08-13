package com.auraboot.framework.application.tenant;

import lombok.Getter;
import org.springframework.util.StringUtils;

import java.util.Set;

/**
 * 租户上下文管理
 * 使用ThreadLocal存储当前线程的租户信息
 */
public class MetaContext {

    private static final ThreadLocal<MetaContext> HOLDER = new ThreadLocal<>();
    private static final ThreadLocal<Long> MEMBER_ID = new ThreadLocal<>();
    private static final ThreadLocal<Long> ENV_ID = new ThreadLocal<>();
    /** OTel trace id snapshotted at the request seam so async workers / LLM call sites
     *  can correlate without an active span (A-G6 / §2.6). */
    private static final ThreadLocal<String> OTEL_TRACE_ID = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> ENV_FILTER_BYPASSED = ThreadLocal.withInitial(() -> false);
    private static final ThreadLocal<Boolean> LOCK_GUARD_BYPASSED = ThreadLocal.withInitial(() -> false);
    private static final ThreadLocal<String> COMMAND_AUTHORITY = new ThreadLocal<>();
    /** Exact command code whose permit plan authorized the current mutation stages. */
    private static final ThreadLocal<String> AUTHORIZED_COMMAND_CODE = new ThreadLocal<>();
    /** Aggregate root (master document) the current command was authorized against. */
    private static final ThreadLocal<String> COMMAND_AGGREGATE = new ThreadLocal<>();
    /**
     * The data-layer execution part of an authoritative command permit plan. Kept as low-level
     * strings so this tenant-context package does not depend on pipeline types.
     */
    private static final ThreadLocal<CommandPermitExecution> COMMAND_PERMIT = new ThreadLocal<>();

    private static final class CommandPermitExecution {
        private final String scopeGrade;
        private final String targetModel;
        private final String targetRecordId;
        private Long expectedVersion;

        private CommandPermitExecution(
                String scopeGrade, Long expectedVersion, String targetModel, String targetRecordId) {
            this.scopeGrade = scopeGrade;
            this.expectedVersion = expectedVersion;
            this.targetModel = targetModel;
            this.targetRecordId = targetRecordId;
        }
    }

    @Getter
    private final Long tenantId;



    @Getter
    private final Long userId;

    @Getter
    private final String userPid;

    @Getter
    private final String username;

    private final Set<Long> roleIds;


    private MetaContext(Long tenantId, Long userId, String userPid, String username, Set<Long> roleIds) {
        this.tenantId = tenantId;
        this.userId = userId;
        this.userPid = userPid;
        this.username = username;
        this.roleIds = roleIds;
    }

    /* ---------- static API ---------- */

    public static void setContext(Long tenantId, Long userId, String userPid, String username) {
        setContext(tenantId, userId, userPid, username, Set.of());
    }

    public static void setContext(Long tenantId, Long userId, String userPid, String username,
                                  Set<Long> roleIds) {
        Set<Long> snapshot = roleIds == null
            ? Set.of()
            : Set.copyOf(roleIds);
        HOLDER.set(new MetaContext(tenantId, userId, userPid, username, snapshot));
    }

    public static Set<Long> getCurrentRoleIds() {
        MetaContext ctx = HOLDER.get();
        return ctx == null ? Set.of() : ctx.roleIds;
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
        ENV_ID.remove();
        OTEL_TRACE_ID.remove();
        ENV_FILTER_BYPASSED.remove();
        LOCK_GUARD_BYPASSED.remove();
        COMMAND_AUTHORITY.remove();
        AUTHORIZED_COMMAND_CODE.remove();
        COMMAND_AGGREGATE.remove();
        COMMAND_PERMIT.remove();
    }

    /**
     * Immutable capture of the identity + correlation ThreadLocals for propagation
     * across an async boundary (e.g. {@code TenantAwareTaskDecorator}, IM {@code @Async}).
     *
     * <p>Deliberately EXCLUDES guard relaxations and command permit state. Propagating either into
     * a worker thread would let background work silently inherit a foreground request's authority.
     * An async command must reconstruct its persisted permit plan explicitly.
     */
    public record Snapshot(Long tenantId, Long userId, String userPid, String username,
                           Set<Long> roleIds, Long memberId, Long envId, String otelTraceId) {}

    /**
     * Capture the current thread's identity + correlation context for later
     * {@link #restore}. Returns {@code null} when no context is set (mirrors
     * {@link #exists()}); the async decorator then runs undecorated.
     */
    public static Snapshot snapshot() {
        MetaContext ctx = HOLDER.get();
        if (ctx == null) {
            return null;
        }
        return new Snapshot(ctx.tenantId, ctx.userId, ctx.userPid, ctx.username, ctx.roleIds,
                MEMBER_ID.get(), ENV_ID.get(), OTEL_TRACE_ID.get());
    }

    /**
     * Install a previously captured {@link #snapshot()} onto the current thread.
     * No-op for {@code null}. Pair with {@link #clear()} in a finally block.
     */
    public static void restore(Snapshot s) {
        if (s == null) {
            return;
        }
        HOLDER.set(new MetaContext(s.tenantId(), s.userId(), s.userPid(), s.username(), s.roleIds()));
        MEMBER_ID.set(s.memberId());
        ENV_ID.set(s.envId());
        OTEL_TRACE_ID.set(s.otelTraceId());
    }

    /** Snapshotted OTel trace id for the current thread (A-G6 correlation); may be null. */
    public static void setOtelTraceId(String otelTraceId) {
        OTEL_TRACE_ID.set(otelTraceId);
    }

    public static String getOtelTraceId() {
        return OTEL_TRACE_ID.get();
    }

    // ---- env-layering extension (PoC) ----

    /**
     * Get the current environment id. May be null if the request did not specify one and the
     * tenant has no default env yet.
     */
    public static Long getCurrentEnvironmentId() {
        return ENV_ID.get();
    }

    /**
     * Set the current environment id. Typically called by EnvironmentResolverFilter from
     * {@code ?env=} or {@code X-Environment} header, or by services starting background work.
     */
    public static void setEnvironmentId(Long envId) {
        ENV_ID.set(envId);
    }

    /**
     * @return true if env filter is currently bypassed (cross-env queries such as promotion).
     */
    public static boolean isEnvFilterBypassed() {
        return Boolean.TRUE.equals(ENV_FILTER_BYPASSED.get());
    }

    /**
     * Run a block with env filtering disabled. Use sparingly — only for legitimate cross-env
     * reads/writes (promotion source→target). State is restored even on exception.
     */
    public static <T> T runWithoutEnvFilter(java.util.function.Supplier<T> action) {
        Boolean prior = ENV_FILTER_BYPASSED.get();
        ENV_FILTER_BYPASSED.set(true);
        try {
            return action.get();
        } finally {
            ENV_FILTER_BYPASSED.set(prior);
        }
    }

    /**
     * Run a block with env filtering disabled (no return value).
     */
    public static void runWithoutEnvFilter(Runnable action) {
        runWithoutEnvFilter(() -> {
            action.run();
            return null;
        });
    }

    // ---- env lock guard (env-layering #17) ----

    /**
     * @return true if the lock guard is currently bypassed (legitimate writes such as
     *         {@code promotion.apply} that target a locked env via four-eyes flow).
     */
    public static boolean isLockGuardBypassed() {
        return Boolean.TRUE.equals(LOCK_GUARD_BYPASSED.get());
    }

    /**
     * Run a block with the lock guard disabled. Use sparingly — only for the legitimate
     * writes-to-locked-env path: promotion apply, plugin import bootstrap, system migrations.
     * State is restored even on exception.
     */
    public static <T> T runWithoutLockGuard(java.util.function.Supplier<T> action) {
        Boolean prior = LOCK_GUARD_BYPASSED.get();
        LOCK_GUARD_BYPASSED.set(true);
        try {
            return action.get();
        } finally {
            LOCK_GUARD_BYPASSED.set(prior);
        }
    }

    public static void runWithoutLockGuard(Runnable action) {
        runWithoutLockGuard(() -> {
            action.run();
            return null;
        });
    }

    /**
     * Run {@code action} under the authority a command boundary already granted — the permission
     * the caller was checked against before the handler was allowed to run at all
     * (DDR-2026-07-22, command-scoped data authority).
     *
     * <p>This carries a DECISION, not a switch. It is only ever opened for a verdict of
     * {@code AUTHORIZED}: a command that declared no permissions granted nothing, and opening a
     * scope on its behalf would hand it authority nobody conferred. Consumers must treat the code
     * as the reason they are permitted to act, and it is recorded so an audit can name it.</p>
     *
     * <p>What this does NOT relax: the tenant partition (a different holder entirely — identity is
     * not a permission), the actor recorded on writes, and any read the caller themselves asked
     * for. It changes only whether a HANDLER's own bookkeeping is re-projected through the read
     * permission of the caller who was already authorized to trigger it.</p>
     *
     * <p>Like the guard-bypass flags, this is deliberately NOT part of {@link Snapshot}: it must
     * never ride into a worker thread implicitly. The async command path re-establishes it from the
     * persisted verdict instead, so background work carries an authority someone can point at.</p>
     */
    public static <T> T runWithCommandAuthority(String permissionCode, java.util.function.Supplier<T> action) {
        if (permissionCode == null || permissionCode.isBlank()) {
            // An authority with no permission behind it is a contradiction: there would be nothing to
            // point at when asked why the work was allowed. Without this, opening a scope for a
            // NOT_APPLICABLE verdict would be silently harmless-looking (its code is null, so nothing
            // reads as authorized) while encoding exactly the mistake this design exists to prevent.
            throw new IllegalArgumentException(
                    "A command authority scope must name the permission that granted it");
        }
        String prior = COMMAND_AUTHORITY.get();
        COMMAND_AUTHORITY.set(permissionCode);
        try {
            return action.get();
        } finally {
            // Restore, never clear: nested commands must not strip the outer scope on the way out.
            COMMAND_AUTHORITY.set(prior);
        }
    }

    public static void runWithCommandAuthority(String permissionCode, Runnable action) {
        runWithCommandAuthority(permissionCode, () -> {
            action.run();
            return null;
        });
    }

    /** The permission a command boundary granted for the work running on this thread, or null. */
    public static String getCommandAuthority() {
        return COMMAND_AUTHORITY.get();
    }

    public static boolean hasCommandAuthority() {
        return COMMAND_AUTHORITY.get() != null;
    }

    /**
     * Run mutation work under the exact command identity attached to an already-published permit
     * plan. A command code alone is not authority: callers must open this scope from inside
     * {@link #runWithCommandPermitPlan}, and field-writer enforcement checks both scopes.
     *
     * <p>This value is deliberately excluded from {@link Snapshot}. Async command handlers restore
     * it only from their persisted command code together with their persisted permit plan.</p>
     */
    public static <T> T runWithAuthorizedCommandCode(
            String commandCode, java.util.function.Supplier<T> action) {
        if (commandCode == null || commandCode.isBlank()) {
            throw new IllegalArgumentException("An authorized command writer scope must name its command code");
        }
        if (!hasCommandPermitScope()) {
            throw new IllegalStateException(
                    "An authorized command writer scope requires a published command permit plan");
        }
        String prior = AUTHORIZED_COMMAND_CODE.get();
        AUTHORIZED_COMMAND_CODE.set(commandCode);
        try {
            return action.get();
        } finally {
            AUTHORIZED_COMMAND_CODE.set(prior);
        }
    }

    public static void runWithAuthorizedCommandCode(String commandCode, Runnable action) {
        runWithAuthorizedCommandCode(commandCode, () -> {
            action.run();
            return null;
        });
    }

    /** Exact command code trusted for current mutation stages, or null outside a permit plan. */
    public static String getAuthorizedCommandCode() {
        return AUTHORIZED_COMMAND_CODE.get();
    }

    /**
     * Run {@code action} pinned to the aggregate root the request named — the master document
     * the entry actually authorized.
     *
     * <p>This is the other half of {@link #runWithCommandAuthority}. That one says <em>what</em>
     * the caller was allowed to do; this one says <em>which document</em> they were allowed to do
     * it to. Writes to models that declare an aggregate binding are pinned to this id in the SQL,
     * so a command authorized for Q1001 cannot reach Q2002's rows even though both live in the
     * same table and the same capability covers them.</p>
     *
     * <p>Opening this scope only ever <em>adds</em> a constraint, so it is safe to open on every
     * command that names a target; models that declare no binding are unaffected.</p>
     *
     * <p>Like the other command-scoped state, this deliberately does not ride into worker threads
     * via {@link Snapshot}: the async path re-establishes it from the persisted task instead.</p>
     */
    public static <T> T runWithCommandAggregate(String aggregateId, java.util.function.Supplier<T> action) {
        if (aggregateId == null || aggregateId.isBlank()) {
            // Pinning to "nothing" would silently widen every guarded write back to the whole
            // table while still reading as though a boundary were in force.
            throw new IllegalArgumentException("A command aggregate scope must name the aggregate it pins to");
        }
        String prior = COMMAND_AGGREGATE.get();
        COMMAND_AGGREGATE.set(aggregateId);
        try {
            return action.get();
        } finally {
            // Restore, never clear: a nested command must not strip the outer aggregate on exit.
            COMMAND_AGGREGATE.set(prior);
        }
    }

    public static void runWithCommandAggregate(String aggregateId, Runnable action) {
        runWithCommandAggregate(aggregateId, () -> {
            action.run();
            return null;
        });
    }

    /** The aggregate root this thread's work was authorized against, or null when unscoped. */
    public static String getCommandAggregateId() {
        return COMMAND_AGGREGATE.get();
    }

    /**
     * Run {@code action} under the row-scope grade the permit plan resolved for this command, so the
     * data layer can honour that one boundary decision rather than re-resolving scope at each of its
     * sites. Like {@link #runWithCommandAggregate}, opening the scope only carries a decision the
     * boundary already made; it restores (never clears) on exit so a nested command cannot strip the
     * outer scope. A {@code null}/blank grade is a no-op (scope stays unresolved and the data layer
     * keeps its own evaluation).
     */
    public static <T> T runWithCommandPermitScope(String scopeGrade, java.util.function.Supplier<T> action) {
        return runWithCommandPermitPlan(scopeGrade, null, null, null, action);
    }

    /**
     * Run the data-layer portion of a permitted command under the boundary's resolved row scope and
     * server-captured target version. The version belongs only to {@code targetModel/targetRecordId};
     * derived aggregate writes must not accidentally compare their row version with the root's.
     */
    public static <T> T runWithCommandPermitPlan(
            String scopeGrade,
            Long expectedVersion,
            String targetModel,
            String targetRecordId,
            java.util.function.Supplier<T> action) {
        if (scopeGrade == null || scopeGrade.isBlank()) {
            return action.get();
        }
        CommandPermitExecution prior = COMMAND_PERMIT.get();
        COMMAND_PERMIT.set(new CommandPermitExecution(
                scopeGrade, expectedVersion, targetModel, targetRecordId));
        try {
            return action.get();
        } finally {
            COMMAND_PERMIT.set(prior);
        }
    }

    public static void runWithCommandPermitPlan(
            String scopeGrade,
            Long expectedVersion,
            String targetModel,
            String targetRecordId,
            Runnable action) {
        runWithCommandPermitPlan(scopeGrade, expectedVersion, targetModel, targetRecordId, () -> {
            action.run();
            return null;
        });
    }

    public static void runWithCommandPermitScope(String scopeGrade, Runnable action) {
        runWithCommandPermitScope(scopeGrade, () -> {
            action.run();
            return null;
        });
    }

    /**
     * The row-scope grade the current command was authorized under ("SELF"/"ALL"), or null.
     *
     * <p>This unqualified accessor is for context propagation and diagnostics. Data-access callers
     * must use {@link #getCommandPermitScopeFor(String)} so a target model's grade is not inherited
     * by unrelated models touched inside the same command.</p>
     */
    public static String getCommandPermitScope() {
        CommandPermitExecution permit = COMMAND_PERMIT.get();
        return permit == null ? null : permit.scopeGrade;
    }

    /**
     * The current command grade when it is authoritative for {@code modelCode}, otherwise null.
     *
     * <p>A normal command plan carries its target model, so its row grade is authoritative only for
     * that model. Other models must evaluate their own data-scope policy. A null target is retained
     * as a global scope for the legacy explicit {@link #runWithCommandPermitScope} bridge.</p>
     */
    public static String getCommandPermitScopeFor(String modelCode) {
        CommandPermitExecution permit = COMMAND_PERMIT.get();
        if (permit == null
                || (permit.targetModel != null
                && !java.util.Objects.equals(permit.targetModel, modelCode))) {
            return null;
        }
        return permit.scopeGrade;
    }

    /** Whether a resolved command permit plan exists, for propagation and diagnostics. */
    public static boolean hasCommandPermitScope() {
        return COMMAND_PERMIT.get() != null;
    }

    /** Whether the current command permit grade is authoritative for {@code modelCode}. */
    public static boolean hasCommandPermitScopeFor(String modelCode) {
        return getCommandPermitScopeFor(modelCode) != null;
    }

    /**
     * Return the public record PID pinned by the current permit plan for this model.
     * A mismatched model never inherits another model's target.
     */
    public static String getCommandPermitTargetFor(String modelCode) {
        CommandPermitExecution permit = COMMAND_PERMIT.get();
        if (permit == null
                || !java.util.Objects.equals(permit.targetModel, modelCode)) {
            return null;
        }
        return permit.targetRecordId;
    }

    /**
     * The optimistic version captured for this exact command target, or null for a derived row,
     * an unversioned target, or a direct non-command call.
     */
    public static Long getCommandExpectedVersion(String modelCode, String recordId) {
        CommandPermitExecution permit = COMMAND_PERMIT.get();
        if (permit == null
                || permit.expectedVersion == null
                || !java.util.Objects.equals(permit.targetModel, modelCode)
                || !java.util.Objects.equals(permit.targetRecordId, recordId)) {
            return null;
        }
        return permit.expectedVersion;
    }

    /**
     * Advance the in-flight target version after a successful guarded write. A command may have
     * both FIELD_MAP and handler-result persistence; the second write must compare with the version
     * produced by the first, not with the stale boundary snapshot.
     */
    public static void advanceCommandExpectedVersion(String modelCode, String recordId) {
        CommandPermitExecution permit = COMMAND_PERMIT.get();
        if (permit != null
                && permit.expectedVersion != null
                && java.util.Objects.equals(permit.targetModel, modelCode)
                && java.util.Objects.equals(permit.targetRecordId, recordId)) {
            permit.expectedVersion = permit.expectedVersion + 1;
        }
    }

    public static Long getCurrentMemberId() {
        return MEMBER_ID.get();
    }

    public static void setMemberId(Long memberId) {
        MEMBER_ID.set(memberId);
    }

    public static void clearMemberId() {
        MEMBER_ID.remove();
    }

    /**
     * Set tenant-scoped context for system/background work with no user identity.
     */
    public static void setSystemTenantContext(Long tenantId) {
        HOLDER.set(new MetaContext(tenantId, null, null, null, Set.of()));
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
            HOLDER.set(new MetaContext(tenantId, current.userId, current.userPid, current.username, current.roleIds));
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
            HOLDER.set(new MetaContext(current.tenantId, userId, current.userPid, current.username, current.roleIds));
        } else {
            HOLDER.set(new MetaContext(null, userId, null, null, Set.of()));
        }
    }



}

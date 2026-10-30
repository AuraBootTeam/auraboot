package com.auraboot.framework.plugin.pf4j;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.exception.MetaRecordNotFoundException;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPipelineContext;
import com.auraboot.framework.permission.service.PermissionFacade;
import com.auraboot.framework.plugin.extension.RecordActionScopeAccessor;
import lombok.RequiredArgsConstructor;
import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;

/** Scalar scope checks; tenant-bounded records never leave the host's authorization boundary. */
@Component
@RequiredArgsConstructor
public class CommandRecordActionScopeAccessorFactory {
    private final ApplicationContext beans;
    private final PlatformTransactionManager transactions;
    private final SqlSessionFactory sessions;

    private record Actor(Long tenant, Long userId, Long memberId, String userPid) {}

    public static final class InvocationScope implements AutoCloseable {
        private volatile boolean active = true;
        private final Thread thread = Thread.currentThread();
        private RecordActionScopeAccessor accessor;
        public RecordActionScopeAccessor accessor() { return accessor; }
        @Override public void close() { active = false; }
    }

    /** The pre-idempotency callback may not have a command transaction yet. */
    public InvocationScope forInvocation(CommandPipelineContext context) {
        var scope = new InvocationScope();
        var actor = actor(context);
        if (actor != null) scope.accessor = accessor(actor, () -> scope.active && Thread.currentThread() == scope.thread);
        return scope;
    }

    /** The handler capability expires with its original physical Core transaction. */
    public RecordActionScopeAccessor forCommand(CommandPipelineContext context) {
        var actor = actor(context);
        if (actor == null || !TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                || !TransactionSynchronizationManager.isSynchronizationActive()) return null;
        var source = sessions.getConfiguration().getEnvironment().getDataSource();
        Object resource = TransactionSynchronizationManager.getResource(source);
        if (!(resource instanceof ConnectionHolder holder) || !holder.isSynchronizedWithTransaction()) return null;
        Connection connection = holder.getConnection();
        try { if (connection.getAutoCommit() || connection.isReadOnly() || connection.isClosed()) return null; }
        catch (SQLException failure) { throw new IllegalStateException("Cannot validate the Core scope transaction", failure); }
        var lifetime = new TransactionSynchronization() {};
        TransactionSynchronizationManager.registerSynchronization(lifetime);
        return accessor(actor, () -> TransactionSynchronizationManager.isSynchronizationActive()
                && TransactionSynchronizationManager.getSynchronizations().contains(lifetime)
                && TransactionSynchronizationManager.getResource(source) == holder
                && holder.getConnection() == connection);
    }

    private Actor actor(CommandPipelineContext context) {
        if (context == null || context.getAuthorizationVerdict() == null
                || !context.getAuthorizationVerdict().isAuthorized() || !MetaContext.exists()
                || com.auraboot.framework.application.security.MachineDataAccessContext.isActive()
                || com.auraboot.framework.openplatform.service.InstalledIntegrationExecution.active()) return null;
        var actor = current();
        return actor.tenant() != null && actor.tenant() > 0 && actor.userId() != null && actor.userId() > 0
                && actor.memberId() != null && actor.memberId() > 0
                && actor.userPid() != null && !actor.userPid().isBlank()
                && actor.tenant().equals(context.getTenantId()) && actor.userId().equals(context.getUserId()) ? actor : null;
    }

    private static Actor current() {
        return new Actor(MetaContext.getCurrentTenantId(), MetaContext.getCurrentUserId(),
                MetaContext.getCurrentMemberId(), MetaContext.getCurrentUserPid());
    }

    private RecordActionScopeAccessor accessor(Actor actor, BooleanSupplier lifetime) {
        return (model, pid, action) -> {
            exact(model, 128); exact(pid, 64);
            if (action == null || !Set.of("read", "update", "delete").contains(action))
                throw new IllegalArgumentException("An explicit read/update/delete action is required");
            if (!lifetime.getAsBoolean() || !MetaContext.exists() || !actor.equals(current())) return false;
            var read = new TransactionTemplate(transactions);
            read.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            read.setReadOnly(true);
            return Boolean.TRUE.equals(read.execute(status -> permits(actor, model, pid, action)));
        };
    }

    private boolean permits(Actor actor, String model, String pid, String action) {
        Map<String,Object> record;
        try {
            record = MetaContext.runWithCommandPermitScope("ALL", () -> beans.getBean(DynamicDataService.class).getById(model, pid));
        } catch (MetaRecordNotFoundException missing) { return false; }
        if (record == null || !pid.equals(record.get("pid")) || Boolean.TRUE.equals(record.get("deleted_flag"))) return false;
        Object tenant = record.get("tenant_id");
        if (!(tenant instanceof Number id) || !Objects.equals(id.longValue(), actor.tenant())) return false;
        var read = beans.getBean(PermissionFacade.class).canOperate(actor.memberId(), model, "read", record);
        if (read == null || !read.granted()) return false;
        return "read".equals(action) || CommandRecordActionScopePolicy.permits(beans, actor.tenant(), actor.memberId(),
                actor.userPid(), model, pid, action, record);
    }

    private static void exact(String value, int max) {
        if (value == null || value.isBlank() || !value.equals(value.strip()) || value.length() > max
                || value.codePoints().anyMatch(cp -> Character.isISOControl(cp) || Character.getType(cp) == Character.FORMAT))
            throw new IllegalArgumentException("An exact model code and public record PID are required");
    }
}

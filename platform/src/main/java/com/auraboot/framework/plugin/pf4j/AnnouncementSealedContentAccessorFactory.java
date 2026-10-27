package com.auraboot.framework.plugin.pf4j;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.service.impl.AsyncTaskExecutionContext;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPipelineContext;
import com.auraboot.framework.plugin.extension.AnnouncementSealedContentAccessor;
import com.auraboot.framework.plugin.extension.AnnouncementSealedContentAccessor.*;
import com.auraboot.framework.workbench.service.AnnouncementContentSealService;
import lombok.RequiredArgsConstructor;
import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.*;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;

/** Original-handler capability issuance; commit proofs outlive the handler but never their physical transaction. */
@Component
@RequiredArgsConstructor
public class AnnouncementSealedContentAccessorFactory {
    private final SqlSessionFactory sessions;
    private final AnnouncementContentSealService content;
    private static final ThreadLocal<Scope> ACTIVE = new ThreadLocal<>();

    public final class Scope implements AutoCloseable {
        private final Actor actor;
        private final String command, model, target;
        private final ConnectionHolder holder;
        private final Connection connection;
        private final Thread thread=Thread.currentThread();
        private final TransactionSynchronization lifetime=new TransactionSynchronization() {};
        private boolean closed;
        private PublishedContent proof;
        private final AnnouncementSealedContentAccessor accessor=this::require;
        private Scope(Actor actor,String command,String model,String target,ConnectionHolder holder,Connection connection) {
            this.actor=actor;this.command=command;this.model=model;this.target=target;this.holder=holder;this.connection=connection;
        }
        public AnnouncementSealedContentAccessor accessor() { return accessor; }
        @Override public void close() { closed=true;if(ACTIVE.get()==this)ACTIVE.remove(); }
        private PublishedContent require(ExactVersion expected) {
            try {
                if(expected==null || closed || ACTIVE.get()!=this || !originalFrame()) throw denied();
                transaction();
                if(proof!=null && !proof.exactVersion().equals(expected))
                    throw new IllegalArgumentException("Declare one exact announcement proof per command handler");
                String member=content.lockActor();
                var verified=content.requirePublished(expected,member);
                if(proof==null) {
                    proof=verified;
                    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                        @Override public void beforeCommit(boolean readOnly) {
                            // The command frame normally ended already. Its immutable proof remains bound to this TX.
                            transaction();
                            var current=content.requirePublished(proof.exactVersion(),content.lockActor());
                            sameProof(proof,current);
                        }
                    });
                } else sameProof(proof,verified);
                return verified;
            } catch(RuntimeException failure) {
                if(Thread.currentThread()==thread) holder.setRollbackOnly();
                throw failure;
            }
        }
        private boolean originalFrame() {
            return MetaContext.isRootAuthorizedCommand() && command.equals(MetaContext.getAuthorizedCommandCode())
                    && target.equals(MetaContext.getCommandPermitTargetFor(model))
                    && !AsyncTaskExecutionContext.isActive() && !MetaContext.isImpersonating()
                    && !com.auraboot.framework.application.security.MachineDataAccessContext.isActive()
                    && !com.auraboot.framework.openplatform.service.InstalledIntegrationExecution.active();
        }
        private void transaction() {
            if(Thread.currentThread()!=thread || !actor.equals(current()) || !writable()
                    || resource()!=holder || holder.getConnection()!=connection
                    || !TransactionSynchronizationManager.getSynchronizations().contains(lifetime)) throw denied();
            validateConnection(connection);
        }
    }

    public Scope forCommand(CommandPipelineContext context) {
        if(ACTIVE.get()!=null || context==null || context.getRequest()==null || context.getRequest().isDryRun()
                || context.getCommand()==null || context.getPermitPlan()==null || !context.getPermitPlan().isPermitted()
                || context.getAuthorizationVerdict()==null || !context.getAuthorizationVerdict().isAuthorized()
                || !Boolean.TRUE.equals(context.getTargetRecordReadable()) || !MetaContext.exists()
                || !MetaContext.isRootAuthorizedCommand() || MetaContext.isImpersonating() || !writable()
                || AsyncTaskExecutionContext.isActive()
                || com.auraboot.framework.application.security.MachineDataAccessContext.isActive()
                || com.auraboot.framework.openplatform.service.InstalledIntegrationExecution.active()) return null;
        Actor actor=current();
        String model=context.getCommand().getModelCode(),command=context.getCommand().getCode();
        String target=MetaContext.getCommandPermitTargetFor(model);
        if(actor.tenant()==null || actor.user()==null || actor.member()==null || actor.userPid()==null
                || actor.userPid().isBlank() || !Objects.equals(actor.tenant(),context.getTenantId())
                || !Objects.equals(actor.user(),context.getUserId()) || target==null || target.isBlank()
                || !target.equals(context.getRequest().getTargetRecordId())
                || !Objects.equals(command,MetaContext.getAuthorizedCommandCode())) return null;
        if(!(resource() instanceof ConnectionHolder holder) || !holder.isSynchronizedWithTransaction()) return null;
        Connection connection=holder.getConnection();validateConnection(connection);
        Scope scope=new Scope(actor,command,model,target,holder,connection);
        TransactionSynchronizationManager.registerSynchronization(scope.lifetime);
        ACTIVE.set(scope);
        return scope;
    }
    private Object resource() {
        return TransactionSynchronizationManager.getResource(sessions.getConfiguration().getEnvironment().getDataSource());
    }
    private static boolean writable() {
        return TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()
                && !TransactionSynchronizationManager.isCurrentTransactionReadOnly();
    }
    private static void validateConnection(Connection connection) {
        try { if(connection.isClosed() || connection.isReadOnly() || connection.getAutoCommit()) throw denied(); }
        catch(SQLException failure) { throw new IllegalStateException("Cannot validate the physical Core transaction",failure); }
    }
    private record Actor(Long tenant,Long user,Long member,String userPid) {}
    private static Actor current() {
        return new Actor(MetaContext.getCurrentTenantId(),MetaContext.getCurrentUserId(),
                MetaContext.getCurrentMemberId(),MetaContext.getCurrentUserPid());
    }
    private static void sameProof(PublishedContent before,PublishedContent after) {
        if(!before.exactVersion().equals(after.exactVersion()) || !before.snapshotHash().equals(after.snapshotHash())
                || !before.document().equals(after.document()) || !before.encoded().equals(after.encoded())
                || !before.approvedBinding().equals(after.approvedBinding()) || !before.sealIdentity().equals(after.sealIdentity())
                || !before.actorMemberPid().equals(after.actorMemberPid()) || !Objects.equals(before.validUntil(),after.validUntil()))
            throw denied();
    }
    private static AccessDeniedException denied() { return new AccessDeniedException("Original command sealed-content proof is unavailable"); }
}

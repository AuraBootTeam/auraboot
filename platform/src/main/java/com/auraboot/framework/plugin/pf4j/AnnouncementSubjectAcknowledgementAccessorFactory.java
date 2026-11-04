package com.auraboot.framework.plugin.pf4j;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.service.impl.AsyncTaskExecutionContext;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPipelineContext;
import com.auraboot.framework.plugin.extension.*;
import com.auraboot.framework.plugin.extension.AnnouncementAcknowledgementAccessor.*;
import com.auraboot.framework.plugin.extension.AnnouncementSubjectAcknowledgementAccessor.SubjectChecks;
import com.auraboot.framework.plugin.extension.CommandSubjectAcknowledgementSource.*;
import com.auraboot.framework.workbench.service.AnnouncementSubjectAcknowledgementService;
import lombok.RequiredArgsConstructor;
import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.*;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;

/** Host-only issuance from the registered primary's immutable source, before replay and inside the original TX. */
@Component
@RequiredArgsConstructor
public class AnnouncementSubjectAcknowledgementAccessorFactory {
    private final SqlSessionFactory sessions;
    private final ExtensionRegistry extensions;
    private final BackgroundDataAccessor records;
    private final CommandRecordActionScopeAccessorFactory recordScopes;
    private final CommandMembershipLockAccessor memberships;
    private final IdentityDirectoryAccessor identities;
    private final AnnouncementSubjectAcknowledgementService acknowledgements;

    private record Actor(Long tenant,Long user,Long member,String pid) {}
    private static Actor actor(){return new Actor(MetaContext.getCurrentTenantId(),MetaContext.getCurrentUserId(),MetaContext.getCurrentMemberId(),MetaContext.getCurrentUserPid());}
    private static String handlerCode(CommandPipelineContext c){var configured=c.getExecConfig().get("handler");return configured instanceof String s&&!s.isBlank()?s.strip():c.getCommandCode();}
    private CommandHandlerExtension primary(CommandPipelineContext c){return extensions.getCommandHandler(handlerCode(c)).orElse(null);}
    private static boolean writable(){return TransactionSynchronizationManager.isActualTransactionActive()&&TransactionSynchronizationManager.isSynchronizationActive()&&!TransactionSynchronizationManager.isCurrentTransactionReadOnly();}
    private Object resource(){return TransactionSynchronizationManager.getResource(sessions.getConfiguration().getEnvironment().getDataSource());}
    private static void connection(Connection c){try{if(c.isClosed()||c.isReadOnly()||c.getAutoCommit())throw denied();}catch(SQLException e){throw new IllegalStateException("Cannot validate original Core connection",e);}}
    private State state(){if(!TransactionSynchronizationManager.isSynchronizationActive())return null;return TransactionSynchronizationManager.getSynchronizations().stream().filter(s->s instanceof State p&&p.owner==this).map(s->(State)s).findFirst().orElse(null);}

    /** Called by the guarded phase BEFORE the idempotency lookup, including cached outcomes. */
    public void prepare(CommandPipelineContext context){
        var handler=primary(context);if(!(handler instanceof CommandSubjectAcknowledgementSource source))return;
        if(state()!=null)throw denied();
        var a=actor();var invocation=context.getHandlerInvocationAuthorization();
        if(!(context.getExecConfig().get("handlerParams")instanceof Map<?,?> params)||!Boolean.TRUE.equals(params.get(TenantProjectionAccessor.OPT_IN_HANDLER_PARAM)))throw denied();
        if(!MetaContext.exists()||!writable()||context.getRequest()==null||context.getRequest().isDryRun()||context.getCommand()==null
                ||context.getAuthorizationVerdict()==null||!context.getAuthorizationVerdict().isAuthorized()
                ||invocation==null
                ||!Objects.equals(invocation.commandCode(),context.getCommandCode())||!Objects.equals(invocation.modelCode(),context.getCommand().getModelCode())
                ||!Objects.equals(invocation.targetRecordId(),context.getRequest().getTargetRecordId())
                ||!Objects.equals(context.getCommandCode(),context.getCommand().getCode())
                ||a.tenant()==null||a.tenant()<1||a.user()==null||a.user()<1||a.member()==null||a.member()<1||a.pid()==null||a.pid().isBlank()
                ||!Objects.equals(a.tenant(),context.getTenantId())||!Objects.equals(a.user(),context.getUserId())
                ||MetaContext.getAuthorizedCommandCode()!=null||excluded())throw denied();
        if(!(resource()instanceof ConnectionHolder holder)||!holder.isSynchronizedWithTransaction())throw denied();
        connection(holder.getConnection());
        var scopes=recordScopes.forCommand(context);if(scopes==null)throw denied();
        var value=new State(context,handler,source,a,holder,scopes);
        TransactionSynchronizationManager.registerSynchronization(value);
        try{value.original=value.resolve();if(value.original!=null)value.initial=value.check();}
        catch(RuntimeException failure){holder.setRollbackOnly();throw failure;}
    }

    /** Only a command that passed the pre-replay gate may receive its original no-argument capability. */
    public Scope forCommand(CommandPipelineContext context){
        if(!(primary(context)instanceof CommandSubjectAcknowledgementSource))return null;
        var value=state();if(value==null||value.context!=context)throw denied();
        value.transaction();if(value.original==null)return null;
        if(value.issued||!value.frame())throw denied();value.issued=true;
        return new Scope(value);
    }
    public final class Scope implements AutoCloseable {
        private final State value;private boolean closed;
        private Scope(State value){this.value=value;}
        public AnnouncementSubjectAcknowledgementAccessor accessor(){return ()->{
            try{if(closed||!value.frame())throw denied();value.transaction();var proof=value.check();same(value.initial,proof);return proof;}
            catch(RuntimeException failure){if(Thread.currentThread()==value.thread)value.holder.setRollbackOnly();throw failure;}
        };}
        @Override public void close(){closed=true;}
    }
    private final class State implements TransactionSynchronization {
        final AnnouncementSubjectAcknowledgementAccessorFactory owner=AnnouncementSubjectAcknowledgementAccessorFactory.this;
        final CommandPipelineContext context;final CommandHandlerExtension handler;final CommandSubjectAcknowledgementSource resolver;
        final Actor actor;final ConnectionHolder holder;final Connection connection;final Thread thread=Thread.currentThread();
        final String command,model,target,handlerCode;final RecordActionScopeAccessor scopes;final TenantProjectionAccessor projection;
        SubjectSource original;SubjectChecks initial;boolean issued,completed,resolving;
        State(CommandPipelineContext context,CommandHandlerExtension handler,CommandSubjectAcknowledgementSource resolver,Actor actor,ConnectionHolder holder,RecordActionScopeAccessor scopes){
            this.context=context;this.handler=handler;this.resolver=resolver;this.actor=actor;this.holder=holder;this.connection=holder.getConnection();this.scopes=scopes;
            command=context.getCommandCode();model=context.getCommand().getModelCode();target=context.getRequest().getTargetRecordId();handlerCode=handlerCode(context);
            projection=new TenantProjectionAccessor(){
                private void live(){transaction();if(!resolving)throw denied();}
                public Map<String,Object> getById(String m,String p){live();return row(records.getById(actor.tenant(),m,p),p);}
                public List<Map<String,Object>> query(String m,Map<String,Object> filters){live();return rows(records.query(actor.tenant(),m,filters));}
                public DataPage queryPage(String m,DataPageQuery q){live();var page=records.queryPage(actor.tenant(),m,q);return new DataPage(rows(page.records()),page.total(),page.page(),page.size());}
                private List<Map<String,Object>> rows(List<Map<String,Object>> values){return values.stream().map(r->row(r,null)).toList();}
                private Map<String,Object> row(Map<String,Object> r,String pid){if(r==null)return null;if(!(r.get("tenant_id")instanceof Number n)||n.longValue()!=actor.tenant()||pid!=null&&!pid.equals(r.get("pid"))||Boolean.TRUE.equals(r.get("deleted_flag")))throw denied();return Collections.unmodifiableMap(new LinkedHashMap<>(r));}
            };
        }
        void transaction(){
            if(completed||Thread.currentThread()!=thread||!actor.equals(actor())||!writable()||excluded()||resource()!=holder||holder.getConnection()!=connection
                    ||!TransactionSynchronizationManager.getSynchronizations().contains(this)||!command.equals(context.getCommandCode())
                    ||context.getCommand()==null||!command.equals(context.getCommand().getCode())||!model.equals(context.getCommand().getModelCode())
                    ||!Objects.equals(target,context.getRequest().getTargetRecordId())||!handlerCode.equals(handlerCode(context))||primary(context)!=handler)throw denied();
            connection(connection);
        }
        boolean frame(){return MetaContext.isRootAuthorizedCommand()&&command.equals(MetaContext.getAuthorizedCommandCode())&&Objects.equals(target,MetaContext.getCommandPermitTargetFor(model))&&!excluded();}
        SubjectSource resolve(){
            transaction();if(resolving)throw denied();
            SubjectSource result;resolving=true;
            try{result=resolver.resolveSubjectAcknowledgementSource(new SubjectSourceContext(actor.tenant(),actor.pid(),actor.member(),command,model,target,projection,
                    (m,p,a)->{transaction();if(!resolving)throw denied();return scopes.permits(m,p,a);}));}
            finally{resolving=false;}
            transaction();
            if(result!=null&&(target==null||target.isBlank()||!Boolean.TRUE.equals(context.getTargetRecordReadable())||!scopes.permits(model,target,"read")
                    ||!command.equals(result.commandCode())||!model.equals(result.modelCode())||!target.equals(result.targetRecordPid())))throw denied();
            return result;
        }
        SubjectChecks check(){
            transaction();if(original==null||!original.equals(resolve())||!identities.supportsStrictApprovalIdentity())throw denied();
            var assertions=original.participants();var seenUsers=new HashSet<String>();var seenMembers=new HashSet<String>();
            for(var item:assertions)if(!seenUsers.add(item.userPid())||!seenMembers.add(item.memberPid()))throw denied();
            var operator=identities.findEnabledTenantMembership(actor.tenant(),actor.pid());
            if(operator==null||operator.user()==null||!Objects.equals(operator.user().id(),actor.user())||!Objects.equals(operator.memberId(),actor.member())
                    ||!assertions.contains(new CommandMembershipLockAccessor.MembershipAssertion(actor.pid(),operator.memberPid()))
                    ||!assertions.contains(new CommandMembershipLockAccessor.MembershipAssertion(original.subjectUserPid(),original.subjectMemberPid())))throw denied();
            var held=memberships.requireEnabledMemberships(assertions);if(held==null||held.size()!=assertions.size())throw denied();
            for(int i=0;i<held.size();i++){var identity=held.get(i);var asserted=assertions.get(i);if(identity==null||identity.user()==null||identity.user().id()==null||identity.memberId()==null||!asserted.userPid().equals(identity.user().pid())||!asserted.memberPid().equals(identity.memberPid()))throw denied();}
            var subject=identities.findEnabledTenantMembership(actor.tenant(),original.subjectUserPid());
            if(subject==null||subject.user()==null||subject.user().id()==null||!original.subjectUserPid().equals(subject.user().pid())||!original.subjectMemberPid().equals(subject.memberPid())||!held.contains(subject))throw denied();
            var required=new ArrayList<Requirement>();var indexes=new LinkedHashMap<String,Integer>();var versions=new HashMap<String,Integer>();
            for(var purpose:original.requirements()){
                var requirement=new Requirement(purpose.versionPid(),purpose.snapshotHash(),original.subjectMemberPid());
                Integer index=versions.get(purpose.versionPid());if(index==null){index=required.size();versions.put(purpose.versionPid(),index);required.add(requirement);}
                else if(!required.get(index).equals(requirement))throw denied();
                if(indexes.put(purpose.purpose(),index)!=null)throw denied();
            }
            var checks=acknowledgements.checkSubject(actor.tenant(),subject.user().id(),subject.user().pid(),subject.memberPid(),required);
            if(checks==null||checks.size()!=required.size())throw denied();
            for(int i=0;i<checks.size();i++){var check=checks.get(i);var expected=required.get(i);if(check==null||check.status()!=Status.SATISFIED||!expected.versionPid().equals(check.requestedVersionPid())||!expected.snapshotHash().equals(check.snapshotHash()))throw denied();}
            return new SubjectChecks(original,checks,indexes);
        }
        @Override public void beforeCommit(boolean readOnly){
            try{transaction();if(readOnly)throw denied();if(original!=null)same(initial,check());else if(resolve()!=null)throw denied();}
            catch(RuntimeException failure){holder.setRollbackOnly();throw failure;}
        }
        @Override public void afterCompletion(int status){completed=true;}
    }
    private static void same(SubjectChecks before,SubjectChecks after){
        if(!before.source().equals(after.source())||!before.purposeIndexes().equals(after.purposeIndexes())||before.checks().size()!=after.checks().size())throw denied();
        for(int i=0;i<before.checks().size();i++){
            var a=before.checks().get(i);var b=after.checks().get(i);
            if(!Objects.equals(a.requestedVersionPid(),b.requestedVersionPid())||!Objects.equals(a.activeVersionPid(),b.activeVersionPid())||!Objects.equals(a.snapshotHash(),b.snapshotHash())
                    ||a.status()!=b.status()||a.effective()!=b.effective()||a.visible()!=b.visible()||a.memberIncarnationMatch()!=b.memberIncarnationMatch()||a.acknowledgementRequired()!=b.acknowledgementRequired()
                    ||!Objects.equals(a.acknowledgedAt(),b.acknowledgedAt())||!Objects.equals(a.acknowledgedHash(),b.acknowledgedHash())||!Objects.equals(a.validUntil(),b.validUntil()))throw denied();
        }
    }
    private static boolean excluded(){return MetaContext.isImpersonating()||AsyncTaskExecutionContext.isActive()||com.auraboot.framework.application.security.MachineDataAccessContext.isActive()||com.auraboot.framework.openplatform.service.InstalledIntegrationExecution.active();}
    private static AccessDeniedException denied(){return new AccessDeniedException("Original command subject acknowledgement is unavailable");}
}

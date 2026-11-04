package com.auraboot.framework.plugin.pf4j;

import com.auraboot.framework.application.security.MachineDataAccessContext;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.plugin.extension.CommandRecordReadLockAccessor;
import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reuses current record reads and tenant-bound row locks without granting a record write. */
@Service
public class CommandRecordReadLockAccessorImpl implements CommandRecordReadLockAccessor {
    private final MetaModelService models;
    private final DynamicDataService data;
    private final DynamicDataMapper mapper;
    private final SqlSessionFactory sessions;

    public CommandRecordReadLockAccessorImpl(MetaModelService models, @Lazy DynamicDataService data,
            DynamicDataMapper mapper, SqlSessionFactory sessions) {
        this.models=models;this.data=data;this.mapper=mapper;this.sessions=sessions;
    }

    @Override
    @Transactional(propagation=Propagation.MANDATORY)
    public void requireReadableRecords(List<RecordRef> requested) {
        Scope scope=TransactionSynchronizationManager.getSynchronizations().stream()
                .filter(s->s instanceof Scope p && p.owner==this).map(s->(Scope)s).findFirst().orElse(null);
        boolean entered=false;
        try {
            Connection connection=coreConnection();Actor actor=actor();
            if(requested==null) throw new IllegalArgumentException("Readable roots are required");
            var roots=new java.util.ArrayList<>(requested);
            if(roots.isEmpty() || roots.size()>32 || roots.stream().anyMatch(java.util.Objects::isNull)
                    || new HashSet<>(roots).size()!=roots.size())
                throw new IllegalArgumentException("Declare one to thirty-two distinct readable roots");
            if(scope==null) {
                scope=new Scope(connection,actor,java.util.Set.copyOf(roots));
                TransactionSynchronizationManager.registerSynchronization(scope);
            } else if(scope.failed || scope.inCall || !scope.complete || scope.connection!=connection
                    || !scope.actor.equals(actor) || !scope.records.containsAll(roots)) {
                throw new IllegalStateException("The original actor, physical resource and complete lock set are required");
            }
            scope.inCall=true;entered=true;
            var targets=new LinkedHashMap<RecordRef,String>();
            for(var ref:roots) targets.put(ref,table(ref));
            if(scope.failed || (scope.complete && !scope.targets.entrySet().containsAll(targets.entrySet())))
                throw new IllegalStateException("The declared physical lock targets changed");
            if(!scope.complete) scope.targets=Map.copyOf(targets);
            var records=roots.stream().sorted(Comparator.comparing((RecordRef ref)->targets.get(ref))
                    .thenComparing(RecordRef::recordPid).thenComparing(RecordRef::modelCode)).toList();
            for(var ref:records) {
                readable(ref,actor);
                if(scope.failed) throw new IllegalStateException("The readable-root lock scope failed during authorization");
                var physical=new PhysicalTarget(targets.get(ref),ref.recordPid());
                if(!scope.locked.contains(physical)) {
                    var rows=mapper.selectTargetVersionForUpdate(targets.get(ref),"pid",actor.tenant(),ref.recordPid());
                    if(rows==null || rows.size()!=1 || !(rows.getFirst().get("row_version") instanceof Number version)
                            || version.longValue()<1) throw denied();
                    scope.locked.add(physical);
                }
                if(!targets.get(ref).equals(table(ref))) throw new IllegalStateException("The record model changed during locking");
                readable(ref,actor);
                if(!targets.get(ref).equals(table(ref))) throw new IllegalStateException("The record model changed during authorization");
                if(scope.failed) throw new IllegalStateException("The readable-root lock scope failed during authorization");
                if(!scope.actor.equals(actor())) throw denied();
            }
            scope.complete=true;
        } catch(RuntimeException | Error failure) {
            if(scope!=null) scope.failed=true;
            throw failure;
        } finally {
            if(entered && scope!=null) scope.inCall=false;
        }
    }

    private String table(RecordRef ref) {
        var model=models.getModelDefinition(ref.modelCode()).orElseThrow(CommandRecordReadLockAccessorImpl::denied);
        String table=model.getTableName();
        if(!ref.modelCode().equals(model.getCode()) || "view".equals(model.getModelType())
                || (model.getSourceType()!=null && !"physical".equals(model.getSourceType()))
                || (model.getPrimaryKey()!=null && !"pid".equals(model.getPrimaryKey()))
                || table==null || !table.matches("mt_[A-Za-z0-9_]+"))
            throw new IllegalArgumentException("Readable row locks require a default-PID dynamic physical model");
        return table;
    }
    private void readable(RecordRef ref,Actor actor) {
        if(!actor.equals(actor())) throw denied();
        var row=MetaContext.runWithoutCommandPermit(()->data.getById(ref.modelCode(),ref.recordPid()));
        if(!actor.equals(actor())) throw denied();
        if(row==null || !ref.recordPid().equals(row.get("pid"))
                || (row.get("tenant_id")!=null && !actor.tenant().toString().equals(row.get("tenant_id").toString())))
            throw denied();
    }
    private record Actor(Long tenant,Long user,String userPid,Long member) { }
    private record PhysicalTarget(String table,String pid) { }
    private static Actor actor() {
        if(!MetaContext.exists() || MachineDataAccessContext.isActive()) throw denied();
        var value=new Actor(MetaContext.getCurrentTenantId(),MetaContext.getCurrentUserId(),
                MetaContext.getCurrentUserPid(),MetaContext.getCurrentMemberId());
        if(value.tenant()==null || value.tenant()<1 || value.user()==null
                || value.user()<1 || value.userPid()==null || value.userPid().isBlank() || value.member()==null || value.member()<1)
            throw denied();
        return value;
    }
    private Connection coreConnection() {
        if(!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                || !TransactionSynchronizationManager.isSynchronizationActive())
            throw new IllegalStateException("Readable locks require an existing writable Core transaction");
        var source=sessions.getConfiguration().getEnvironment().getDataSource();
        if(!(TransactionSynchronizationManager.getResource(source) instanceof ConnectionHolder holder)
                || !holder.isSynchronizedWithTransaction())
            throw new IllegalStateException("The actual Core datasource transaction is required");
        Connection connection=holder.getConnection();
        try {
            if(connection.getAutoCommit() || connection.isReadOnly() || connection.isClosed())
                throw new IllegalStateException("The physical Core transaction is not writable");
        } catch(SQLException error) { throw new IllegalStateException("Cannot verify the Core transaction",error); }
        return connection;
    }
    private final class Scope implements TransactionSynchronization {
        final CommandRecordReadLockAccessorImpl owner=CommandRecordReadLockAccessorImpl.this;
        final Connection connection;final Actor actor;final java.util.Set<RecordRef> records;
        Map<RecordRef,String> targets=Map.of();
        final java.util.Set<PhysicalTarget> locked=new HashSet<>();boolean failed,inCall,complete;
        Scope(Connection connection,Actor actor,java.util.Set<RecordRef> records) {
            this.connection=connection;this.actor=actor;this.records=records;
        }
        @Override public void beforeCommit(boolean readOnly) {
            if(failed || inCall || !complete || readOnly || coreConnection()!=connection)
                throw new IllegalStateException("The readable-root lock scope cannot commit");
        }
    }
    private static AccessDeniedException denied() { return new AccessDeniedException("Readable root is unavailable"); }
}

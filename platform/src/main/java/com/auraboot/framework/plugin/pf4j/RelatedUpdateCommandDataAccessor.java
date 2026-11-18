package com.auraboot.framework.plugin.pf4j;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.plugin.extension.CommandHandlerExtension;
import com.auraboot.framework.plugin.extension.RecordReadAccessor;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.List;
import java.util.Comparator;
import java.util.HashSet;
import com.auraboot.framework.plugin.extension.CompareAndSetUpdate;
import org.springframework.security.access.AccessDeniedException;

/** Explicitly authorized related updates retain native persistence and exact optimistic versions. */
public final class RelatedUpdateCommandDataAccessor extends DynamicDataAccessorImpl {
    public static final String OPT_IN = "relatedUpdateAuthorization";
    public static final String CREATE_OPT_IN = "relatedCreateAuthorization";
    private final Long tenant;
    private final String command, rootModel, rootPid, actor;
    private final CommandHandlerExtension handler;
    private final RecordReadAccessor reads;
    private final boolean relatedDeletes;
    private final boolean relatedCreates;

    public RelatedUpdateCommandDataAccessor(DynamicDataService data, Long tenant, String command,
            String rootModel, String rootPid, String actor, CommandHandlerExtension handler, RecordReadAccessor reads) {
        this(data,tenant,command,rootModel,rootPid,actor,handler,reads,false);
    }
    public RelatedUpdateCommandDataAccessor(DynamicDataService data, Long tenant, String command,
            String rootModel, String rootPid, String actor, CommandHandlerExtension handler,
            RecordReadAccessor reads, boolean relatedDeletes) {
        this(data,tenant,command,rootModel,rootPid,actor,handler,reads,relatedDeletes,false);
    }
    public RelatedUpdateCommandDataAccessor(DynamicDataService data, Long tenant, String command,
            String rootModel, String rootPid, String actor, CommandHandlerExtension handler,
            RecordReadAccessor reads, boolean relatedDeletes, boolean relatedCreates) {
        super(data);
        this.tenant=Objects.requireNonNull(tenant);
        this.command=Objects.requireNonNull(command);
        this.rootModel=Objects.requireNonNull(rootModel);
        this.rootPid=Objects.requireNonNull(rootPid);
        this.actor=Objects.requireNonNull(actor);
        this.handler=Objects.requireNonNull(handler);
        this.reads=Objects.requireNonNull(reads);
        this.relatedDeletes=relatedDeletes;
        this.relatedCreates=relatedCreates;
    }

    @Override public Map<String,Object> create(String model,Map<String,Object> input) {
        if(!relatedCreates || rootModel.equals(model))return super.create(model,input);
        var root=authorizedRoot();
        if(!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Related create requires the original transaction");
        var intended=Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(input)));
        var context=new CommandHandlerExtension.TargetAuthorizationContext(command,rootModel,rootPid,"update",tenant,actor,root,reads);
        if(!handler.authorizeRelatedCreate(new CommandHandlerExtension.RelatedCreateAuthorizationContext(context,model,intended)))
            throw new AccessDeniedException("Related create is not authorized");
        return MetaContext.runWithCommandPermitPlan("ALL",null,model,null,()->
                RelatedMutationFieldScope.within(model,"create",null,intended,java.util.Set.of(),()->super.create(model,new LinkedHashMap<>(intended))));
    }
    @Override public java.util.Optional<Map<String,Object>> tryCreate(String model,Map<String,Object> input) {
        if(!relatedCreates || rootModel.equals(model))return super.tryCreate(model,input);
        try{return java.util.Optional.of(create(model,input));}
        catch(RuntimeException error){if(!IdempotentCreateSupport.isUniqueViolation(error))throw error;return java.util.Optional.empty();}
    }

    @Override public Map<String,Object> update(String model, String pid, Map<String,Object> changes) {
        if(rootModel.equals(model) && rootPid.equals(pid)) return super.update(model,pid,changes);
        var root=authorizedRoot();
        var record=load(model,pid);
        var intended=Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(changes)));
        long actual=version(record.get("row_version"));
        long expected=version(intended.get("_expectedVersion"));
        if(actual!=expected) throw new com.auraboot.framework.meta.exception.RecordVersionConflictException(
                "Related record changed concurrently");
        var rootContext=new CommandHandlerExtension.TargetAuthorizationContext(command,rootModel,
                rootPid,"update",tenant,actor,root,reads);
        if(!handler.authorizeRelatedUpdate(new CommandHandlerExtension.RelatedUpdateAuthorizationContext(
                rootContext,model,pid,record,intended)))
            throw new AccessDeniedException("Related record update is not authorized");
        return MetaContext.runWithCommandPermitPlan("TARGET",expected,model,pid,
                ()->RelatedMutationFieldScope.within(model,"update",pid,intended,java.util.Set.of(),()->super.update(model,pid,new LinkedHashMap<>(intended))));
    }

    @Override public void delete(String model,String pid) {
        if(!relatedDeletes || (rootModel.equals(model) && rootPid.equals(pid))) {
            super.delete(model,pid);return;
        }
        var root=authorizedRoot();var record=load(model,pid);
        var context=new CommandHandlerExtension.TargetAuthorizationContext(command,rootModel,
                rootPid,"update",tenant,actor,root,reads);
        if(!handler.authorizeRelatedDelete(new CommandHandlerExtension.RelatedDeleteAuthorizationContext(
                context,model,pid,record)))throw new AccessDeniedException("Related record delete is not authorized");
        long expected=version(record.get("row_version"));
        MetaContext.runWithCommandPermitPlan("TARGET",expected,model,pid,()->super.delete(model,pid));
    }

    /** Related CAS retains native predicates and ordered locks inside the original transaction. */
    @Override public void compareAndSetBatch(String model,String field,List<CompareAndSetUpdate> updates) {
        if(rootModel.equals(model) && updates!=null && updates.stream().allMatch(x->x!=null && rootPid.equals(x.recordId()))) {
            super.compareAndSetBatch(model,field,updates);return;
        }
        var root=authorizedRoot();
        if(!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Related CAS requires the original transaction");
        if(updates==null || updates.isEmpty() || field==null || field.isBlank())
            throw new IllegalArgumentException("Related CAS requires records and a compare field");
        var versions=new LinkedHashMap<String,Long>();var ids=new HashSet<String>();
        for(var update:updates) {
            if(update==null || !ids.add(update.recordId()) || update.nextValues().containsKey("_expectedVersion"))
                throw new IllegalArgumentException("Related CAS contains invalid records");
            var row=load(model,update.recordId());long expected=version(row.get("row_version"));
            var changes=new LinkedHashMap<>(update.nextValues());changes.put("_expectedVersion",expected);
            var context=new CommandHandlerExtension.TargetAuthorizationContext(command,rootModel,rootPid,"update",tenant,actor,root,reads);
            if(!handler.authorizeRelatedUpdate(new CommandHandlerExtension.RelatedUpdateAuthorizationContext(
                    context,model,update.recordId(),row,Collections.unmodifiableMap(changes))))
                throw new AccessDeniedException("Related CAS is not authorized");
            versions.put(update.recordId(),expected);
        }
        // The enclosing command transaction makes the native single-row operations atomic as a batch.
        for(var update:updates.stream().sorted(Comparator.comparing(CompareAndSetUpdate::recordId)).toList())
            MetaContext.runWithCommandPermitPlan("TARGET",versions.get(update.recordId()),model,update.recordId(),
                    ()->RelatedMutationFieldScope.within(model,"update",update.recordId(),update.nextValues(),java.util.Set.of(field),
                            ()->{super.compareAndSetBatch(model,field,List.of(update));return null;}));
    }

    private Map<String,Object> authorizedRoot() {
        if(!tenant.equals(MetaContext.getCurrentTenantId()) || !actor.equals(MetaContext.getCurrentUserPid())
                || !command.equals(MetaContext.getAuthorizedCommandCode())
                || !MetaContext.hasCommandPermitScopeFor(rootModel))
            throw new AccessDeniedException("Related update requires the original authorized command");
        String boundTarget=MetaContext.getCommandPermitTargetFor(rootModel);
        if(boundTarget!=null && !rootPid.equals(boundTarget))
            throw new AccessDeniedException("Related update root changed");
        var root=load(rootModel,rootPid);
        if(!com.auraboot.framework.meta.service.impl.CommandPermitDataAccess.permitsRecord(
                rootModel,root,MetaContext.getCurrentUserId()))
            throw new AccessDeniedException("Related update source is outside the original record scope");
        return root;
    }

    private static long version(Object value) {
        if(!(value instanceof Number number)) throw new IllegalStateException("Related update version is required");
        long result=new BigDecimal(number.toString()).longValueExact();
        if(result<0) throw new IllegalStateException("Related update version is required");
        return result;
    }

    private Map<String,Object> load(String model,String pid) {
        var row=reads.getById(model,pid);
        if(row==null || !Objects.equals(pid,row.get("pid"))
                || !tenant.toString().equals(String.valueOf(row.get("tenant_id"))))
            throw new AccessDeniedException("Related update target is unavailable in this tenant");
        return Collections.unmodifiableMap(new LinkedHashMap<>(row));
    }
}

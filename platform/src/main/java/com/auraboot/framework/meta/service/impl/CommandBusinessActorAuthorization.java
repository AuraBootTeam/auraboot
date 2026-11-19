package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPipelineContext;
import com.auraboot.framework.plugin.extension.*;
import com.auraboot.framework.plugin.pf4j.DynamicDataAccessorImpl;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.springframework.security.access.AccessDeniedException;

/** Short-lived, bounded identity reads for an immutable business-owner gate. No caller-supplied authority. */
public final class CommandBusinessActorAuthorization {
    private static final Set<String> IDENTITIES = Set.of("pid", "tenant_id", "row_version");
    private CommandBusinessActorAuthorization() {}

    public static void require(CommandPipelineContext context, BusinessResourceAuthorization owner,
                               DynamicDataService data, Map<String,Object> root) {
        var policy=owner.businessActorAuthorization();
        if(policy==null)return;
        CommandResultProjectionService.requireActor(context);
        var target=context.getBusinessTargetAuthorization();
        String actor=MetaContext.getCurrentUserPid();
        if(MetaContext.isImpersonating() || actor==null || actor.isBlank() || root==null
                || !target.targetRecordId().equals(root.get("pid"))
                || !target.tenantId().toString().equals(Objects.toString(root.get("tenant_id"),""))
                || Boolean.TRUE.equals(root.get("deleted_flag")))throw denied();
        var configured=policy.sources();
        if(configured==null || configured.size()>16)throw denied();
        var sources=new LinkedHashMap<String,Set<String>>();
        configured.forEach((model,fields)->{
            BusinessResourceAuthorization.identifier(model);
            if(fields==null || fields.size()>125)throw denied();
            var readable=new HashSet<>(fields);readable.forEach(BusinessResourceAuthorization::identifier);
            readable.addAll(IDENTITIES);sources.put(model,Set.copyOf(readable));
        });
        var active=new AtomicBoolean(true);var invalid=new AtomicBoolean(false);var remaining=new AtomicInteger(100);long thread=Thread.currentThread().threadId();
        var delegate=new DynamicDataAccessorImpl(Objects.requireNonNull(data));
        var reads=new RecordReadAccessor() {
            private AccessDeniedException reject(){invalid.set(true);return denied();}
            private Set<String> admit(String model,int rows) {
                if(!active.get() || Thread.currentThread().threadId()!=thread || !MetaContext.exists()
                        || !actor.equals(MetaContext.getCurrentUserPid()) || !target.tenantId().equals(MetaContext.getCurrentTenantId())
                        || !target.userId().equals(MetaContext.getCurrentUserId()) || !target.memberId().equals(MetaContext.getCurrentMemberId())
                        || !sources.containsKey(model) || rows<1 || remaining.addAndGet(-rows)<0)throw reject();
                CommandResultProjectionService.requireActor(context);return sources.get(model);
            }
            private Map<String,Object> project(Map<String,Object> row,Set<String> fields) {
                if(row==null)return null;
                if(!target.tenantId().toString().equals(Objects.toString(row.get("tenant_id"),""))
                        || !(row.get("pid") instanceof String pid) || pid.isBlank() || Boolean.TRUE.equals(row.get("deleted_flag"))
                        || !validVersion(row.get("row_version")))throw reject();
                var copy=new LinkedHashMap<String,Object>();for(String field:fields)if(row.containsKey(field))copy.put(field,row.get(field));
                return Collections.unmodifiableMap(copy);
            }
            @Override public Map<String,Object> getById(String model,String id) {
                var fields=admit(model,1);if(id==null || id.isBlank() || id.length()>128)throw reject();
                Map<String,Object> row;
                try {row=MetaContext.runWithCommandPermitScope("ALL",()->delegate.getByIdForProcessing(model,id));}
                // CATCH: revoke a failed lease even if owner code catches the unchanged lookup error.
                catch(RuntimeException failure){invalid.set(true);throw failure;}
                if(row==null || !id.equals(row.get("pid")))throw reject();return project(row,fields);
            }
            @Override public DataPage queryPage(String model,DataPageQuery query) {
                if(query==null || query.page()!=1 || query.size()>100 || !query.keyword().isEmpty())throw reject();
                var fields=admit(model,query.size());
                if(!fields.containsAll(query.exactFilters().keySet()) || !fields.containsAll(query.anyOfFilters().keySet())
                        || query.sorts().stream().anyMatch(sort->!fields.contains(sort.field())))throw reject();
                DataPage page;
                try {page=MetaContext.runWithCommandPermitScope("ALL",()->delegate.queryPage(model,query));}
                // CATCH: revoke a failed lease even if owner code catches the unchanged lookup error.
                catch(RuntimeException failure){invalid.set(true);throw failure;}
                return new DataPage(page.records().stream().map(row->project(row,fields)).toList(),page.total(),page.page(),page.size());
            }
        };
        try {
            var permissions=context.getBusinessGrantDecision().grants().stream().map(grant->grant.permissionCode())
                    .filter(Objects::nonNull).collect(Collectors.toUnmodifiableSet());
            var targetContext=new CommandHandlerExtension.TargetAuthorizationContext(target.commandCode(),target.modelCode(),target.targetRecordId(),
                    target.action(),target.tenantId(),actor,Collections.unmodifiableMap(new LinkedHashMap<>(root)),reads);
            boolean allowed=policy.authorize(new BusinessActorAuthorization.Context(targetContext,permissions));
            CommandResultProjectionService.requireActor(context);
            if(!allowed || invalid.get() || !actor.equals(MetaContext.getCurrentUserPid()))throw denied();
        } finally {active.set(false);}
    }
    private static boolean validVersion(Object raw) {
        if(!(raw instanceof Number))return false;
        try {var value=new java.math.BigDecimal(raw.toString());return value.signum()>=0 && value.stripTrailingZeros().scale()<=0
                && value.compareTo(java.math.BigDecimal.valueOf(Long.MAX_VALUE))<=0;}
        catch(NumberFormatException malformed){return false;}
    }
    private static AccessDeniedException denied(){return new AccessDeniedException("Current business actor authorization denied");}
}

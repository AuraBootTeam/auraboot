package com.auraboot.framework.plugin.pf4j;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.plugin.extension.CommandHandlerExtension;
import com.auraboot.framework.plugin.extension.RecordReadAccessor;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.security.access.AccessDeniedException;

/** Opt-in exact related-record deletes retain native persistence and optimistic guards. */
public final class RelatedCommandDataAccessor extends DynamicDataAccessorImpl {
    public static final String OPT_IN = "relatedDeleteAuthorization";
    private final Long tenant;
    private final String command;
    private final String rootModel;
    private final String rootPid;
    private final String actor;
    private final CommandHandlerExtension handler;
    private final RecordReadAccessor reads;

    public RelatedCommandDataAccessor(DynamicDataService data, Long tenant, String command,
            String rootModel, String rootPid, String actor, CommandHandlerExtension handler,
            RecordReadAccessor reads) {
        super(data);
        this.tenant=Objects.requireNonNull(tenant);
        this.command=Objects.requireNonNull(command);
        this.rootModel=Objects.requireNonNull(rootModel);
        this.rootPid=Objects.requireNonNull(rootPid);
        this.actor=Objects.requireNonNull(actor);
        this.handler=Objects.requireNonNull(handler);
        this.reads=Objects.requireNonNull(reads);
    }

    @Override public void delete(String model, String pid) {
        if(rootModel.equals(model)) { super.delete(model,pid);return; }
        if(!tenant.equals(MetaContext.getCurrentTenantId())
                || !actor.equals(MetaContext.getCurrentUserPid())
                || !command.equals(MetaContext.getAuthorizedCommandCode())
                || !"TARGET".equals(MetaContext.getCommandPermitScopeFor(rootModel))
                || !rootPid.equals(MetaContext.getCommandPermitTargetFor(rootModel)))
            throw new AccessDeniedException("Related delete requires the authorized root command");
        var root=load(rootModel,rootPid);
        var record=load(model,pid);
        var rootContext=new CommandHandlerExtension.TargetAuthorizationContext(command,rootModel,
                rootPid,"update",tenant,actor,root,reads);
        if(!handler.authorizeRelatedDelete(new CommandHandlerExtension.RelatedDeleteAuthorizationContext(
                rootContext,model,pid,record)))
            throw new AccessDeniedException("Related record delete is not authorized");
        Object version=record.get("row_version");
        if(!(version instanceof Number number))
            throw new IllegalStateException("Related record version is required");
        long expected=new java.math.BigDecimal(number.toString()).longValueExact();
        if(expected<0) throw new IllegalStateException("Related record version is required");
        MetaContext.runWithCommandPermitPlan("TARGET",expected,model,pid,
                ()->super.delete(model,pid));
    }

    private Map<String,Object> load(String model,String pid) {
        var record=reads.getById(model,pid);
        if(record==null || !Objects.equals(pid,record.get("pid"))
                || !tenant.toString().equals(String.valueOf(record.get("tenant_id"))))
            throw new AccessDeniedException("Related delete target is unavailable in this tenant");
        return Collections.unmodifiableMap(new LinkedHashMap<>(record));
    }
}

package com.auraboot.framework.plugin.pf4j;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPipelineContext;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** A synchronous invocation may initialize only its explicitly command-owned new models. */
public final class CommandCreatedDataAccessor extends DynamicDataAccessorImpl implements AutoCloseable {
    public static final String OPT_IN = "commandCreatedModels";
    private final CommandPipelineContext context;
    private final MetaModelService models;
    private final Set<String> allowed;
    private final CommandPipelineContext.HandlerInvocationAuthorization invocation;
    private final Long tenant, user, member;
    private final String actor;
    private final Thread thread = Thread.currentThread();
    private final Map<Object,Object> resources;
    private boolean active = true;

    private CommandCreatedDataAccessor(DynamicDataService data, MetaModelService models,
            CommandPipelineContext context, Set<String> allowed) {
        super(data);
        this.models=models; this.context=context; this.allowed=Set.copyOf(allowed);
        invocation=context.getHandlerInvocationAuthorization();
        tenant=MetaContext.getCurrentTenantId(); user=MetaContext.getCurrentUserId();
        member=MetaContext.getCurrentMemberId(); actor=MetaContext.getCurrentUserPid();
        resources=Map.copyOf(TransactionSynchronizationManager.getResourceMap());
    }

    public static CommandCreatedDataAccessor open(DynamicDataService data, MetaModelService models,
            CommandPipelineContext context, ObjectMapper json) {
        Object rawParams=context.getExecConfig().get("handlerParams");
        if (!(rawParams instanceof Map<?,?> params) || !params.containsKey(OPT_IN)) return null;
        Object raw=params.get(OPT_IN);
        if (!(raw instanceof List<?> list) || list.isEmpty() || list.size()>32
                || list.stream().anyMatch(v->!(v instanceof String s) || s.isBlank())
                || new LinkedHashSet<>(list).size()!=list.size()
                || !"handler-with-result-reads".equals(params.get("invocationAuthorization"))
                || !"read".equals(params.get("targetRecordAction"))
                || !Boolean.FALSE.equals(params.get("dslPersistence"))
                || params.containsKey("async") || params.containsKey(RelatedCommandDataAccessor.OPT_IN)
                || params.containsKey(RelatedUpdateCommandDataAccessor.OPT_IN)
                || context.getCommand()==null || context.getRequest()==null || context.getRequest().isDryRun()
                || context.getAuthorizationVerdict()==null || !context.getAuthorizationVerdict().isAuthorized()
                || context.getPermitPlan()==null || !context.getPermitPlan().isPermitted()
                || !Boolean.TRUE.equals(context.getTargetRecordReadable())
                || context.getHandlerInvocationAuthorization()==null || !humanTransaction()) throw denied();
        Set<String> allowed=new LinkedHashSet<>();
        for(Object item:list) allowed.add((String)item);
        try {
            List<String> targets=json.readValue(context.getCommand().getTargetModels(),new TypeReference<List<String>>(){});
            if (targets==null || !targets.containsAll(allowed)) throw denied();
        } catch (com.fasterxml.jackson.core.JsonProcessingException | IllegalArgumentException e) {
            throw new AccessDeniedException("Invalid command-created model declaration",e);
        }
        var accessor=new CommandCreatedDataAccessor(data,models,context,allowed);
        accessor.requireFrame();
        return accessor;
    }

    private static boolean humanTransaction() {
        return MetaContext.exists() && MetaContext.getCurrentTenantId()!=null
                && MetaContext.getCurrentUserId()!=null && MetaContext.getCurrentMemberId()!=null
                && MetaContext.getCurrentUserPid()!=null && !MetaContext.isImpersonating()
                && !com.auraboot.framework.application.security.MachineDataAccessContext.isActive()
                && !com.auraboot.framework.openplatform.service.InstalledIntegrationExecution.active()
                && !com.auraboot.framework.meta.service.impl.AsyncTaskExecutionContext.isActive()
                && TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()
                && !TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                && !TransactionSynchronizationManager.getResourceMap().isEmpty();
    }

    private void requireFrame() {
        if (!active || Thread.currentThread()!=thread || !humanTransaction()
                || !Objects.equals(tenant,MetaContext.getCurrentTenantId())
                || !Objects.equals(user,MetaContext.getCurrentUserId())
                || !Objects.equals(member,MetaContext.getCurrentMemberId())
                || !Objects.equals(actor,MetaContext.getCurrentUserPid())
                || context.getHandlerInvocationAuthorization()!=invocation
                || !Objects.equals(context.getCommandCode(),context.getCommand().getCode())
                || context.getRequest().getTargetRecordId()==null || context.getRequest().getTargetRecordId().isBlank()
                || !Objects.equals(invocation.commandCode(),context.getCommandCode())
                || !Objects.equals(invocation.modelCode(),context.getCommand().getModelCode())
                || !Objects.equals(invocation.targetRecordId(),context.getRequest().getTargetRecordId())
                || !"read".equals(invocation.action()) || !tenant.equals(context.getTenantId())
                || !user.equals(context.getUserId())
                || (MetaContext.getAuthorizedCommandCode()!=null
                    && !context.getCommandCode().equals(MetaContext.getAuthorizedCommandCode()))
                || resources.entrySet().stream().anyMatch(e->TransactionSynchronizationManager.getResource(e.getKey())!=e.getValue())) throw denied();
    }

    @Override public Map<String,Object> create(String model,Map<String,Object> input) {
        requireFrame();
        if (!allowed.contains(model) || model.equals(invocation.modelCode())) throw denied();
        var definition=models.getModelDefinition(model).orElseThrow(CommandCreatedDataAccessor::denied);
        if (!definition.isCommandOnlyCreate() || definition.getExtension()==null
                || !(definition.getExtension().get("commandWriters") instanceof Map<?,?> writers)
                || !(writers.get("create") instanceof List<?> codes)
                || !codes.contains(context.getCommandCode())) throw denied();
        // Only this native insert receives writer provenance. No handler-wide row/read authority.
        return MetaContext.runWithCommandCreateWriter(model,context.getCommandCode(),
                ()->{requireFrame(); return true;},()->{
                    var result=super.create(model,input); requireFrame(); return result;
                });
    }

    @Override public Optional<Map<String,Object>> tryCreate(String model,Map<String,Object> input) {
        try { return Optional.of(create(model,input)); }
        catch (RuntimeException error) {
            if (!IdempotentCreateSupport.isUniqueViolation(error)) throw error;
            return Optional.empty();
        }
    }
    @Override public void close() { active=false; }
    private static AccessDeniedException denied() { return new AccessDeniedException("Command-created model authority is unavailable"); }
}

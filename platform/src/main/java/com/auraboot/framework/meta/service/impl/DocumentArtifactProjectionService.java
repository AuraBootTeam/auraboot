package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.file.entity.FileEntity;
import com.auraboot.framework.file.security.BusinessArtifactAuthorization;
import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.entity.CommandDefinition;
import com.auraboot.framework.meta.mapper.CommandDefinitionMapper;
import com.auraboot.framework.meta.security.DocumentArtifactContract;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.meta.service.impl.pipeline.CommandAuthorizationVerdict;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPipelineContext;
import com.auraboot.framework.permission.service.BusinessCommandRecordAuthorization;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.util.*;

/** Document generation and later downloads consume the existing complete-grant/field evaluators. */
@Service
@RequiredArgsConstructor
public class DocumentArtifactProjectionService {
    private final CommandDefinitionMapper commands;
    private final DynamicDataService data;
    private final BusinessCommandRecordAuthorization authorization;
    private final CommandPublicOutputProjectionService projections;
    private final ObjectMapper json;

    public CommandPublicOutputProjectionPlan prepare(CommandPipelineContext context) {
        var contract=DocumentArtifactContract.from(context.getExecConfig(),context.getCommand().getModelCode());
        if(contract==null)throw DocumentArtifactContract.denied();
        return prepare(context.getCommand(),context.getExecConfig(),context.getRequest().getTargetRecordId(),contract);
    }

    public com.auraboot.framework.plugin.extension.DocumentOutputAccessor accessor(CommandPipelineContext context) {
        return new com.auraboot.framework.plugin.extension.DocumentOutputAccessor() {
            @Override public Map<String,Object> project(String model,Map<String,Object> source) {
                var plan=com.auraboot.framework.meta.security.DocumentArtifactCommandScope.outputPlan(context);
                return projections.projectDocument(plan,model,source);
            }
            @Override public boolean isUnrestricted(String model) {
                var fields=com.auraboot.framework.meta.security.DocumentArtifactCommandScope.outputPlan(context).models().get(model);
                if(fields==null)throw DocumentArtifactContract.denied();
                return fields.readable().equals(fields.declared()) && fields.policyMasks().isEmpty() && fields.detailMasks().isEmpty();
            }
        };
    }

    @Transactional(propagation=Propagation.MANDATORY,readOnly=true)
    public void authorize(FileEntity file, BusinessArtifactAuthorization stamp) {
        var document=stamp.document();
        if(document==null || !MetaContext.exists() || MetaContext.isImpersonating()
                || com.auraboot.framework.application.security.MachineDataAccessContext.isActive()
                || com.auraboot.framework.openplatform.service.InstalledIntegrationExecution.active()
                || MetaContext.getCurrentUserId()==null || MetaContext.getCurrentUserId()<=0
                || MetaContext.getCurrentMemberId()==null || MetaContext.getCurrentMemberId()<=0
                || !Objects.equals(stamp.tenantId(),MetaContext.getCurrentTenantId())
                || !Objects.equals(file.getTenantId(),stamp.tenantId()) || Boolean.TRUE.equals(file.getDeletedFlag())
                || !"success".equals(file.getStatus()) || !Boolean.TRUE.equals(file.getRetentionLocked())
                || !stamp.rootBinding().isEmpty())throw DocumentArtifactContract.denied();
        CommandDefinition command=commands.findCurrentByCode(stamp.commandCode());
        if(command==null || !Objects.equals(command.getTenantId(),stamp.tenantId()) || Boolean.TRUE.equals(command.getDeletedFlag())
                || !Boolean.TRUE.equals(command.getIsCurrent()) || !Objects.equals(command.getCode(),stamp.commandCode())
                || !"published".equalsIgnoreCase(command.getStatus()) || !Objects.equals(command.getModelCode(),stamp.rootModel()))throw DocumentArtifactContract.denied();
        Map<String,Object> config;
        try {config=json.readValue(command.getExecutionConfig(),new TypeReference<>(){});}
        catch(com.fasterxml.jackson.core.JsonProcessingException invalid){throw DocumentArtifactContract.denied();}
        var contract=DocumentArtifactContract.from(config,stamp.rootModel());
        if(contract==null || !contract.code().equals(document.code()) || !contract.version().equals(document.version())
                || !contract.fingerprint().equals(document.definitionHash()) || !contract.relation().equals(stamp.rootField())
                || !contract.outputs().contains(document.format()))throw DocumentArtifactContract.denied();
        if(!stamp.permits(prepare(command,config,stamp.rootPid(),contract)))throw DocumentArtifactContract.denied();
    }

    private CommandPublicOutputProjectionPlan prepare(CommandDefinition command, Map<String,Object> config,
            String pid, DocumentArtifactContract contract) {
        Long tenant=MetaContext.getCurrentTenantId(),user=MetaContext.getCurrentUserId(),member=MetaContext.getCurrentMemberId();
        if(tenant==null || user==null || member==null || MetaContext.isImpersonating())throw DocumentArtifactContract.denied();
        String model=command.getModelCode();
        // The exact canonical root is evidence for authorization, never an output or a generic read grant.
        var root=MetaContext.runWithCommandPermitPlan("TARGET",null,model,pid,()->data.getByIdForAuthorization(model,pid));
        if(root==null || !pid.equals(root.get("pid")) || !tenant.toString().equals(String.valueOf(root.get("tenant_id")))
                || Boolean.TRUE.equals(root.get("deleted_flag")))throw DocumentArtifactContract.denied();
        var grant=authorization.resolveOrdinaryRead(member,model,root);
        if(!grant.result().granted())throw DocumentArtifactContract.denied();
        var request=new CommandExecuteRequest();request.setTargetRecordId(pid);
        var context=CommandPipelineContext.builder().commandCode(command.getCode()).command(command).request(request)
                .tenantId(tenant).userId(user).execConfig(config).build();
        context.setAuthorizationVerdict(CommandAuthorizationVerdict.authorized(grant.grants().getFirst().permissionCode()));
        context.setBusinessGrantDecision(grant);
        context.setBusinessTargetAuthorization(new CommandPipelineContext.BusinessTargetAuthorization(command.getCode(),model,pid,"read",tenant,user,member));
        return projections.prepareDocument(context,contract.fields());
    }
}

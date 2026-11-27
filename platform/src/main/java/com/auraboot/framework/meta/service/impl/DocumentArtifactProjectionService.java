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
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.auraboot.framework.file.dao.mapper.FileMapper files;

    /** Validate fresh and replayed file references against host-owned provenance, never DTO claims. */
    public void verifyResults(CommandPipelineContext context, Map<String,Object> values) {
        CommandResultProjectionService.requireActor(context);
        var result=context.getBusinessResultProjection();
        var contract=DocumentArtifactContract.from(context.getExecConfig(),context.getCommand().getModelCode());
        if(result==null || contract==null || files==null)throw DocumentArtifactContract.denied();
        Map<String,Map<String,String>> formats=new LinkedHashMap<>();
        result.fields().forEach((key,field)-> {
            if(field.kind()==com.auraboot.framework.plugin.extension.BusinessResultField.Kind.DOCUMENT_ARTIFACT) {
                var properties=formats.computeIfAbsent(field.sourcePath().get(0),ignored->new LinkedHashMap<>());
                if(properties.putIfAbsent(field.sourcePath().get(1),key)!=null)throw DocumentArtifactContract.denied();
            }
        });
        var current=prepare(context);
        for(var entry:formats.entrySet()) {
            String format=entry.getKey();var properties=entry.getValue();
            if(!properties.keySet().equals(Set.of("fileId","filename","bytes","url"))
                    || !contract.outputs().contains(format))throw DocumentArtifactContract.denied();
            Object id=values.get(properties.get("fileId"));
            if(!(id instanceof String pid) || !pid.matches("[0-9A-HJKMNP-TV-Z]{26}"))throw DocumentArtifactContract.denied();
            var file=files.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<FileEntity>()
                    .eq(FileEntity::getTenantId,context.getTenantId()).eq(FileEntity::getPid,pid).eq(FileEntity::getDeletedFlag,false));
            if(file==null || !pid.equals(file.getPid()) || !context.getTenantId().equals(file.getTenantId())
                    || Boolean.TRUE.equals(file.getDeletedFlag()) || !"success".equals(file.getStatus())
                    || !Boolean.TRUE.equals(file.getRetentionLocked()) || file.getFileSize()==null
                    || file.getFileSize()<1 || file.getFileSize()>67_108_864 || file.getOriginalName()==null
                    || !file.getOriginalName().toLowerCase(Locale.ROOT).endsWith("."+format)
                    || !("xlsx".equals(format)?"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet":"application/pdf").equals(file.getMimeType()))
                throw DocumentArtifactContract.denied();
            var stamp=BusinessArtifactAuthorization.parse(file.getArtifactAuthorization());
            var descriptor=stamp.document();
            if(descriptor==null || !context.getTenantId().equals(stamp.tenantId())
                    || !context.getCommandCode().equals(stamp.commandCode())
                    || !context.getCommand().getModelCode().equals(stamp.rootModel())
                    || !context.getRequest().getTargetRecordId().equals(stamp.rootPid())
                    || !contract.relation().equals(stamp.rootField()) || !stamp.rootBinding().isEmpty()
                    || !contract.code().equals(descriptor.code()) || !contract.version().equals(descriptor.version())
                    || !contract.fingerprint().equals(descriptor.definitionHash()) || !format.equals(descriptor.format())
                    || !stamp.permits(current))throw DocumentArtifactContract.denied();
            if(!Objects.equals(file.getOriginalName(),values.get(properties.get("filename")))
                    || !Objects.equals("/api/file/download/"+pid,values.get(properties.get("url")))
                    || !(values.get(properties.get("bytes")) instanceof Number size)
                    || new java.math.BigDecimal(size.toString()).compareTo(java.math.BigDecimal.valueOf(file.getFileSize()))!=0)
                throw DocumentArtifactContract.denied();
        }
    }

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

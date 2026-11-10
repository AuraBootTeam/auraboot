package com.auraboot.framework.openplatform.service;

import com.auraboot.framework.application.security.ExternalMachineAuthenticator.MachinePrincipal;
import com.auraboot.framework.application.security.MachineDataAccessContext;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.framework.meta.mapper.MetaModelMapper;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.meta.service.impl.RequiredRecordReadGuard;
import com.auraboot.framework.plugin.extension.BackgroundDataAccessor;
import com.auraboot.framework.plugin.extension.DataPageQuery;
import com.auraboot.framework.plugin.mapper.PluginRecordMapper;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/** Read-only, installation-constrained lookup of a provider-owned managed record, never a payload resolver. */
@Component
final class InstalledIntegrationBindingLookup {
    record Selection(long revision,Map<String,Object> parameters) {
        Selection {parameters=Map.copyOf(parameters);}
    }
    private final MetaModelService models;
    private final MetaModelMapper metadata;
    private final PluginRecordMapper owners;
    private final BackgroundDataAccessor data;
    InstalledIntegrationBindingLookup(@Lazy MetaModelService models,MetaModelMapper metadata,PluginRecordMapper owners,
            @Lazy BackgroundDataAccessor data) {
        this.models=models;this.metadata=metadata;this.owners=owners;this.data=data;
    }
    Selection resolve(MachinePrincipal principal,String ownerPlugin,JsonNode spec) {
        if(principal==null || principal.tenantId()==null || principal.tenantId()<1
                || !Objects.equals(MetaContext.getCurrentTenantId(),principal.tenantId())
                || !Objects.equals(MetaContext.getCurrentUserId(),0L)
                || !("installation:"+principal.installationPid()).equals(MetaContext.getCurrentUserPid())
                || !pid(principal.applicationPid()) || !pid(principal.installationPid()) || ownerPlugin==null || ownerPlugin.isBlank())throw denied();
        var caller=MachineDataAccessContext.principal();
        if(caller!=null && (!Objects.equals(caller.tenantId(),principal.tenantId())
                || !Objects.equals(caller.applicationPid(),principal.applicationPid())
                || !Objects.equals(caller.installationPid(),principal.installationPid())
                || !Objects.equals(caller.subjectPid(),principal.subjectPid())))throw denied();
        if(spec==null || !spec.isObject() || !keys(spec).equals(Set.of("version","modelCode","applicationField","installationField",
                "revisionField","statusField","allowedStatuses","recordPidParameter","referenceReadFields"))
                || !spec.path("version").isIntegralNumber() || !spec.path("version").canConvertToInt() || spec.path("version").intValue()!=1)throw denied();
        String model=identifier(spec,"modelCode"),application=identifier(spec,"applicationField"),installation=identifier(spec,"installationField"),
                revision=identifier(spec,"revisionField"),status=identifier(spec,"statusField"),parameter=identifier(spec,"recordPidParameter");
        var names=List.of(application,installation,revision,status);
        if(new HashSet<>(names).size()!=4 || names.stream().anyMatch(Set.of("pid","id","tenant_id","row_version")::contains))throw denied();
        JsonNode statuses=spec.path("allowedStatuses");
        if(!statuses.isArray() || statuses.isEmpty() || statuses.size()>16)throw denied();
        var allowed=new java.util.ArrayList<Object>();
        for(var value:statuses) {
            if(!value.isTextual() || !value.textValue().matches("[a-z][a-z0-9-]{0,63}") || allowed.contains(value.textValue()))throw denied();
            allowed.add(value.textValue());
        }
        var definition=models.getModelDefinitionFromDb(model).orElseThrow(InstalledIntegrationBindingLookup::denied);
        owner(principal,ownerPlugin,model,definition);
        var fields=new LinkedHashMap<String,FieldDefinition>();
        if(definition.getFields()==null)throw denied();
        for(var field:definition.getFields())if(field==null || field.getCode()==null || fields.put(field.getCode(),field)!=null)throw denied();
        FieldDefinition app=field(fields,application,Set.of("string","reference"),true),inst=field(fields,installation,Set.of("string","reference"),true),
                rev=field(fields,revision,Set.of("integer","long"),true),state=field(fields,status,Set.of("enum","string"),false);
        var columns=List.of(column(app),column(inst),column(rev),column(state));
        if(new HashSet<>(columns).size()!=4)throw denied();
        for(var selected:List.of(app,inst,rev,state))fixedStorage(definition,selected);
        var readable=new HashSet<>(Set.of("pid","tenant_id","row_version",application,installation,revision,status));
        var decisions=new LinkedHashMap<String,MachineDataAccessContext.Decision>();
        JsonNode referenceReads=spec.path("referenceReadFields");
        if(!referenceReads.isObject() || referenceReads.size()>8)throw denied();
        var referenceEntries=referenceReads.fields();
        while(referenceEntries.hasNext()) {
            var entry=referenceEntries.next();String target=entry.getKey();JsonNode declaredFields=entry.getValue();
            if(!target.matches("[a-z][a-zA-Z0-9_]{0,127}") || !declaredFields.isArray() || declaredFields.isEmpty() || declaredFields.size()>32)throw denied();
            var targetModel=target.equals(model)?definition:models.getModelDefinitionFromDb(target).orElseThrow(InstalledIntegrationBindingLookup::denied);
            if(!target.equals(model))owner(principal,ownerPlugin,target,targetModel);
            Set<String> edges=RequiredRecordReadGuard.requiredReferenceFields(targetModel);
            var targetFields=new HashSet<>(Set.of("pid","tenant_id","row_version"));var distinct=new HashSet<String>();
            for(var value:declaredFields) {
                if(!value.isTextual() || !distinct.add(value.textValue()) || !("pid".equals(value.textValue()) || edges.contains(value.textValue())))throw denied();
                targetFields.add(value.textValue());
            }
            if(target.equals(model))readable.addAll(targetFields);
            else decisions.put(target,new MachineDataAccessContext.Decision(principal.tenantId(),principal.installationPid(),target,Set.of("read"),targetFields,Set.of(),List.of()));
        }
        var constraints=List.of(new MachineDataAccessContext.Constraint(application,columns.get(0),List.of(principal.applicationPid())),
                new MachineDataAccessContext.Constraint(installation,columns.get(1),List.of(principal.installationPid())),
                new MachineDataAccessContext.Constraint(status,columns.get(3),allowed));
        var decision=new MachineDataAccessContext.Decision(principal.tenantId(),principal.installationPid(),model,Set.of("read"),readable,Set.of(),constraints);
        decisions.put(model,decision);
        // begin/authorize are lexical; the original caller's machine decisions and identity are restored.
        try(var identity=MachineDataAccessContext.begin(principal);var scope=MachineDataAccessContext.authorizeRequiredReferenceReads(decisions)) {
            var page=data.queryPage(principal.tenantId(),model,new DataPageQuery(Map.of(application,principal.applicationPid(),installation,principal.installationPid()),
                    Map.of(status,allowed),List.of(),1,2));
            if(page.total()!=1 || page.records().size()!=1)throw denied();
            var row=page.records().getFirst();Object record=row.get("pid");
            if(!(record instanceof String recordPid) || !pid(recordPid) || !decision.permits(row)
                    || !Long.toString(principal.tenantId()).equals(String.valueOf(row.get("tenant_id")))
                    || row.containsKey("deleted_flag") && !Boolean.FALSE.equals(row.get("deleted_flag")))throw denied();
            positive(row.get("row_version"));return new Selection(positive(row.get(revision)),Map.of(parameter,recordPid));
        }
    }
    private void owner(MachinePrincipal principal,String ownerPlugin,String model,ModelDefinition definition) {
        if(!model.equals(definition.getCode()) || definition.getId()==null || !definition.isCommandOnlyCreate()
                || "view".equals(definition.getModelType()) || definition.getSourceType()!=null && !"physical".equals(definition.getSourceType())
                || definition.getPrimaryKey()!=null && !"pid".equals(definition.getPrimaryKey())
                || definition.getTableName()==null || !definition.getTableName().matches("mt_[A-Za-z0-9_]+"))throw denied();
        var entity=metadata.selectById(definition.getId());
        if(entity==null || !principal.tenantId().equals(entity.getTenantId()) || !model.equals(entity.getCode())
                || !Boolean.TRUE.equals(entity.getIsCurrent()) || !Boolean.FALSE.equals(entity.getDeletedFlag())
                || !"published".equals(entity.getStatus()) || entity.getPluginPid()==null)throw denied();
        var owner=owners.findByPid(entity.getPluginPid());
        if(owner==null || !principal.tenantId().equals(owner.getTenantId()) || !Objects.equals(ownerPlugin,owner.getPluginId())
                || !Boolean.FALSE.equals(owner.getDeletedFlag()))throw denied();
    }
    private static FieldDefinition field(Map<String,FieldDefinition> fields,String key,Set<String> types,boolean immutable) {
        var field=fields.get(key);
        if(field==null || !types.contains(field.getDataType()) || immutable && !Boolean.TRUE.equals(field.getImmutable())
                || field.getAllowedWriterCommands()==null || field.getAllowedWriterCommands().isEmpty())throw denied();
        return field;
    }
    private static void fixedStorage(ModelDefinition model,FieldDefinition field) {
        // Same fixed-edge requirement as RequiredReadReferences: metadata aliases cannot bypass provenance.
        if(field.isTransientField() || field.isComputedReadonly() || field.isVirtual() || field.isJsonbVirtual())throw denied();
        String protectedColumn=column(field);
        if(Set.of("pid","id","tenant_id","row_version","created_at","updated_at","created_by","updated_by","deleted_flag")
                .contains(protectedColumn.toLowerCase(java.util.Locale.ROOT)))throw denied();
        for(var other:model.getFields()) {
            if(other==field)continue;
            String otherColumn=other.getColumnName()==null || other.getColumnName().isBlank()?other.getCode():other.getColumnName(),jsonHost=other.getJsonbColumn();
            if(protectedColumn.equalsIgnoreCase(otherColumn) || jsonHost!=null && protectedColumn.equalsIgnoreCase(jsonHost))throw denied();
        }
    }
    private static String column(FieldDefinition field) {
        String value=field.getColumnName()==null || field.getColumnName().isBlank()?field.getCode():field.getColumnName();
        if(!value.matches("[A-Za-z_][A-Za-z0-9_]{0,127}"))throw denied();return value;
    }
    private static String identifier(JsonNode spec,String key) {
        var value=spec.path(key);if(!value.isTextual() || !value.textValue().matches("[a-z][a-zA-Z0-9_]{0,127}"))throw denied();return value.textValue();
    }
    private static Set<String> keys(JsonNode node) {var keys=new HashSet<String>();node.fieldNames().forEachRemaining(keys::add);return keys;}
    private static boolean pid(String value) {return value!=null && value.matches("[0-7][0-9A-HJKMNP-TV-Z]{25}");}
    private static long positive(Object value) {
        try {if(!(value instanceof Number))throw denied();long n=new BigDecimal(value.toString()).longValueExact();if(n<1)throw denied();return n;}
        catch(ArithmeticException | NumberFormatException invalid) {throw denied();}
    }
    private static SecurityException denied() {return new SecurityException("Managed installation binding lookup denied");}
}

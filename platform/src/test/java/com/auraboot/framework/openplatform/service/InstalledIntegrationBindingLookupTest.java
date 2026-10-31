package com.auraboot.framework.openplatform.service;

import com.auraboot.framework.application.security.ExternalMachineAuthenticator.MachinePrincipal;
import com.auraboot.framework.application.security.MachineDataAccessContext;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.framework.meta.entity.Model;
import com.auraboot.framework.meta.mapper.MetaModelMapper;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.plugin.entity.PluginRecord;
import com.auraboot.framework.plugin.extension.BackgroundDataAccessor;
import com.auraboot.framework.plugin.extension.DataPage;
import com.auraboot.framework.plugin.extension.DataPageQuery;
import com.auraboot.framework.plugin.mapper.PluginRecordMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Hermetic metadata/authority tests; real SDK predicates and transaction races have native gates. */
class InstalledIntegrationBindingLookupTest {
    static final String MODEL="worker_registry",OWNER="com.auraboot.worker-provider";
    static String id(int n) {return String.format("%026d",n);}
    final MachinePrincipal principal=new MachinePrincipal(7L,id(2),"Worker",Set.of("worker.heartbeat"),id(1),id(2),"development","token");
    final MetaModelService models=mock(MetaModelService.class);
    final MetaModelMapper metadata=mock(MetaModelMapper.class);
    final PluginRecordMapper owners=mock(PluginRecordMapper.class);
    final BackgroundDataAccessor data=mock(BackgroundDataAccessor.class);
    final ObjectMapper json=new ObjectMapper();
    final InstalledIntegrationBindingLookup lookup=new InstalledIntegrationBindingLookup(models,metadata,owners,data);
    ModelDefinition definition;Model entity;PluginRecord owner;List<FieldDefinition> fields;Map<String,Object> row;ObjectNode spec;
    @BeforeEach void setup() {
        MetaContext.setContext(7L,0L,"installation:"+id(2),"Installed");
        spec=json.valueToTree(Map.of("version",1,"modelCode",MODEL,"applicationField","wrk_application","installationField","wrk_installation",
                "revisionField","wrk_binding_revision","statusField","wrk_state","allowedStatuses",List.of("offline","online"),"recordPidParameter","workerPid","referenceReadFields",Map.of()));
        fields=new ArrayList<>(List.of(field("wrk_application","string",true),field("wrk_installation","string",true),
                field("wrk_binding_revision","long",true),field("wrk_state","enum",false)));
        definition=ModelDefinition.builder().id(11L).code(MODEL).tableName("mt_worker_registry").modelType("entity").sourceType("physical")
                .primaryKey("pid").commandOnlyCreate(true).fields(fields).build();
        entity=new Model();entity.setId(11L);entity.setCode(MODEL);entity.setTenantId(7L);entity.setPluginPid("owner");entity.setIsCurrent(true);entity.setDeletedFlag(false);entity.setStatus("published");
        owner=new PluginRecord();owner.setTenantId(7L);owner.setPluginId(OWNER);owner.setDeletedFlag(false);
        when(models.getModelDefinitionFromDb(MODEL)).thenReturn(Optional.of(definition));when(metadata.selectById(11L)).thenReturn(entity);when(owners.findByPid("owner")).thenReturn(owner);
        row=new LinkedHashMap<>(Map.of("pid",id(3),"tenant_id",7L,"row_version",10L,"wrk_application",id(1),"wrk_installation",id(2),"wrk_binding_revision",3L,"wrk_state","offline"));
        when(data.queryPage(eq(7L),eq(MODEL),any())).thenAnswer(call->{
            var decision=MachineDataAccessContext.requireDecision(MODEL);assertEquals(Set.of("read"),decision.actions());assertTrue(decision.writableFields().isEmpty());
            assertEquals(3,decision.constraints().size());assertEquals(principal,MachineDataAccessContext.principal());
            DataPageQuery query=call.getArgument(2);assertEquals(Map.of("wrk_application",id(1),"wrk_installation",id(2)),query.exactFilters());assertEquals(2,query.size());
            return new DataPage(List.of(row),1,1,2);
        });
    }
    static FieldDefinition field(String code,String type,boolean immutable) {
        var f=new FieldDefinition();f.setCode(code);f.setColumnName(code);f.setDataType(type);f.setImmutable(immutable);f.setAllowedWriterCommands(List.of("registry:register"));return f;
    }
    @AfterEach void clear() {MetaContext.clear();assertFalse(MachineDataAccessContext.isActive(),"Lookup decisions must not escape their lexical lifetime");}
    void denied() {assertThrows(SecurityException.class,()->lookup.resolve(principal,OWNER,spec));}
    @Test void resolvesOneExactManagedRecordWithIndependentStableBindingRevision() {
        var result=lookup.resolve(principal,OWNER,spec);assertEquals(3,result.revision());assertEquals(Map.of("workerPid",id(3)),result.parameters());
        row.put("row_version",99L);assertEquals(result,lookup.resolve(principal,OWNER,spec));
        assertThrows(UnsupportedOperationException.class,()->result.parameters().put("other","value"));
    }
    @Test void existingMachineScopeIsRestoredAfterSuccessAndReaderFailure() {
        var previous=new MachineDataAccessContext.Decision(7L,id(2),"other",Set.of("read"),Set.of("pid"),Set.of(),List.of());
        try(var original=MachineDataAccessContext.begin(principal);var scope=MachineDataAccessContext.authorize(Map.of("other",previous))) {
            lookup.resolve(principal,OWNER,spec);assertSame(previous,MachineDataAccessContext.requireDecision("other"));
            when(data.queryPage(eq(7L),eq(MODEL),any())).thenThrow(new IllegalStateException("owned-reader-fault"));
            assertThrows(IllegalStateException.class,()->lookup.resolve(principal,OWNER,spec));assertSame(previous,MachineDataAccessContext.requireDecision("other"));
        }
    }
    @Test void anotherActiveInstallationCannotBeReboundByMethodArguments() {
        var other=new MachinePrincipal(7L,id(9),"Other",principal.scopes(),id(1),id(9),"development","token");
        try(var scope=MachineDataAccessContext.begin(other)){denied();}verifyNoInteractions(data);
    }
    @Test void wrongTenantContextRejectsBeforeMetadataOrDataLookup() {
        MetaContext.setContext(8L,0L,"installation:"+id(2),"Wrong");denied();verifyNoInteractions(models,data);
    }
    @Test void crossPluginCrossTenantDeletedAndUnpublishedMetadataCannotGrantRead() {
        owner.setPluginId("com.auraboot.other");denied();owner.setPluginId(OWNER);entity.setTenantId(8L);denied();entity.setTenantId(7L);
        entity.setDeletedFlag(true);denied();entity.setDeletedFlag(false);entity.setStatus("draft");denied();verifyNoInteractions(data);
    }
    @Test void modelMustBeCurrentPhysicalPidAndCommandManaged() {
        definition.setCommandOnlyCreate(false);denied();definition.setCommandOnlyCreate(true);definition.setSourceType("named-query");denied();
        definition.setSourceType("physical");definition.setTableName("ab_tenant_member");denied();definition.setTableName("mt_worker_registry");entity.setIsCurrent(false);denied();verifyNoInteractions(data);
    }
    @Test void bindingIdentityAndRevisionFieldsMustBeImmutableAndControlled() {
        for(int i=0;i<3;i++){fields.get(i).setImmutable(false);denied();fields.get(i).setImmutable(true);}
        fields.get(3).setAllowedWriterCommands(null);denied();fields.get(3).setAllowedWriterCommands(List.of("registry:register"));
        fields.get(2).setDataType("string");denied();verifyNoInteractions(data);
    }
    @Test void emptyOrAmbiguousRegistryNeverChoosesAnArbitraryRow() {
        when(data.queryPage(eq(7L),eq(MODEL),any())).thenReturn(new DataPage(List.of(),0,1,2));denied();
        when(data.queryPage(eq(7L),eq(MODEL),any())).thenReturn(new DataPage(List.of(row,new LinkedHashMap<>(row)),2,1,2));denied();
    }
    @Test void returnedRowsMustRetainTenantInstallationApplicationStatusAndPublicPid() {
        for(var change:Map.of("tenant_id",(Object)8L,"wrk_installation",(Object)id(9),"wrk_application",(Object)id(9),"wrk_state",(Object)"revoked","pid",(Object)"internal-17").entrySet()) {
            Object before=row.put(change.getKey(),change.getValue());denied();row.put(change.getKey(),before);
        }
    }
    @Test void invalidRevisionNeverBecomesAnImplicitFirstBinding() {
        for(Object value:List.of(0L,-1L,new BigDecimal("1.5"),new BigDecimal("9223372036854775808"),"3")){row.put("wrk_binding_revision",value);denied();}
    }
    @Test void lookupDeclarationRejectsUnknownVersionFieldsAliasesAndBroadStatusSets() {
        spec.put("version",4294967297L);denied();spec.put("version",1);spec.put("extra","ignored");denied();spec.remove("extra");
        spec.put("installationField","wrk_application");denied();spec.put("installationField","wrk_installation");
        spec.put("applicationField","tenant_id");denied();spec.put("applicationField","wrk_application");
        spec.set("allowedStatuses",json.valueToTree(List.of("online","online")));denied();verifyNoInteractions(data);
    }
    @Test void duplicateMetadataFieldsAndPhysicalAliasesCannotBroadenThePredicate() {
        fields.add(fields.getFirst());denied();fields.removeLast();fields.get(1).setColumnName(fields.getFirst().getColumnName());denied();verifyNoInteractions(data);
    }
    @Test void selectedIdentityFieldsCannotBeVirtualOrJsonPathStorage() {
        for(String kind:List.of("transient","computed_readonly","materialized")){fields.getFirst().setVirtualType(kind);denied();}
        fields.getFirst().setVirtualType(null);fields.getFirst().setJsonbColumn("payload");fields.getFirst().setJsonbPath("application");denied();verifyNoInteractions(data);
    }
    @Test void unselectedWritableColumnAndJsonHostAliasesCannotRewriteBindingIdentity() {
        var alias=field("mutable_alias","string",false);alias.setColumnName("WRK_APPLICATION");fields.add(alias);denied();fields.removeLast();
        alias=field("json_member","string",false);alias.setJsonbColumn("wrk_binding_revision");alias.setJsonbPath("other");fields.add(alias);denied();verifyNoInteractions(data);
    }
    @Test void aRenamedSystemVersionColumnCannotBecomeAnIndependentBindingRevision() {
        fields.get(2).setColumnName("row_version");denied();verifyNoInteractions(data);
    }
    @Test void ordinaryHumanOrUnboundSystemIdentityCannotUseTheLookupReader() {
        MetaContext.setContext(7L,42L,"human","Human");denied();MetaContext.setContext(7L,0L,"system","System");denied();verifyNoInteractions(models,data);
    }
    @Test void missingOrInvalidPhysicalRowVersionCannotQualifyAManagedRecord() {
        row.remove("row_version");denied();row.put("row_version",0L);denied();
    }
    void referenceModel() {
        fields.add(field("wrk_pool","reference",true));definition.setImmutable(true);
        definition.setExtension(Map.of("requiredReadReferences",Map.of("version",1,"maxCandidateRecords",10,"minimumReferences",1,
                "references",List.of(Map.of("field","wrk_pool","model","pool_registry","many",false,"allowEmpty",false)))));
        var pool=ModelDefinition.builder().id(12L).code("pool_registry").tableName("mt_pool_registry").modelType("entity")
                .sourceType("physical").primaryKey("pid").commandOnlyCreate(true).fields(List.of()).build();
        var poolEntity=new Model();poolEntity.setId(12L);poolEntity.setCode("pool_registry");poolEntity.setTenantId(7L);
        poolEntity.setPluginPid("owner");poolEntity.setIsCurrent(true);poolEntity.setDeletedFlag(false);poolEntity.setStatus("published");
        when(models.getModelDefinitionFromDb("pool_registry")).thenReturn(Optional.of(pool));when(metadata.selectById(12L)).thenReturn(poolEntity);
        spec.set("referenceReadFields",json.valueToTree(Map.of(MODEL,List.of("wrk_pool"),"pool_registry",List.of("pid"))));
    }
    @Test void referenceLookupGrantsOnlyCanonicalEdgeFieldsAndOwnedTargetIdentities() {
        referenceModel();when(data.queryPage(eq(7L),eq(MODEL),any())).thenAnswer(call->{
            assertNotNull(MachineDataAccessContext.requiredReferenceReadScope());
            assertTrue(MachineDataAccessContext.requireDecision(MODEL).readableFields().contains("wrk_pool"));
            var target=MachineDataAccessContext.requireDecision("pool_registry");
            assertEquals(Set.of("pid","tenant_id","row_version"),target.readableFields());assertTrue(target.writableFields().isEmpty());
            return new DataPage(List.of(row),1,1,2);
        });
        assertEquals(Map.of("workerPid",id(3)),lookup.resolve(principal,OWNER,spec).parameters());
    }
    @Test void referenceLookupCannotGrantUnrelatedPrivateFieldsOrCrossOwnerTargets() {
        referenceModel();spec.set("referenceReadFields",json.valueToTree(Map.of(MODEL,List.of("wrk_state"))));denied();
        spec.set("referenceReadFields",json.valueToTree(Map.of("pool_registry",List.of("secret"))));denied();
        spec.set("referenceReadFields",json.valueToTree(Map.of("pool_registry",List.of("pid"))));
        var other=metadata.selectById(12L);other.setPluginPid("other");
        var otherOwner=new PluginRecord();otherOwner.setTenantId(7L);otherOwner.setPluginId("com.auraboot.other");otherOwner.setDeletedFlag(false);
        when(owners.findByPid("other")).thenReturn(otherOwner);denied();verifyNoInteractions(data);
    }

}

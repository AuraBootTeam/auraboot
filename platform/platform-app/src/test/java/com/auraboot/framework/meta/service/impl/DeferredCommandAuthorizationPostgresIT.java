package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.constant.DataType;
import com.auraboot.framework.meta.constant.Status;
import com.auraboot.framework.meta.entity.*;
import com.auraboot.framework.meta.entity.payload.*;
import com.auraboot.framework.meta.mapper.*;
import com.auraboot.framework.meta.service.*;
import com.auraboot.framework.permission.service.*;
import com.auraboot.framework.plugin.extension.CommandHandlerExtension;
import com.auraboot.framework.plugin.pf4j.ExtensionRegistry;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.doReturn;

/** Actual queue claims, IAM/pipeline decisions and native checkpoint writes; only the handler is a controlled seam. */
@SpringBootTest(classes=TestApplication.class, properties="spring.data.redis.host=")
@Transactional(propagation=Propagation.NEVER)
class DeferredCommandAuthorizationPostgresIT extends BaseIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired MetaModelMapper models;
    @Autowired MetaFieldMapper fields;
    @Autowired MetaModelFieldBindingMapper bindings;
    @Autowired SchemaManagementService schema;
    @Autowired MetaModelService metadata;
    @Autowired CommandMetadataCacheService commands;
    @Autowired AsyncTaskMapper jobs;
    @Autowired AsyncTaskServiceImpl tasks;
    @Autowired UserPermissionService permissions;
    @Autowired DataScopeService scopes;
    @Autowired FieldPermissionService fieldPermissions;
    @Autowired ObjectMapper json;
    @MockitoSpyBean ExtensionRegistry extensions;
    Model model;
    Field field;
    String code, duty, table, root, label;
    final List<Long> jobIds = new ArrayList<>();
    final AtomicInteger calls = new AtomicInteger();

    @BeforeEach void fixture() throws Exception {
        String suffix=Long.toUnsignedString(System.nanoTime());
        String modelCode="da_root_"+suffix;
        label="da_label_"+suffix;code="deferred_fixture:"+suffix;duty="deferred."+suffix+".execute";
        model=new Model();model.setPid(UniqueIdGenerator.generate());model.setTenantId(tenant());
        model.setCode(modelCode);model.setVersion(1);model.setIsCurrent(true);model.setStatus(Status.PUBLISHED.getCode());
        model.setCreatedAt(Instant.now());model.setUpdatedAt(Instant.now());model.setDeletedFlag(false);
        var extension=new ExtensionBean();extension.setExtension(new HashMap<>(Map.of("modelType","entity","softDelete",true)));
        model.setExtension(extension);models.insert(model);
        field=new Field();field.setPid(UniqueIdGenerator.generate());field.setTenantId(tenant());field.setCode(label);
        field.setDataType(DataType.STRING.getCode());field.setVersion(1);field.setIsCurrent(true);field.setStatus(Status.PUBLISHED.getCode());
        field.setCreatedAt(Instant.now());field.setUpdatedAt(Instant.now());field.setDeletedFlag(false);field.setFeature(new FieldFeatureBean());fields.insert(field);
        var binding=new ModelFieldBinding();binding.setTenantId(tenant());binding.setModelId(model.getId());binding.setFieldId(field.getId());binding.setFieldOrder(0);bindings.insert(binding);
        var result=schema.createTableByModel(modelCode);assertThat(result.isSuccess()).as(result.getErrorMessage()).isTrue();
        table=metadata.getModelDefinition(modelCode).orElseThrow().getTableName();assertThat(table).matches("mt_da_root_[0-9]+");
        root=UniqueIdGenerator.generate();
        jdbc.update("INSERT INTO "+table+" (id,pid,tenant_id,created_by,updated_by,row_version,deleted_flag,"+label+") VALUES (?,?,?,?,?,0,false,?)",
                IdWorker.getId(),root,tenant(),getTestUser().getId(),getTestUser().getId(),"draft");
        grantCommittedPermissionToTestRole(duty,"API",modelCode,"execute","Deferred fixture duty");
        for(String action:List.of("read","update")) grantCommittedPermissionToTestRole("model."+modelCode+"."+action,"model",modelCode,action,"Deferred fixture "+action);
        jdbc.update("""
                INSERT INTO ab_command_definition(id,pid,tenant_id,code,model_code,execution_config,version,is_current,row_version,status,deleted_flag,created_at,updated_at)
                VALUES (?,?,?,?,?,?::jsonb,1,true,0,'published',false,now(),now())
                """,IdWorker.getId(),UniqueIdGenerator.generate(),tenant(),code,modelCode,json.writeValueAsString(config(code)));
        commands.evictAll();
        CommandHandlerExtension handler=new CommandHandlerExtension() {
            public String getCommandType(){return code;}
            public boolean supportsDryRun(){return false;}
            public boolean requiresDslPersistence(String c,Map<String,Object> config,com.auraboot.framework.meta.dto.CommandExecuteRequest request){return false;}
            public Object execute(CommandContext context) {
                calls.incrementAndGet();
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).as("authorization transaction ended before checkpoint work").isFalse();
                assertThat(MetaContext.getCommandAuthority()).isEqualTo(duty);
                assertThat(MetaContext.getCommandPermitScope()).isEqualTo("TARGET");
                assertThat(MetaContext.getCommandExpectedVersion(model.getCode(),root)).isEqualTo(0L);
                assertThat(context.tenantSecretAccessor()).isNull();
                assertThat(context.independentTransactionAccessor()).isNotNull();
                context.independentTransactionAccessor().requiresNew(db->{
                    assertThat(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()).isEqualTo(java.sql.Connection.TRANSACTION_REPEATABLE_READ);
                    db.update(model.getCode(),root,Map.of(label,context.payload().get(label)));return null;
                });
                return Map.of("success",true);
            }
        };
        doReturn(Optional.of(handler)).when(extensions).getCommandHandler(code);
    }

    Map<String,Object> config(String handler){return Map.of("type","custom","handler",handler,"permissions",List.of(duty),"handlerParams",Map.of("async",false,"dslPersistence",false));}
    Long tenant(){return getTestTenant().getId();}
    Map<String,Object> row(){return jdbc.queryForMap("SELECT * FROM "+table+" WHERE pid=?",root);}
    String grants(){return jdbc.queryForObject("SELECT jsonb_agg(to_jsonb(g) ORDER BY g.id)::text FROM ab_role_permission g JOIN ab_permission p ON p.id=g.permission_id WHERE g.tenant_id=? AND p.resource_code=?",String.class,tenant(),model.getCode());}
    void evict(){permissions.evictRoleUsers(tenant(),getTestRole().getId());permissions.evictUserPermissions(tenant(),getTestUser().getId());}
    AsyncTask queued() {
        var input=json.createObjectNode();input.put("handlerCode",code);input.put("commandCode",code);
        input.put("tenantId",tenant());input.put("userId",getTestUser().getId());input.put("currentUserPid",getTestUser().getPid());
        input.put("modelCode",model.getCode());input.put("recordPid",root);input.put("commandAuthority","old.unrelated.allow");input.put("commandPermitScope","ALL");
        input.set("handlerParams",json.valueToTree(Map.of("tenantSecretRead",true)));
        input.set("payload",json.valueToTree(Map.of(label,"approved")));
        var job=new AsyncTask();job.setTenantId(tenant());job.setTaskCode(UniqueIdGenerator.generate());job.setTaskType("command-handler");job.setTaskName(code);
        job.setStatus("pending");job.setPriority(5);job.setProgress(0);job.setInputParams(input);job.setRetryCount(0);job.setMaxRetries(0);
        job.setCreatedBy(getTestUser().getId());job.setCreatedAt(Instant.now());job.setTimeoutSeconds(30);jobs.insert(job);jobIds.add(job.getId());return job;
    }
    AsyncTask execute(AsyncTask job) {
        tasks.executeTaskAsync(job.getId(),tenant());
        await().atMost(Duration.ofSeconds(20)).until(()->{
            String status=jdbc.queryForObject("SELECT status FROM ab_async_task WHERE id=?",String.class,job.getId());
            return Set.of("completed","failed").contains(status);
        });
        return jobs.selectById(job.getId());
    }
    void deniedWithoutWrite(AsyncTask job){var before=row();String grants=grants();var done=execute(job);assertThat(done.getStatus()).as(done.getErrorMessage()).isEqualTo("failed");assertThat(calls).hasValue(0);assertThat(row()).isEqualTo(before);assertThat(grants()).isEqualTo(grants);}

    @Test void actualClaimUsesCurrentPolicyAndCommitsAnIndependentNativeCheckpoint() {
        String grants=grants();var done=execute(queued());assertThat(done.getStatus()).as(done.getErrorMessage()).isEqualTo("completed");
        assertThat(calls).hasValue(1);assertThat(row()).containsEntry(label,"approved");assertThat(((Number)row().get("row_version")).longValue()).isEqualTo(1L);
        assertThat(grants()).isEqualTo(grants);
    }
    @Test void revokedCommandDutyCannotBeRestoredFromTheQueuedAllow() {
        var job=queued();jdbc.update("UPDATE ab_role_permission SET status='inactive' WHERE role_id=? AND permission_id IN(SELECT id FROM ab_permission WHERE tenant_id=? AND code=?)",getTestRole().getId(),tenant(),duty);evict();deniedWithoutWrite(job);
    }
    @Test void narrowedRecordScopeCannotBeRestoredFromQueuedAll() {
        var job=queued();scopes.setScope(tenant(),getTestRole().getId(),model.getCode(),"read","none","MAX");evict();deniedWithoutWrite(job);
    }
    @Test void expiredCommandDutyCannotBeRestoredFromTheQueuedAllow() {
        var job=queued();jdbc.update("UPDATE ab_role_permission SET expiry_date=CURRENT_DATE-1 WHERE role_id=? AND permission_id IN(SELECT id FROM ab_permission WHERE tenant_id=? AND code=?)",getTestRole().getId(),tenant(),duty);evict();deniedWithoutWrite(job);
    }
    @Test void checkpointWritesStillHonorCurrentFiniteFieldAllowances() throws Exception {
        var job=queued();
        String constraint=json.writeValueAsString(Map.of("modelCode",model.getCode(),"readFields",List.of(label),"writeFields",List.of()));
        jdbc.update("UPDATE ab_role_permission SET field_scope=?::jsonb WHERE role_id=? AND permission_id IN(SELECT id FROM ab_permission WHERE tenant_id=? AND code=?)",
                constraint,getTestRole().getId(),tenant(),"model."+model.getCode()+".update");
        evict();var before=row();String grantBytes=grants();var done=execute(job);
        assertThat(done.getStatus()).as(done.getErrorMessage()).isEqualTo("failed");
        assertThat(calls).hasValue(1);assertThat(row()).isEqualTo(before);assertThat(grants()).isEqualTo(grantBytes);
    }

    void rootBusinessDuty() {
        jdbc.update("UPDATE ab_permission SET resource_type='operation',action='manage' WHERE tenant_id=? AND code=?",tenant(),duty);
        scopes.setScope(tenant(),getTestRole().getId(),model.getCode(),"manage","self","MAX");
        permissions.evictPermissionDefinitions(tenant());evict();
    }
    @Test void anOwningBusinessDutyNeedsNoTechnicalModelUpdateGrant() {
        rootBusinessDuty();
        jdbc.update("UPDATE ab_role_permission SET status='inactive' WHERE role_id=? AND permission_id IN(SELECT id FROM ab_permission WHERE tenant_id=? AND code=?)",getTestRole().getId(),tenant(),"model."+model.getCode()+".update");
        evict();String grants=grants();var done=execute(queued());
        assertThat(done.getStatus()).as(done.getErrorMessage()).isEqualTo("completed");
        assertThat(calls).hasValue(1);assertThat(row()).containsEntry(label,"approved");assertThat(grants()).isEqualTo(grants);
    }
    @Test void completeBusinessCheckpointRetainsMetadataFieldRolesWithoutRequiringModelCrud() {
        rootBusinessDuty();
        jdbc.update("UPDATE ab_role_permission SET status='inactive' WHERE role_id=? AND permission_id IN(SELECT id FROM ab_permission WHERE tenant_id=? AND code=?)",getTestRole().getId(),tenant(),"model."+model.getCode()+".update");
        var restriction=new ExtensionBean();restriction.setExtension(new HashMap<>(Map.of("fieldPermission",Map.of("edit",List.of("unassigned_"+model.getCode())))));
        field.setExtension(restriction);fields.updateById(field);metadata.clearAllCache();evict();
        assertThat(fieldPermissions.getFieldPermissions(getTestTenantMember().getId(),model.getCode()).editableFields()).doesNotContain(label);
        var before=row();String original=grants();var rejected=execute(queued());var after=row();
        assertThat(rejected.getStatus()).as("Current metadata role constraint; row=%s",after).isEqualTo("failed");
        assertThat(calls).hasValue(1);assertThat(after).isEqualTo(before);assertThat(grants()).isEqualTo(original);
        var unrestricted=new ExtensionBean();unrestricted.setExtension(new HashMap<>());
        field.setExtension(unrestricted);fields.updateById(field);metadata.clearAllCache();evict();
        var accepted=execute(queued());assertThat(accepted.getStatus()).as(accepted.getErrorMessage()).isEqualTo("completed");
        assertThat(calls).hasValue(2);assertThat(row()).containsEntry(label,"approved");assertThat(grants()).isEqualTo(original);
    }

    @Test void owningBusinessFieldsCannotBeBypassedByAnUnrestrictedModelGrant() throws Exception {
        rootBusinessDuty();
        jdbc.update("UPDATE ab_role_permission SET field_scope=?::jsonb WHERE role_id=? AND permission_id IN(SELECT id FROM ab_permission WHERE tenant_id=? AND code=?)",
                json.writeValueAsString(Map.of("modelCode",model.getCode(),"readFields",List.of(label),"writeFields",List.of())),getTestRole().getId(),tenant(),duty);
        evict();var before=row();String grants=grants();var done=execute(queued());
        assertThat(done.getStatus()).as(done.getErrorMessage()).isEqualTo("failed");assertThat(calls).hasValue(1);
        assertThat(row()).isEqualTo(before);assertThat(grants()).isEqualTo(grants);
    }
    @Test void owningBusinessScopeRefusalCannotFallBackToAnUnrestrictedModelGrant() {
        rootBusinessDuty();scopes.setScope(tenant(),getTestRole().getId(),model.getCode(),"manage","none","MAX");evict();
        var before=row();String grants=grants();var done=execute(queued());
        assertThat(done.getStatus()).as(done.getErrorMessage()).isEqualTo("failed");assertThat(calls).hasValue(1);
        assertThat(row()).isEqualTo(before);assertThat(grants()).isEqualTo(grants);
    }

    @Test void queuedPidCannotSubstituteAnotherHumanIdentity() {
        var job=queued();var input=(com.fasterxml.jackson.databind.node.ObjectNode)job.getInputParams();
        input.put("currentUserPid",UniqueIdGenerator.generate());job.setInputParams(input);jobs.updateById(job);
        deniedWithoutWrite(job);
    }

    @ParameterizedTest @ValueSource(strings={"handler","model","unpublished","business"})
    void currentDefinitionCannotBeSubstitutedByQueuedMetadata(String change) throws Exception {
        var job=queued();
        if("handler".equals(change))jdbc.update("UPDATE ab_command_definition SET execution_config=?::jsonb WHERE tenant_id=? AND code=?",json.writeValueAsString(config("another:handler")),tenant(),code);
        if("model".equals(change))jdbc.update("UPDATE ab_command_definition SET model_code='foreign_model' WHERE tenant_id=? AND code=?",tenant(),code);
        if("unpublished".equals(change))jdbc.update("UPDATE ab_command_definition SET status='draft' WHERE tenant_id=? AND code=?",tenant(),code);
        if("business".equals(change))jdbc.update("UPDATE ab_command_definition SET execution_config=jsonb_set(execution_config,'{handlerParams,targetRecordAuthorization}','\"business\"') WHERE tenant_id=? AND code=?",tenant(),code);
        commands.evictAll();deniedWithoutWrite(job);
    }

    @AfterEach void cleanupFixture() {
        for(Long id:jobIds){assertThat(jdbc.queryForObject("SELECT status FROM ab_async_task WHERE id=?",String.class,id)).isIn("completed","failed","pending");jdbc.update("DELETE FROM ab_async_task WHERE id=?",id);}
        if(model!=null){
            jdbc.update("DELETE FROM ab_command_audit_log WHERE tenant_id=? AND command_code=?",tenant(),code);
            jdbc.update("DELETE FROM ab_command_definition WHERE tenant_id=? AND code=?",tenant(),code);
            jdbc.update("DELETE FROM ab_role_data_scope WHERE tenant_id=? AND resource_code=?",tenant(),model.getCode());
            jdbc.update("DELETE FROM ab_role_permission WHERE tenant_id=? AND permission_id IN(SELECT id FROM ab_permission WHERE tenant_id=? AND resource_code=?)",tenant(),tenant(),model.getCode());
            jdbc.update("DELETE FROM ab_permission WHERE tenant_id=? AND resource_code=?",tenant(),model.getCode());
            if(table!=null){assertThat(table).matches("mt_da_root_[0-9]+");jdbc.execute("DROP TABLE "+table);}
            jdbc.update("DELETE FROM ab_meta_model_field_binding WHERE tenant_id=? AND model_id=?",tenant(),model.getId());
            jdbc.update("DELETE FROM ab_meta_model WHERE tenant_id=? AND id=?",tenant(),model.getId());
        }
        if(field!=null)jdbc.update("DELETE FROM ab_meta_field WHERE tenant_id=? AND id=?",tenant(),field.getId());
        commands.evictAll();metadata.clearAllCache();evict();
    }
}

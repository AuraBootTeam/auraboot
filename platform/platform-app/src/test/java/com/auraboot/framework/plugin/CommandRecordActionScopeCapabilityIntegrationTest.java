package com.auraboot.framework.plugin;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.constant.DataType;
import com.auraboot.framework.meta.constant.Status;
import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.entity.CommandDefinition;
import com.auraboot.framework.meta.entity.Field;
import com.auraboot.framework.meta.entity.Model;
import com.auraboot.framework.meta.entity.ModelFieldBinding;
import com.auraboot.framework.meta.entity.payload.ExtensionBean;
import com.auraboot.framework.meta.entity.payload.FieldFeatureBean;
import com.auraboot.framework.meta.mapper.MetaFieldMapper;
import com.auraboot.framework.meta.mapper.MetaModelFieldBindingMapper;
import com.auraboot.framework.meta.mapper.MetaModelMapper;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.meta.service.SchemaManagementService;
import com.auraboot.framework.meta.service.impl.pipeline.CommandAuthorizationVerdict;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPipeline;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPipelineContext;
import com.auraboot.framework.meta.service.impl.pipeline.phases.CommandTargetScopePhase;
import com.auraboot.framework.permission.service.DataScopeService;
import com.auraboot.framework.permission.service.PermissionFacade;
import com.auraboot.framework.permission.service.RecordShareService;
import com.auraboot.framework.plugin.extension.RecordActionScopeAccessor;
import com.auraboot.framework.plugin.pf4j.CommandRecordActionScopeAccessorFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

/** Real host policy/cache/PG checks; does not claim a product HTTP command or browser journey. */
@SpringBootTest(classes=TestApplication.class, properties={
        "spring.datasource.url=${TEST_DATABASE_URL}", "spring.datasource.username=${TEST_DATABASE_USERNAME}",
        "spring.datasource.password=${TEST_DATABASE_PASSWORD}", "auraboot.announcement.worker-enabled=false"})
@ActiveProfiles("integration-test")
@Transactional(propagation=Propagation.NOT_SUPPORTED)
class CommandRecordActionScopeCapabilityIntegrationTest extends BaseIntegrationTest {
    @Autowired private CommandRecordActionScopeAccessorFactory factory;
    @Autowired private CommandTargetScopePhase targetScope;
    @Autowired private DynamicDataService data;
    @Autowired private SchemaManagementService schema;
    @Autowired private MetaModelMapper models;
    @Autowired private MetaFieldMapper fields;
    @Autowired private MetaModelFieldBindingMapper bindings;
    @Autowired private DataScopeService scopes;
    @Autowired private RecordShareService shares;
    @Autowired private PermissionFacade permissions;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private JdbcTemplate jdbc;
    private String model;
    private String label;
    private String pid;

    @BeforeEach void ownedFixture() {
        assertEquals("maritime-parallel-core",System.getenv("AURA_RUNTIME_NAME"));
        assertEquals("auraboot_26",System.getenv("POSTGRES_DB"));
        assertEquals("auraboot_26",jdbc.queryForObject("select current_database()",String.class));
        String suffix=Long.toUnsignedString(System.nanoTime()); model="cmd_scope_"+suffix; label="scope_label_"+suffix;
        var entity=new Model(); entity.setPid(UniqueIdGenerator.generate()); entity.setTenantId(getTestTenant().getId());
        entity.setCode(model); entity.setVersion(1); entity.setIsCurrent(true); entity.setStatus(Status.PUBLISHED.getCode());
        entity.setCreatedAt(Instant.now()); entity.setUpdatedAt(Instant.now()); entity.setDeletedFlag(false);
        var extension=new ExtensionBean(); extension.setExtension(new HashMap<>(Map.of(
                "displayName","Command scope fixture", "modelType","entity","softDelete",true))); entity.setExtension(extension);
        models.insert(entity);
        var field=new Field(); field.setPid(UniqueIdGenerator.generate()); field.setTenantId(getTestTenant().getId());
        field.setCode(label); field.setDataType(DataType.STRING.getCode()); field.setVersion(1); field.setIsCurrent(true);
        field.setStatus(Status.PUBLISHED.getCode()); field.setCreatedAt(Instant.now()); field.setUpdatedAt(Instant.now());
        field.setDeletedFlag(false); field.setFeature(new FieldFeatureBean()); fields.insert(field);
        var binding=new ModelFieldBinding(); binding.setTenantId(getTestTenant().getId());
        binding.setModelId(entity.getId()); binding.setFieldId(field.getId()); binding.setFieldOrder(0); bindings.insert(binding);
        var created=schema.createTableByModel(model); assertTrue(created.isSuccess(),created.getErrorMessage());
        grantCommittedPermissionToTestRole(model+".read","model",model,"read","Read command scope fixture");
        setScope("read","all"); setScope("update","none"); setScope("delete","none");
        var record=MetaContext.runWithCommandPermitScope("ALL",()->data.create(model,new HashMap<>(Map.of(label,"Before"))));
        pid=(String)record.get("pid"); assertNotNull(pid);
        var stored=MetaContext.runWithCommandPermitScope("ALL",()->data.getById(model,pid));
        assertEquals(getTestTenant().getId(),stored.get("tenant_id"));
        assertEquals(pid,stored.get("pid"));
        assertFalse(permissions.canAction(getTestTenantMember().getId(),model,"update"),"Fixture must not have generic model update");
    }
    private void setScope(String action,String type) {
        scopes.setScope(getTestTenant().getId(),getTestRole().getId(),model,action,type,"MAX");
    }
    private CommandPipelineContext context() {
        var command=new CommandDefinition(); command.setCode(model+"_manage"); command.setModelCode(model); command.setCommandType("update");
        var request=new CommandExecuteRequest(); request.setTargetRecordId(pid); request.setPayload(Map.of()); request.setClientRequestId("stable-request-key");
        return CommandPipelineContext.builder().tenantId(getTestTenant().getId()).userId(getTestUser().getId())
                .commandCode(command.getCode()).command(command).request(request)
                .authorizationVerdict(CommandAuthorizationVerdict.authorized(model+".manage"))
                .execConfig(Map.of("handlerParams",Map.of("targetRecordAction","update"))).build();
    }
    private void share(String actions) {
        shares.shareRecordByPid(getTestTenant().getId(),model,pid,"member",getTestTenantMember().getId(),
                getTestUser().getPid(),actions,null);
    }
    private boolean permits(String action) {
        try(var callback=factory.forInvocation(context())) { return callback.accessor().permits(model,pid,action); }
    }
    @Test void actualReadAllAndUpdateNoneDenyBeforeTheGuardedReplayStage() {
        assertTrue(permits("read")); assertFalse(permits("update"));
        var context=context(); var pipeline=new CommandPipeline(List.of(targetScope),List.of());
        assertThrows(BusinessException.class,()->pipeline.executePreGuardPhases(context));
        assertFalse(context.getTargetRecordReadable());
        assertEquals("Before",MetaContext.runWithCommandPermitScope("ALL",()->data.getById(model,pid)).get(label));
    }
    @Test void aCommandMayUseItsOwnCapabilityWithUpdateAllWithoutGenericModelUpdate() {
        setScope("update","all"); assertTrue(permits("update")); assertFalse(permits("delete"));
        var context=context(); targetScope.execute(context); assertTrue(context.getTargetRecordReadable());
        assertFalse(permissions.canAction(getTestTenantMember().getId(),model,"update"));
        assertNotNull(context.getTargetRecordVersion());
    }
    @Test void readSharingNeverBecomesUpdateSharingAndRevocationRechecksTheSameRequest() {
        share("read"); assertTrue(permits("read")); assertFalse(permits("update"));
        assertThrows(BusinessException.class,()->targetScope.execute(context()));
        share("read,update"); assertTrue(permits("update")); targetScope.execute(context());
        assertFalse(permits("delete"));
        var entries=shares.listByRecordPid(getTestTenant().getId(),model,pid); assertEquals(1,entries.size());
        shares.removeByPid(getTestTenant().getId(),entries.getFirst().getPid());
        assertFalse(permits("update")); assertThrows(BusinessException.class,()->targetScope.execute(context()));
    }
    @Test void anInvocationBridgeReadsCommittedScopeChangesEvenInsideAnOlderTransaction() {
        setScope("update","all");
        new TransactionTemplate(transactions).executeWithoutResult(tx->{
            try(var invocation=factory.forInvocation(context())) {
                assertTrue(invocation.accessor().permits(model,pid,"update"));
                var independent=new TransactionTemplate(transactions);
                independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                independent.executeWithoutResult(other->setScope("update","none"));
                assertFalse(invocation.accessor().permits(model,pid,"update"));
            }
        });
    }
    @Test void theHandlerBridgeBelongsToOnlyItsOriginalPhysicalTransaction() {
        assertNull(factory.forCommand(context())); setScope("update","all");
        var retained=new AtomicReference<RecordActionScopeAccessor>();
        new TransactionTemplate(transactions).executeWithoutResult(tx->{
            var bridge=factory.forCommand(context()); assertNotNull(bridge); retained.set(bridge);
            assertTrue(bridge.permits(model,pid,"update"));
            var nested=new TransactionTemplate(transactions); nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            nested.executeWithoutResult(other->assertFalse(bridge.permits(model,pid,"update")));
            assertTrue(bridge.permits(model,pid,"update"));
        });
        assertFalse(retained.get().permits(model,pid,"update"));
        new TransactionTemplate(transactions).executeWithoutResult(tx->assertFalse(retained.get().permits(model,pid,"update")));
    }
    @Test void numericAliasesDeletedRowsAndAnotherTenantCannotReuseAParentProof() {
        try(var callback=factory.forInvocation(context())) {
            var row=MetaContext.runWithCommandPermitScope("ALL",()->data.getById(model,pid));
            assertFalse(callback.accessor().permits(model,String.valueOf(row.get("id")),"read"));
            assertFalse(callback.accessor().permits(model,"missing-public-pid","read"));
            MetaContext.setContext(getTestTenant().getId()+1,getTestUser().getId(),getTestUser().getPid(),"Wrong tenant");
            MetaContext.setMemberId(getTestTenantMember().getId());
            assertFalse(callback.accessor().permits(model,pid,"read"));
        } finally {
            MetaContext.setContext(getTestTenant().getId(),getTestUser().getId(),getTestUser().getPid(),getTestUser().getUserName());
            MetaContext.setMemberId(getTestTenantMember().getId());
        }
        MetaContext.runWithCommandPermitScope("ALL",()->{data.delete(model,pid);});
        assertFalse(permits("read"));
    }
}

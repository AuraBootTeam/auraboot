package com.auraboot.framework.permission;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.dto.DataImportRequest;
import com.auraboot.framework.meta.entity.Field;
import com.auraboot.framework.meta.entity.Model;
import com.auraboot.framework.meta.entity.ModelFieldBinding;
import com.auraboot.framework.meta.entity.payload.ExtensionBean;
import com.auraboot.framework.meta.entity.payload.FieldFeatureBean;
import com.auraboot.framework.meta.mapper.MetaFieldMapper;
import com.auraboot.framework.meta.mapper.MetaModelFieldBindingMapper;
import com.auraboot.framework.meta.mapper.MetaModelMapper;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.meta.service.SchemaManagementService;
import com.auraboot.framework.permission.service.FieldPermissionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

/** Published field metadata, native owner paths and persisted rows all use real PostgreSQL. */
@SpringBootTest(classes = TestApplication.class, properties = "spring.data.redis.host=")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class NativeDslFieldWriteIT extends BaseIntegrationTest {
    @Autowired DynamicDataService data;
    @Autowired SchemaManagementService schema;
    @Autowired MetaModelService metadata;
    @Autowired MetaModelMapper models;
    @Autowired MetaFieldMapper fields;
    @Autowired MetaModelFieldBindingMapper bindings;
    @Autowired FieldPermissionService fieldPermissions;
    @Autowired JdbcTemplate jdbc;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @Autowired com.auraboot.framework.permission.service.DataScopeService scopes;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    com.auraboot.framework.plugin.pf4j.ExtensionRegistry extensions;
    @Autowired com.auraboot.framework.meta.service.FieldMaskService masks;
    private final List<Long> maskIds = new ArrayList<>();
    @Autowired com.auraboot.framework.meta.mapper.CommandDefinitionMapper commands;
    @Autowired com.auraboot.framework.meta.service.CommandExecutor commandExecutor;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper json;
    @TempDir Path files;
    private final List<Long> fieldIds = new ArrayList<>(), permissionIds = new ArrayList<>();
    private final List<Long> commandIds = new ArrayList<>();
    private String model, table, label, secret, hidden, quantity, pid;
    private Long modelId;
    private boolean physicalCreated;

    @BeforeEach void fixture(org.junit.jupiter.api.TestInfo testInfo) {
        String suffix = UniqueIdGenerator.generate().toLowerCase(Locale.ROOT);
        model = "fw_" + suffix; label = "label_" + suffix; secret = "secret_" + suffix; quantity = "qty_" + suffix; hidden = "hidden_" + suffix;
        Model definition = new Model();
        definition.setTenantId(testTenant.getId()); definition.setPid(UniqueIdGenerator.generate());
        definition.setCode(model); definition.setVersion(1); definition.setIsCurrent(true);
        definition.setStatus("published"); definition.setDeletedFlag(false);
        ExtensionBean extension = new ExtensionBean(); extension.setExtension(new HashMap<>(Map.of("modelType","entity","softDelete",testInfo.getTags().contains("soft-delete"))));
        definition.setExtension(extension); models.insert(definition); modelId = definition.getId();
        bind(label,"string",false,1); bind(secret,"integer",true,2); bind(quantity,"integer",false,3); bind(hidden,"integer",true,4);
        metadata.clearAllCache();
        var created = schema.createTableByModel(model);
        assertThat(created.isSuccess()).as(created.getErrorMessage()).isTrue(); physicalCreated = true;
        table = metadata.getModelDefinition(model).orElseThrow().getTableName();
        assertThat(table).isEqualTo("mt_"+model);
        for (String action : List.of("read","create","update")) {
            String code = "model."+model+"."+action;
            grantCommittedPermissionToTestRole(code,"data",model,action,"Native field write fixture");
            permissionIds.add(jdbc.queryForObject("SELECT id FROM ab_permission WHERE tenant_id=? AND code=?",
                    Long.class,testTenant.getId(),code));
        }
        var resolved = fieldPermissions.getFieldPermissions(testTenantMember.getId(),model);
        assertThat(resolved.viewableFields()).contains(label,secret,quantity).doesNotContain(hidden);
        assertThat(resolved.editableFields()).contains(label,quantity).doesNotContain(secret,hidden);
        pid = Objects.toString(data.create(model,new LinkedHashMap<>(Map.of(label,"before",quantity,1))).get("pid"));
        assertThat(pid).isNotBlank();
        // Fixture setup also proves a trusted derived root write retains its separate execution scope.
        MetaContext.runWithCommandPermitPlan("ALL",1L,model,pid,()->data.update(model,pid,Map.of(secret,10,hidden,17)));
        assertThat(MetaContext.hasCommandPermitScope()).isFalse();
        assertThat(stored(secret,pid)).isEqualTo(10);
    }

    private void bind(String code,String type,boolean readonly,int order) {
        Field field = new Field(); field.setTenantId(testTenant.getId()); field.setPid(UniqueIdGenerator.generate());
        field.setCode(code); field.setDataType(type); field.setVersion(1); field.setIsCurrent(true);
        field.setStatus("published"); field.setDeletedFlag(false);
        var feature = new FieldFeatureBean();
        if (code.equals(quantity)) feature.setDefaultValue(7);
        field.setFeature(feature);
        ExtensionBean extension = new ExtensionBean();
        Map<String,Object> rule = new HashMap<>();
        if (readonly) rule.put("edit",List.of("unassigned_"+model));
        if (code.equals(hidden)) rule.put("view",List.of("unassigned_"+model));
        extension.setExtension(new HashMap<>(readonly ? Map.of("fieldPermission",rule) : Map.of()));
        field.setExtension(extension); fields.insert(field); fieldIds.add(field.getId());
        ModelFieldBinding binding = new ModelFieldBinding(); binding.setTenantId(testTenant.getId());
        binding.setModelId(modelId); binding.setFieldId(field.getId()); binding.setFieldOrder(order); bindings.insert(binding);
    }

    private Object stored(String field,String record) {
        return jdbc.queryForObject("SELECT "+field+" FROM "+table+" WHERE pid=?",Object.class,record);
    }
    private long count() { return jdbc.queryForObject("SELECT count(*) FROM "+table,Long.class); }

    @AfterEach void cleanup() {
        applyTestMetaContext();
        org.mockito.Mockito.reset(extensions);
        jdbc.update("DELETE FROM ab_role_data_scope WHERE tenant_id=? AND role_id=? AND resource_code=?",testTenant.getId(),testRole.getId(),model);
        for (Long id : maskIds) masks.deleteConfig(id);
        for (Long id : commandIds) jdbc.update("DELETE FROM ab_command_definition WHERE tenant_id=? AND id=?",testTenant.getId(),id);
        if (physicalCreated) assertThat(schema.dropTableByModel(model).isSuccess()).isTrue();
        if (modelId != null) {
            jdbc.update("DELETE FROM ab_meta_model_field_binding WHERE tenant_id=? AND model_id=?",testTenant.getId(),modelId);
            jdbc.update("DELETE FROM ab_meta_model WHERE tenant_id=? AND id=?",testTenant.getId(),modelId);
        }
        for (Long id : fieldIds) jdbc.update("DELETE FROM ab_meta_field WHERE tenant_id=? AND id=?",testTenant.getId(),id);
        for (Long id : permissionIds) {
            jdbc.update("DELETE FROM ab_role_permission WHERE tenant_id=? AND permission_id=?",testTenant.getId(),id);
            jdbc.update("DELETE FROM ab_permission WHERE tenant_id=? AND id=?",testTenant.getId(),id);
        }
        metadata.clearAllCache();
    }

    @Test void nativeUpdateUsesItsOwnCompleteGrantFieldsAndRejectsTheWholeMixedWrite() throws Exception {
        String code = "model."+model+".update";
        String scope = json.writeValueAsString(Map.of("modelCode",model,"readFields",List.of(),"writeFields",List.of(label)));
        int changed = jdbc.update("UPDATE ab_role_permission g SET field_scope=?::jsonb FROM ab_permission p WHERE g.permission_id=p.id AND g.role_id=? AND g.tenant_id=? AND p.code=? AND g.deleted_flag=FALSE",
                scope,testRole.getId(),testTenant.getId(),code);
        assertThat(changed).isEqualTo(1);
        String before = jdbc.queryForObject("SELECT to_jsonb(g)::text FROM ab_role_permission g JOIN ab_permission p ON p.id=g.permission_id WHERE g.tenant_id=? AND g.role_id=? AND p.code=? AND g.deleted_flag=FALSE",String.class,testTenant.getId(),testRole.getId(),code);
        data.update(model,pid,Map.of(label,"allowed"));
        assertThat(stored(label,pid)).isEqualTo("allowed");
        assertThatThrownBy(() -> data.update(model,pid,Map.of(label,"must-rollback",quantity,9)))
                .isInstanceOf(AccessDeniedException.class).hasMessageContaining("FIELD_WRITE_DENIED");
        assertThat(stored(label,pid)).isEqualTo("allowed");assertThat(stored(quantity,pid)).isEqualTo(1);
        var batch = data.batchUpdate(model,List.of(new LinkedHashMap<>(Map.of("pid",pid,label,"mixed-batch-must-rollback",quantity,9)),
                new LinkedHashMap<>(Map.of("pid",pid,label,"batch-allowed"))));
        assertThat(batch.getFailed()).isEqualTo(1);assertThat(batch.getSuccess()).isEqualTo(1);
        assertThat(stored(label,pid)).isEqualTo("batch-allowed");assertThat(stored(quantity,pid)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT to_jsonb(g)::text FROM ab_role_permission g JOIN ab_permission p ON p.id=g.permission_id WHERE g.tenant_id=? AND g.role_id=? AND p.code=? AND g.deleted_flag=FALSE",String.class,testTenant.getId(),testRole.getId(),code)).isEqualTo(before);
    }

    private String restrictGrantFields(String action, List<String> readable, List<String> writable) throws Exception {
        String code = "model." + model + "." + action;
        String scope = json.writeValueAsString(Map.of("modelCode", model, "readFields", readable, "writeFields", writable));
        assertThat(jdbc.update("UPDATE ab_role_permission g SET field_scope=?::jsonb FROM ab_permission p WHERE g.permission_id=p.id AND g.role_id=? AND g.tenant_id=? AND p.code=? AND g.deleted_flag=FALSE",
                scope, testRole.getId(), testTenant.getId(), code)).isEqualTo(1);
        return jdbc.queryForObject("SELECT to_jsonb(g)::text FROM ab_role_permission g JOIN ab_permission p ON p.id=g.permission_id WHERE g.tenant_id=? AND g.role_id=? AND p.code=? AND g.deleted_flag=FALSE",
                String.class, testTenant.getId(), testRole.getId(), code);
    }

    @Test void bulkCreateRejectsTheWholeBatchWhenOneSubmittedFieldExceedsItsCreateGrant() throws Exception {
        restrictGrantFields("create", List.of(), List.of(label));
        long before = count();
        assertThatThrownBy(() -> data.bulkCreate(model, List.of(
                new LinkedHashMap<>(Map.of(label, "bulk-allowed")),
                new LinkedHashMap<>(Map.of(label, "bulk-forbidden", quantity, 9)))))
                .isInstanceOf(AccessDeniedException.class).hasMessageContaining("FIELD_WRITE_DENIED");
        assertThat(count()).isEqualTo(before);
    }

    @Test void bulkCreateProjectsEachResultThroughItsIndependentReadGrant() throws Exception {
        restrictGrantFields("create", List.of(), List.of(label, quantity));
        restrictGrantFields("read", List.of(label), List.of());
        var inputs = List.of(new LinkedHashMap<String, Object>(Map.of(label, "bulk-first", quantity, 3)),
                new LinkedHashMap<String, Object>(Map.of(label, "bulk-second", quantity, 4)));
        var result = data.bulkCreate(model, new ArrayList<Map<String, Object>>(inputs));
        assertThat(result).hasSize(2);
        for (int i = 0; i < result.size(); i++) {
            var row = result.get(i);
            assertThat(row).containsEntry(label, inputs.get(i).get(label))
                    .doesNotContainKeys(quantity, secret, hidden, "tenant_id", "created_by");
            assertThat(stored(quantity, Objects.toString(row.get("pid")))).isEqualTo(i + 3);
            assertThat(inputs.get(i)).containsOnlyKeys(label, quantity);
        }
    }

    @Test void bulkCreateWithoutReadGrantReturnsOnlyCreatedIdentities() throws Exception {
        restrictGrantFields("create", List.of(), List.of(label));
        assertThat(jdbc.update("UPDATE ab_role_permission g SET deleted_flag=TRUE FROM ab_permission p WHERE g.permission_id=p.id AND g.role_id=? AND g.tenant_id=? AND p.code=? AND g.deleted_flag=FALSE",
                testRole.getId(), testTenant.getId(), "model." + model + ".read")).isEqualTo(1);
        var result = data.bulkCreate(model, List.of(new LinkedHashMap<>(Map.of(label, "bulk-write-only"))));
        assertThat(result).hasSize(1);
        assertThat(result.getFirst()).containsKey("pid");
        assertThat(result.getFirst().keySet()).isSubsetOf("pid", "row_version");
        String createdPid = Objects.toString(result.getFirst().get("pid"));
        assertThat(stored(label, createdPid)).isEqualTo("bulk-write-only");
        assertThatThrownBy(() -> data.getById(model, createdPid)).isInstanceOf(AccessDeniedException.class);
    }

    @Test void jointSaveCreatesAnAuthorizedMasterAndProjectsItsPublicFields() throws Exception {
        restrictGrantFields("create", List.of(), List.of(label));
        restrictGrantFields("read", List.of(label), List.of());
        var result = data.saveWithRelations(model, com.auraboot.framework.meta.dto.JointSubTableSaveRequest.builder()
                .masterData(new LinkedHashMap<>(Map.of(label, "joint-created"))).build());
        assertThat(result.getSuccess()).as("joint errors: %s", result.getErrors()).isTrue();
        assertThat(result.getMasterRecord()).containsEntry(label, "joint-created")
                .doesNotContainKeys(quantity, secret, hidden, "tenant_id");
        assertThat(stored(label, result.getMasterId())).isEqualTo("joint-created");
        assertThat(stored(quantity, result.getMasterId())).isEqualTo(7);
        assertThat(count()).isEqualTo(2);
    }

    @Test void jointSaveUpdatesOnlyFieldsPermittedByTheMatchingUpdateGrant() throws Exception {
        restrictGrantFields("update", List.of(), List.of(label));
        restrictGrantFields("read", List.of(label), List.of());
        var allowed = data.saveWithRelations(model, com.auraboot.framework.meta.dto.JointSubTableSaveRequest.builder()
                .masterData(new LinkedHashMap<>(Map.of("pid", pid, label, "joint-updated"))).build());
        assertThat(allowed.getSuccess()).as("joint errors: %s", allowed.getErrors()).isTrue();
        assertThat(allowed.getMasterRecord()).containsEntry(label, "joint-updated").doesNotContainKeys(quantity, secret, hidden);
        var denied = data.saveWithRelations(model, com.auraboot.framework.meta.dto.JointSubTableSaveRequest.builder()
                .masterData(new LinkedHashMap<>(Map.of("pid", pid, label, "must-not-persist", quantity, 9))).build());
        assertThat(denied.getSuccess()).isFalse();
        assertThat(denied.getErrors()).anySatisfy(error -> assertThat(error).contains("FIELD_WRITE_DENIED"));
        assertThat(stored(label, pid)).isEqualTo("joint-updated");
        assertThat(stored(quantity, pid)).isEqualTo(1);
    }

    @Test void jointSaveWithoutReadGrantReturnsAnIdentityAcknowledgement() throws Exception {
        restrictGrantFields("create", List.of(), List.of(label));
        assertThat(jdbc.update("UPDATE ab_role_permission g SET deleted_flag=TRUE FROM ab_permission p WHERE g.permission_id=p.id AND g.role_id=? AND g.tenant_id=? AND p.code=? AND g.deleted_flag=FALSE",
                testRole.getId(), testTenant.getId(), "model." + model + ".read")).isEqualTo(1);
        var result = data.saveWithRelations(model, com.auraboot.framework.meta.dto.JointSubTableSaveRequest.builder()
                .masterData(new LinkedHashMap<>(Map.of(label, "joint-write-only"))).build());
        assertThat(result.getSuccess()).as("joint errors: %s", result.getErrors()).isTrue();
        assertThat(result.getMasterRecord()).containsEntry("pid", result.getMasterId());
        assertThat(result.getMasterRecord().keySet()).isSubsetOf("pid", "row_version");
        assertThat(stored(label, result.getMasterId())).isEqualTo("joint-write-only");
        assertThatThrownBy(() -> data.getById(model, result.getMasterId())).isInstanceOf(AccessDeniedException.class);
    }

    @Test void jointSaveRetainsAuthorizedRowsButRollsBackTheDeniedChildSavepoint() throws Exception {
        String children = "children_" + model;
        Field field = new Field();
        field.setTenantId(testTenant.getId()); field.setPid(UniqueIdGenerator.generate());
        field.setCode(children); field.setDataType("string"); field.setVersion(1); field.setIsCurrent(true);
        field.setStatus("published"); field.setDeletedFlag(false);
        var feature = new FieldFeatureBean(); feature.setVirtualType("transient"); field.setFeature(feature);
        var reference = new com.auraboot.framework.meta.entity.payload.FieldRefTargetBean();
        reference.setTargetEntity(model); reference.setTargetTable(table); reference.setTargetField(label); reference.setValueField("pid");
        var bidirectional = new com.auraboot.framework.meta.entity.payload.FieldRefTargetBean.BidirectionalConfig();
        bidirectional.setRelationType("ONE_TO_MANY"); reference.setBidirectional(bidirectional); field.setRefTarget(reference);
        fields.insert(field); fieldIds.add(field.getId());
        var binding = new ModelFieldBinding(); binding.setTenantId(testTenant.getId());
        binding.setModelId(modelId); binding.setFieldId(field.getId()); binding.setFieldOrder(5); bindings.insert(binding);
        metadata.clearAllCache();
        assertThat(metadata.getModelDefinition(model).orElseThrow().getRelations()).hasSize(1);
        restrictGrantFields("create", List.of(), List.of(label));
        restrictGrantFields("read", List.of(label), List.of());
        var result = data.saveWithRelations(model, com.auraboot.framework.meta.dto.JointSubTableSaveRequest.builder()
                .masterData(new LinkedHashMap<>(Map.of(label, "partial-master")))
                .tables(Map.of(children, List.of(Map.of(), Map.of(quantity, 9)))).build());
        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getMasterId()).isNotBlank();
        assertThat(stored(label, result.getMasterId())).isEqualTo("partial-master");
        assertThat(result.getSubTableCounts()).containsEntry(children, 1);
        assertThat(result.getSavedRecords().get(children)).singleElement().satisfies(child -> {
            assertThat(child).containsEntry(label, result.getMasterId()).doesNotContainKeys(quantity, secret, hidden);
            assertThat(stored(quantity, Objects.toString(child.get("pid")))).isEqualTo(7);
        });
        assertThat(result.getSubTableErrors().get(children)).singleElement().satisfies(error -> {
            assertThat(error.getRowIndex()).isEqualTo(1);
            assertThat(error.getMessage()).contains("FIELD_WRITE_DENIED");
        });
        assertThat(count()).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE " + quantity + "=9", Long.class)).isZero();
    }

    @Test void jointSaveCannotUpgradeAnExistingWeakWriteTransaction() {
        var outer = new org.springframework.transaction.support.TransactionTemplate(transactions);
        outer.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        outer.executeWithoutResult(status -> {
            var result = data.saveWithRelations(model, com.auraboot.framework.meta.dto.JointSubTableSaveRequest.builder()
                    .masterData(new LinkedHashMap<>(Map.of(label, "weak-must-not-persist"))).build());
            assertThat(result.getSuccess()).isFalse();
            assertThat(result.getErrors()).anySatisfy(error -> assertThat(error).contains("FIELD_WRITE_DENIED"));
        });
        assertThat(count()).isEqualTo(1);
    }

    @Test void conditionalWritesConsumeCompleteUpdateFieldsBeforeAnyMutation() throws Exception {
        String before = restrictGrantFields("update", List.of(), List.of(label));
        assertThat(data.compareAndSet(model, pid, label, "before", "cas-allowed")).isTrue();
        assertThatThrownBy(() -> data.compareAndSet(model, pid, label, "cas-allowed", Map.of(label, "must-not-persist", quantity, 9)))
                .isInstanceOf(AccessDeniedException.class).hasMessageContaining("FIELD_WRITE_DENIED");
        assertThat(stored(label, pid)).isEqualTo("cas-allowed");
        assertThat(stored(quantity, pid)).isEqualTo(1);
        assertThatThrownBy(() -> data.compareAndSetBatch(model, label, List.of(
                new com.auraboot.framework.plugin.extension.CompareAndSetUpdate(pid, "cas-allowed", Map.of(label, "batch-must-not-persist", quantity, 9)))))
                .isInstanceOf(AccessDeniedException.class).hasMessageContaining("FIELD_WRITE_DENIED");
        assertThat(stored(label, pid)).isEqualTo("cas-allowed");
        assertThat(stored(quantity, pid)).isEqualTo(1);
        data.compareAndSetBatch(model, label, List.of(
                new com.auraboot.framework.plugin.extension.CompareAndSetUpdate(pid, "cas-allowed", Map.of(label, "batch-allowed"))));
        assertThat(stored(label, pid)).isEqualTo("batch-allowed");
        assertThat(data.compareAndSet(model, pid, label, "stale", "must-not-persist")).isFalse();
        assertThat(stored(label, pid)).isEqualTo("batch-allowed");
        assertThat(jdbc.queryForObject("SELECT to_jsonb(g)::text FROM ab_role_permission g JOIN ab_permission p ON p.id=g.permission_id WHERE g.tenant_id=? AND g.role_id=? AND p.code=? AND g.deleted_flag=FALSE",
                String.class, testTenant.getId(), testRole.getId(), "model." + model + ".update")).isEqualTo(before);
    }

    @Test void conditionalComparisonsCannotBorrowReadableFieldsFromUpdateGrants() throws Exception {
        restrictGrantFields("read", List.of(label), List.of());
        restrictGrantFields("update", List.of(quantity), List.of(label, quantity));
        for (int expected : List.of(1, 99)) {
            assertThatThrownBy(() -> data.compareAndSet(model, pid, quantity, expected, Map.of(label, "inferred")))
                    .isInstanceOf(AccessDeniedException.class).hasMessageContaining("FIELD_READ_DENIED");
        }
        assertThatThrownBy(() -> data.compareAndSetBatch(model, quantity, List.of(
                new com.auraboot.framework.plugin.extension.CompareAndSetUpdate(pid, 1, Map.of(label, "inferred")))))
                .isInstanceOf(AccessDeniedException.class).hasMessageContaining("FIELD_READ_DENIED");
        assertThat(stored(label, pid)).isEqualTo("before");
        assertThat(stored(quantity, pid)).isEqualTo(1);
        assertThat(data.compareAndSet(model, pid, label, "before", "allowed")).isTrue();
    }

    @Test void nativeCounterRequiresBothWritableCounterAndReadableComparisonFields() throws Exception {
        restrictGrantFields("update", List.of(), List.of(label));
        assertThatThrownBy(() -> data.incrementWithinCap(model, pid, quantity, 1, null))
                .isInstanceOf(AccessDeniedException.class).hasMessageContaining("FIELD_WRITE_DENIED");
        assertThat(stored(quantity, pid)).isEqualTo(1);
        restrictGrantFields("update", List.of(), List.of(quantity));
        restrictGrantFields("read", List.of(quantity), List.of());
        assertThatThrownBy(() -> data.incrementWithinCap(model, pid, quantity, 1, secret))
                .isInstanceOf(AccessDeniedException.class).hasMessageContaining("FIELD_READ_DENIED");
        assertThat(stored(quantity, pid)).isEqualTo(1);
        assertThat(data.incrementWithinCap(model, pid, quantity, 1, null)).contains(2L);
        assertThat(stored(quantity, pid)).isEqualTo(2);
    }

    @Test void nativeUpdateReadbackUsesTheFieldGrantNarrowedInItsOwnTransaction() throws Exception {
        String fieldScope = json.writeValueAsString(Map.of("modelCode",model,"readFields",List.of(label),"writeFields",List.of()));
        var transaction = new org.springframework.transaction.support.TransactionTemplate(transactions);
        transaction.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        var updated = transaction.execute(status -> {
            assertThat(jdbc.update("UPDATE ab_role_permission g SET field_scope=?::jsonb FROM ab_permission p WHERE g.permission_id=p.id AND g.role_id=? AND g.tenant_id=? AND p.code=? AND g.deleted_flag=FALSE",
                    fieldScope,testRole.getId(),testTenant.getId(),"model."+model+".read")).isEqualTo(1);
            var result = data.update(model,pid,Map.of(label,"same-transaction"));
            assertThat(result).containsEntry(label,"same-transaction").containsEntry("pid",pid)
                    .doesNotContainKeys(secret,quantity,hidden);
            return result;
        });
        assertThat(updated).containsEntry(label,"same-transaction");
        assertThat(stored(label,pid)).isEqualTo("same-transaction");
        assertThat(stored(secret,pid)).isEqualTo(10);
        assertThat(stored(quantity,pid)).isEqualTo(1);
        assertThat(data.getById(model,pid)).containsEntry(label,"same-transaction").doesNotContainKeys(secret,quantity,hidden);
    }

    @Test void nativeCreateReadbackUsesItsOwnFieldGrantAndActualDefaultedRow() throws Exception {
        String fieldScope = json.writeValueAsString(Map.of("modelCode",model,"readFields",List.of(label),"writeFields",List.of()));
        var transaction = new org.springframework.transaction.support.TransactionTemplate(transactions);
        transaction.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        var created = transaction.execute(status -> {
            assertThat(jdbc.update("UPDATE ab_role_permission g SET field_scope=?::jsonb FROM ab_permission p WHERE g.permission_id=p.id AND g.role_id=? AND g.tenant_id=? AND p.code=? AND g.deleted_flag=FALSE",
                    fieldScope,testRole.getId(),testTenant.getId(),"model."+model+".read")).isEqualTo(1);
            var result = data.create(model,new LinkedHashMap<>(Map.of(label,"created-with-own-grant")));
            assertThat(result).containsEntry(label,"created-with-own-grant").containsKey("pid").doesNotContainKeys(secret,quantity,hidden);
            return result;
        });
        String createdPid = Objects.toString(created.get("pid"));
        assertThat(stored(label,createdPid)).isEqualTo("created-with-own-grant");
        assertThat(stored(quantity,createdPid)).isEqualTo(7);
        assertThat(data.getById(model,createdPid)).containsEntry(label,"created-with-own-grant").doesNotContainKeys(quantity);
    }

    @Test @org.junit.jupiter.api.Tag("soft-delete")
    void nativeSingleAndBatchDeleteRetainTheirAuthorizedReadWithinTheWriteTransaction() {
        assertThat(metadata.getModelDefinition(model).orElseThrow().isSoftDelete()).isTrue();
        String code = "model."+model+".delete";
        grantCommittedPermissionToTestRole(code,"data",model,"delete","Native delete fixture");
        permissionIds.add(jdbc.queryForObject("SELECT id FROM ab_permission WHERE tenant_id=? AND code=?",Long.class,testTenant.getId(),code));
        data.delete(model,pid);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM "+table+" WHERE pid=? AND deleted_flag=FALSE",Long.class,pid)).isZero();
        String first = Objects.toString(data.create(model,new LinkedHashMap<>(Map.of(label,"batch-delete-first"))).get("pid"));
        String second = Objects.toString(data.create(model,new LinkedHashMap<>(Map.of(label,"batch-delete-second"))).get("pid"));
        data.batchDelete(model,List.of(first,second));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM "+table+" WHERE pid IN (?,?) AND deleted_flag=FALSE",Long.class,first,second)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM "+table+" WHERE deleted_flag=TRUE",Long.class)).isEqualTo(3);
    }

    @Test void createOnlyActorReceivesAnEnvelopeWithoutLosingTheCreatedBusinessRow() {
        assertThat(jdbc.update("UPDATE ab_role_permission g SET status='inactive' FROM ab_permission p WHERE g.permission_id=p.id AND g.role_id=? AND g.tenant_id=? AND p.code=? AND g.deleted_flag=FALSE",
                testRole.getId(),testTenant.getId(),"model."+model+".read")).isEqualTo(1);
        var result = data.create(model,new LinkedHashMap<>(Map.of(label,"write-only-created")));
        assertThat(result).containsOnlyKeys("pid","row_version");
        String created = Objects.toString(result.get("pid"));
        assertThat(stored(label,created)).isEqualTo("write-only-created");
        assertThat(stored(quantity,created)).isEqualTo(7);
        assertThatThrownBy(() -> data.getById(model,created)).isInstanceOf(AccessDeniedException.class);
    }

    @Test void nativeCreateUsesTheActualDefaultedRowAndRejectsForbiddenFieldsWithoutInsertion() throws Exception {
        String code="model."+model+".create";
        String fieldScope=json.writeValueAsString(Map.of("modelCode",model,"readFields",List.of(),"writeFields",List.of(label)));
        String condition=json.writeValueAsString(com.auraboot.framework.decision.ast.ConditionNode.CompareNode.of(
                new com.auraboot.framework.decision.ast.Operand.PathOperand(com.auraboot.framework.decision.ast.Scope.RECORD,"data."+quantity,com.auraboot.framework.decision.ast.DataType.INTEGER),
                com.auraboot.framework.decision.ast.Operator.EQ,
                new com.auraboot.framework.decision.ast.Operand.LiteralOperand(7,com.auraboot.framework.decision.ast.DataType.INTEGER)));
        assertThat(jdbc.update("UPDATE ab_role_permission g SET field_scope=?::jsonb,condition_ast=?::jsonb FROM ab_permission p WHERE g.permission_id=p.id AND g.tenant_id=? AND g.role_id=? AND p.code=? AND g.deleted_flag=FALSE",fieldScope,condition,testTenant.getId(),testRole.getId(),code)).isEqualTo(1);
        scopes.setScope(testTenant.getId(),testRole.getId(),model,"create","self","MAX");
        String before=jdbc.queryForObject("SELECT to_jsonb(g)::text FROM ab_role_permission g JOIN ab_permission p ON p.id=g.permission_id WHERE g.tenant_id=? AND g.role_id=? AND p.code=? AND g.deleted_flag=FALSE",String.class,testTenant.getId(),testRole.getId(),code);
        var created=data.create(model,new LinkedHashMap<>(Map.of(label,"create-allowed")));
        String createdPid=Objects.toString(created.get("pid"));
        assertThat(stored(label,createdPid)).isEqualTo("create-allowed");assertThat(stored(quantity,createdPid)).isEqualTo(7);
        assertThat(count()).isEqualTo(2);
        assertThatThrownBy(() -> data.create(model,new LinkedHashMap<>(Map.of(label,"create-denied",quantity,7))))
                .isInstanceOf(AccessDeniedException.class).hasMessageContaining("FIELD_WRITE_DENIED");
        assertThat(count()).isEqualTo(2);
        var batch=data.batchCreate(model,List.of(new LinkedHashMap<>(Map.of(label,"batch-create-denied",quantity,7)),
                new LinkedHashMap<>(Map.of(label,"batch-create-allowed"))));
        assertThat(batch.getFailed()).isEqualTo(1);assertThat(batch.getSuccess()).isEqualTo(1);assertThat(count()).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM "+table+" WHERE "+label+" IN ('create-denied','batch-create-denied')",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT to_jsonb(g)::text FROM ab_role_permission g JOIN ab_permission p ON p.id=g.permission_id WHERE g.tenant_id=? AND g.role_id=? AND p.code=? AND g.deleted_flag=FALSE",String.class,testTenant.getId(),testRole.getId(),code)).isEqualTo(before);
    }

    @Test void createRejectsReadonlyValuesAndNullWithoutInserting() {
        Map<String,Object> attempt = new LinkedHashMap<>(); attempt.put(label,"denied"); attempt.put(secret,null);
        assertThatThrownBy(()->data.create(model,attempt)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(()->data.create(model,new LinkedHashMap<>(Map.of(label,"denied",secret,11))))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(count()).isEqualTo(1);
    }

    @Test void updateRejectsChangesAndNullButPreservesUnchangedVisibleRoundtrips() {
        assertThatThrownBy(()->data.update(model,pid,Map.of(secret,11))).isInstanceOf(AccessDeniedException.class);
        Map<String,Object> clear = new LinkedHashMap<>(); clear.put(secret,null);
        assertThatThrownBy(()->data.update(model,pid,clear)).isInstanceOf(AccessDeniedException.class);
        data.update(model,pid,Map.of(secret,10,label,"after"));
        assertThat(stored(secret,pid)).isEqualTo(10); assertThat(stored(label,pid)).isEqualTo("after");
    }

    @Test void hiddenNullCannotBeSilentlyIgnoredAsAnUnchangedValue() {
        Map<String,Object> clear = new LinkedHashMap<>(); clear.put(hidden,null);
        assertThatThrownBy(()->data.update(model,pid,clear)).isInstanceOf(AccessDeniedException.class);
        assertThat(stored(hidden,pid)).isEqualTo(17);
    }

    @Test void compareAndSetRejectsReadonlyButPersistsAnAllowedField() {
        assertThatThrownBy(()->data.compareAndSet(model,pid,secret,10,11)).isInstanceOf(AccessDeniedException.class);
        assertThat(data.compareAndSet(model,pid,label,"before","after-cas")).isTrue();
        assertThat(stored(secret,pid)).isEqualTo(10); assertThat(stored(label,pid)).isEqualTo("after-cas");
    }

    @Test void hiddenComparisonCannotRevealEitherMatchingOrNonmatchingValues() {
        for (int expected : List.of(17,99)) {
            assertThatThrownBy(()->data.compareAndSet(model,pid,hidden,expected,Map.of(label,"inferred")))
                    .isInstanceOf(AccessDeniedException.class).hasMessageContaining("FIELD_READ_DENIED");
        }
        assertThat(stored(label,pid)).isEqualTo("before");
        assertThat(stored(hidden,pid)).isEqualTo(17);
        assertThatThrownBy(()->data.compareAndSetBatch(model,hidden,List.of(
                new com.auraboot.framework.plugin.extension.CompareAndSetUpdate(pid,17,Map.of(label,"inferred")))))
                .isInstanceOf(AccessDeniedException.class).hasMessageContaining("FIELD_READ_DENIED");
        assertThat(stored(label,pid)).isEqualTo("before");
    }

    @Test void hiddenCapCannotBeProbedButVisibleReadonlyCapAllowsBoundedIncrement() {
        assertThatThrownBy(()->data.incrementWithinCap(model,pid,quantity,1,hidden))
                .isInstanceOf(AccessDeniedException.class).hasMessageContaining("FIELD_READ_DENIED");
        assertThat(stored(quantity,pid)).isEqualTo(1);
        assertThat(data.incrementWithinCap(model,pid,quantity,1,secret)).contains(2L);
        assertThat(stored(quantity,pid)).isEqualTo(2);
        assertThat(stored(secret,pid)).isEqualTo(10);
    }

    @Test void writableMaskedCounterCannotReturnItsRawNewValue() {
        var config = new com.auraboot.framework.meta.entity.FieldMaskConfig();
        config.setModelCode(model); config.setFieldCode(quantity); config.setEnabled(true);
        config.setApplyToDetail(true); config.setApplyToList(true); config.setApplyToExport(true);
        config.setMaskType("FULL"); config.setReplacementChar("*");
        masks.saveConfig(config); maskIds.add(config.getId());
        assertThat(fieldPermissions.getFieldPermissions(testTenantMember.getId(),model).editableFields()).contains(quantity);
        assertThat(masks.getEffectiveConfigs(model,testUser.getId(),"detail")).hasSize(1);
        assertThatThrownBy(()->data.incrementWithinCap(model,pid,quantity,1,null))
                .isInstanceOf(AccessDeniedException.class).hasMessageContaining("FIELD_READ_DENIED");
        assertThat(stored(quantity,pid)).isEqualTo(1);
        assertThatThrownBy(()->data.compareAndSet(model,pid,quantity,1,Map.of(label,"inferred")))
                .isInstanceOf(AccessDeniedException.class).hasMessageContaining("FIELD_READ_DENIED");
        assertThat(stored(label,pid)).isEqualTo("before");
        assertThat(count()).isEqualTo(1);
        masks.deleteConfig(config.getId()); maskIds.remove(config.getId());
        assertThat(data.incrementWithinCap(model,pid,quantity,1,null)).contains(2L);
        assertThat(stored(quantity,pid)).isEqualTo(2);
    }

    @Test void bulkCreateRejectsTheWholeWriteAndAllowsUnrestrictedRows() {
        assertThatThrownBy(()->data.bulkCreate(model,List.of(new LinkedHashMap<>(Map.of(label,"valid")),
                new LinkedHashMap<>(Map.of(label,"denied",secret,11))))).isInstanceOf(AccessDeniedException.class);
        assertThat(count()).isEqualTo(1);
        assertThat(data.bulkCreate(model,List.of(new LinkedHashMap<>(Map.of(label,"one")),
                new LinkedHashMap<>(Map.of(label,"two"))))).hasSize(2);
        assertThat(count()).isEqualTo(3);
    }

    @Test void countersRespectReadonlyFieldsAndPersistAllowedIncrements() {
        assertThatThrownBy(()->data.incrementWithinCap(model,pid,secret,1,null)).isInstanceOf(AccessDeniedException.class);
        assertThat(data.incrementWithinCap(model,pid,quantity,1,null)).contains(2L);
        assertThat(stored(secret,pid)).isEqualTo(10); assertThat(stored(quantity,pid)).isEqualTo(2);
    }

    @Test void batchCreateCannotReplayAForbiddenExistingPidAsSuccess() {
        var result = data.batchCreate(model,List.of(new LinkedHashMap<>(Map.of("pid",pid,secret,11)),
                new LinkedHashMap<>(Map.of(label,"batch-ok"))));
        assertThat(result.getFailed()).isEqualTo(1); assertThat(result.getSuccess()).isEqualTo(1);
        assertThat(result.getErrors()).anySatisfy(error->assertThat(error).contains("FIELD_WRITE_DENIED"));
        assertThat(stored(secret,pid)).isEqualTo(10); assertThat(count()).isEqualTo(2);
    }

    @Test void jsonImportPersistsOnlyAuthorizedRows() throws Exception {
        Path input = files.resolve("fields.json");
        Files.writeString(input,"[{\""+label+"\":\"import-ok\"},{\""+label+"\":\"import-denied\",\""+secret+"\":11}]");
        var result = data.importData(model,DataImportRequest.builder().filePath(input.toString())
                .format(DataImportRequest.ImportFormat.JSON).build());
        assertThat(result.getSuccess()).isFalse(); assertThat(result.getSuccessCount()).isEqualTo(1);
        assertThat(result.getFailedCount()).isEqualTo(1);
        assertThat(result.getErrors()).anySatisfy(error->assertThat(error.getErrorMessage()).contains("FIELD_WRITE_DENIED"));
        assertThat(count()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM "+table+" WHERE "+label+"='import-denied'",Long.class)).isZero();
    }

    private String resultCommand(java.util.concurrent.atomic.AtomicInteger executions) throws Exception {
        return resultCommand(executions,false);
    }
    private String resultCommand(java.util.concurrent.atomic.AtomicInteger executions,boolean invalidResult) throws Exception {
        return resultCommand(executions, invalidResult, false);
    }
    private String resultCommand(java.util.concurrent.atomic.AtomicInteger executions,boolean invalidResult,boolean publicOutput) throws Exception {
        String code=model+":inspect_result",permission="model."+model+".inspect";
        grantCommittedPermissionToTestRole(permission,"operation",model,"inspect","Business output fixture");
        permissionIds.add(jdbc.queryForObject("SELECT id FROM ab_permission WHERE tenant_id=? AND code=?",Long.class,testTenant.getId(),permission));
        grantCommittedPermissionToTestRole(com.auraboot.framework.permission.constants.MetaPermission.COMMAND_EXECUTE,
                "API","command","execute","Command fixture invocation");
        scopes.setScope(testTenant.getId(),testRole.getId(),model,"inspect","all","MAX");
        var handler=new ResultOwner(code,permission,model,label,secret,hidden,quantity,executions,invalidResult,publicOutput);
        org.mockito.Mockito.doReturn(Optional.of(handler)).when(extensions).getCommandHandler(code);
        var definition=new com.auraboot.framework.meta.entity.CommandDefinition();
        definition.setTenantId(testTenant.getId());definition.setPid(UniqueIdGenerator.generate());definition.setCode(code);
        definition.setModelCode(model);definition.setDisplayName("Result projection fixture");definition.setVersion(1);
        definition.setIsCurrent(true);definition.setStatus("published");definition.setDeletedFlag(false);
        definition.setInputSchema("{}");definition.setTargetModels("[]");definition.setCmdRiskLevel("L1");
        definition.setExecutionConfig(json.writeValueAsString(Map.of("type","custom","handler",code,"cacheResult",true,
                "permissions",List.of(permission),"handlerParams",Map.of("targetRecordAuthorization","business","targetRecordAction","update","dslPersistence",false))));
        commands.insert(definition);commandIds.add(definition.getId());return code;
    }

    private void resultReadFields(List<String> readable) throws Exception {
        jdbc.update("UPDATE ab_role_permission SET field_scope=?::jsonb WHERE tenant_id=? AND role_id=? AND permission_id=?",
                json.writeValueAsString(Map.of("modelCode",model,"readFields",readable,"writeFields",List.of())),
                testTenant.getId(),testRole.getId(),permissionIds.getLast());
    }

    private com.auraboot.framework.meta.dto.CommandExecuteRequest resultRequest() {
        var request=request(Map.of());request.setOperationType("update");request.setTargetRecordId(pid);
        request.setExpectedVersion(jdbc.queryForObject("SELECT row_version FROM "+table+" WHERE pid=?",Integer.class,pid));return request;
    }

    @Test void publicResultUsesCurrentGrantOnActualReplayWithoutRepeatingTheMutation() throws Exception {
        var executions=new java.util.concurrent.atomic.AtomicInteger();String command=resultCommand(executions);
        resultReadFields(List.of());var request=resultRequest();
        var first=commandExecutor.execute(command,request);
        assertThat(first.isIdempotentReplay()).isFalse();assertThat(first.getData()).containsEntry("success",true).doesNotContainKeys("visibleValue","hiddenValue");
        assertThat(stored(label,pid)).isEqualTo("executed-1");
        assertThat(jdbc.queryForObject("SELECT (outcome->>'visibleValue')::int FROM ab_idempotency_record WHERE tenant_id=? AND command_code=? AND client_request_id=?",
                Integer.class,testTenant.getId(),command,command+":"+request.getClientRequestId())).isEqualTo(10);
        resultReadFields(List.of(secret,hidden));
        var replay=commandExecutor.execute(command,request);
        assertThat(replay.isIdempotentReplay()).isTrue();assertThat(replay.getData()).containsEntry("visibleValue",10).doesNotContainKey("hiddenValue");
        resultReadFields(List.of());var revoked=commandExecutor.execute(command,request);
        assertThat(revoked.isIdempotentReplay()).isTrue();assertThat(revoked.getData()).doesNotContainKeys("visibleValue","hiddenValue");
        assertThat(executions).hasValue(1);assertThat(stored(label,pid)).isEqualTo("executed-1");assertThat(stored(hidden,pid)).isEqualTo(17);
    }

    @Test void cachedPublicResultAppliesCurrentDetailMaskAndRestoresOnlyAfterRemoval() throws Exception {
        var executions=new java.util.concurrent.atomic.AtomicInteger();String command=resultCommand(executions);resultReadFields(List.of(secret));
        var config=new com.auraboot.framework.meta.entity.FieldMaskConfig();config.setModelCode(model);config.setFieldCode(secret);
        config.setEnabled(true);config.setApplyToDetail(true);config.setMaskType("FULL");config.setReplacementChar("*");
        masks.saveConfig(config);maskIds.add(config.getId());var request=resultRequest();
        assertThat(commandExecutor.execute(command,request).getData()).containsEntry("visibleValue","**").doesNotContainKey("hiddenValue");
        masks.deleteConfig(config.getId());maskIds.remove(config.getId());
        var replay=commandExecutor.execute(command,request);assertThat(replay.isIdempotentReplay()).isTrue();
        assertThat(replay.getData()).containsEntry("visibleValue",10).doesNotContainKey("hiddenValue");assertThat(executions).hasValue(1);
        assertThat(jdbc.queryForObject("SELECT (outcome->>'visibleValue')::int FROM ab_idempotency_record WHERE tenant_id=? AND command_code=? AND client_request_id=?",
                Integer.class,testTenant.getId(),command,command+":"+request.getClientRequestId())).isEqualTo(10);
    }

    @Test void artifactPolicyChangeConflictsBeforeReplayAndRestorationReusesTheOriginalOutcome() throws Exception {
        var executions = new java.util.concurrent.atomic.AtomicInteger(); String command = resultCommand(executions, false, true);
        resultReadFields(List.of(secret)); var request = resultRequest();
        var first = commandExecutor.execute(command, request);
        assertThat(first.isIdempotentReplay()).isFalse();
        assertThat(first.getData()).containsEntry("visibleValue", 10);
        String outcome = jdbc.queryForObject("SELECT outcome::text FROM ab_idempotency_record WHERE tenant_id=? AND command_code=? AND client_request_id=?",
                String.class, testTenant.getId(), command, command + ":" + request.getClientRequestId());
        var config = new com.auraboot.framework.meta.entity.FieldMaskConfig(); config.setModelCode(model); config.setFieldCode(secret);
        config.setEnabled(true); config.setApplyToDetail(false); config.setApplyToList(false); config.setApplyToExport(true);
        config.setMaskType("FULL"); config.setReplacementChar("*"); masks.saveConfig(config); maskIds.add(config.getId());
        assertThatThrownBy(() -> commandExecutor.execute(command, request))
                .isInstanceOf(com.auraboot.framework.exception.RequestIntentConflictException.class);
        assertThat(executions).hasValue(1);
        assertThat(stored(label, pid)).isEqualTo("executed-1");
        assertThat(jdbc.queryForObject("SELECT outcome::text FROM ab_idempotency_record WHERE tenant_id=? AND command_code=? AND client_request_id=?",
                String.class, testTenant.getId(), command, command + ":" + request.getClientRequestId())).isEqualTo(outcome);
        masks.deleteConfig(config.getId()); maskIds.remove(config.getId());
        var replay = commandExecutor.execute(command, request);
        assertThat(replay.isIdempotentReplay()).isTrue();
        assertThat(replay.getData()).containsEntry("visibleValue", 10);
        assertThat(executions).hasValue(1);
    }

    @Test void strongIsolationDryRunRollsBackTheRealMutationAndIdempotencyClaim() throws Exception {
        var executions = new java.util.concurrent.atomic.AtomicInteger(); String command = resultCommand(executions, false, true);
        resultReadFields(List.of(secret)); var request = resultRequest(); request.setDryRun(true);
        var result = commandExecutor.execute(command, request);
        assertThat(result.getPhaseReached()).isEqualTo("completed_dry_run");
        assertThat(executions).hasValue(1);
        assertThat(stored(label, pid)).isEqualTo("before");
        assertThat(stored(secret, pid)).isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ab_idempotency_record WHERE tenant_id=? AND command_code=? AND client_request_id=?",
                Long.class, testTenant.getId(), command, command + ":" + request.getClientRequestId())).isZero();
    }

    @Test void invalidAcknowledgementRollsBackMutationAndScopedOutcomeClaim() throws Exception {
        var executions=new java.util.concurrent.atomic.AtomicInteger();String command=resultCommand(executions,true);resultReadFields(List.of(secret));
        var request=resultRequest();
        assertThatThrownBy(()->commandExecutor.execute(command,request)).isInstanceOf(AccessDeniedException.class);
        assertThat(stored(label,pid)).isEqualTo("before");assertThat(stored(secret,pid)).isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ab_idempotency_record WHERE tenant_id=? AND command_code=? AND client_request_id=?",
                Long.class,testTenant.getId(),command,command+":"+request.getClientRequestId())).isZero();
        assertThat(executions).hasValue(1);
    }

    private record ResultOwner(String code,String permission,String model,String label,String secret,String hidden,String quantity,
            java.util.concurrent.atomic.AtomicInteger executions,boolean invalidResult,boolean publicOutput)
            implements com.auraboot.framework.plugin.extension.CommandHandlerExtension,
                       com.auraboot.framework.plugin.extension.BusinessResourceAuthorization {
        @Override public String getCommandType(){return code;}
        @Override public boolean supportsDryRun(){return true;}
        @Override public Boundary businessBoundary(){return new Boundary(code,model,"update",Set.of(permission));}
        @Override public Set<String> businessResourceModels(){return Set.of(model);}
        @Override public ReadIsolation resourceReadIsolation(){return publicOutput ? ReadIsolation.REPEATABLE_READ : null;}
        @Override public Map<String,Set<String>> publicBusinessResourceFields(){return publicOutput ? Map.of(model,Set.of(secret)) : Map.of();}
        @Override public Set<String> businessResultFields(){return Set.of("success","visibleValue","hiddenValue");}
        @Override public Map<String,com.auraboot.framework.plugin.extension.BusinessResultField> businessResultProjection(){
            return Map.of("success",com.auraboot.framework.plugin.extension.BusinessResultField.constant(true),
                    "visibleValue",com.auraboot.framework.plugin.extension.BusinessResultField.rootField(secret),
                    "hiddenValue",com.auraboot.framework.plugin.extension.BusinessResultField.rootField(hidden));
        }
        @Override public Grant authorizeResource(TargetAuthorizationContext root,Request request) {
            if(!model.equals(request.modelCode()) || !root.recordId().equals(request.recordId()))return null;
            Set<String> read=Set.of("pid","tenant_id","row_version","id",label,secret,hidden,quantity);
            return switch(request.action()) {
                case READ -> new Grant(read,Set.of(),1);
                case UPDATE -> new Grant(read,Set.of(label),1);
                default -> null;
            };
        }
        @Override public Object execute(CommandContext context) {
            var record=context.dataAccessor().getById(model,context.recordId());
            if (publicOutput) assertThat(context.dataAccessor().projectForPublicOutput(model, List.of(context.recordId())).get(context.recordId()))
                    .containsOnlyKeys(secret).containsEntry(secret, record.get(secret));
            context.dataAccessor().update(model,context.recordId(),Map.of(label,"executed-"+executions.incrementAndGet()));
            return Map.of("success",!invalidResult,"visibleValue",record.get(secret),"hiddenValue",record.get(hidden));
        }
    }

    @Test void nativeBusinessCreationChecksTheRealInsertRollsBackClaimsAndReauthorizesReplay() throws Exception {
        String code = model + ":native_create", permission = "fixture." + model + ".manage";
        grantCommittedPermissionToTestRole(permission, "operation", model, "manage", "Bounded native creation");
        long permissionId = jdbc.queryForObject("SELECT id FROM ab_permission WHERE tenant_id=? AND code=?", Long.class,
                testTenant.getId(), permission); permissionIds.add(permissionId);
        grantCommittedPermissionToTestRole(com.auraboot.framework.permission.constants.MetaPermission.COMMAND_EXECUTE,
                "API", "command", "execute", "Native creation invocation");
        scopes.setScope(testTenant.getId(), testRole.getId(), model, "manage", "all", "MAX");
        String predicate = json.writeValueAsString(com.auraboot.framework.decision.ast.ConditionNode.CompareNode.of(
                new com.auraboot.framework.decision.ast.Operand.PathOperand(com.auraboot.framework.decision.ast.Scope.RECORD,
                        "data." + quantity, com.auraboot.framework.decision.ast.DataType.INTEGER),
                com.auraboot.framework.decision.ast.Operator.EQ,
                new com.auraboot.framework.decision.ast.Operand.LiteralOperand(3, com.auraboot.framework.decision.ast.DataType.INTEGER)));
        jdbc.execute("ALTER TABLE " + table + " ALTER COLUMN " + secret + " SET DEFAULT 10");
        var defaultPredicate = com.auraboot.framework.decision.ast.ConditionNode.CompareNode.of(
                new com.auraboot.framework.decision.ast.Operand.PathOperand(com.auraboot.framework.decision.ast.Scope.RECORD,
                        "data." + secret, com.auraboot.framework.decision.ast.DataType.INTEGER),
                com.auraboot.framework.decision.ast.Operator.EQ,
                new com.auraboot.framework.decision.ast.Operand.LiteralOperand(10, com.auraboot.framework.decision.ast.DataType.INTEGER));
        predicate = json.writeValueAsString(new com.auraboot.framework.decision.ast.ConditionNode.GroupNode(
                com.auraboot.framework.decision.ast.ConditionNode.BoolOp.AND,
                List.of(json.readValue(predicate, com.auraboot.framework.decision.ast.ConditionNode.class), defaultPredicate)));
        String fieldScope = json.writeValueAsString(Map.of("modelCode", model, "readFields", List.of(),
                "writeFields", List.of(label, quantity)));
        jdbc.update("UPDATE ab_role_permission SET condition_ast=?::jsonb,field_scope=?::jsonb,priority=37,source_ref=? WHERE tenant_id=? AND role_id=? AND permission_id=?",
                predicate, fieldScope, code + ":source", testTenant.getId(), testRole.getId(), permissionId);
        var definition = new com.auraboot.framework.meta.entity.CommandDefinition();
        definition.setTenantId(testTenant.getId()); definition.setPid(UniqueIdGenerator.generate()); definition.setCode(code);
        definition.setModelCode(model); definition.setDisplayName("Native creation boundary"); definition.setVersion(1);
        definition.setIsCurrent(true); definition.setStatus("published"); definition.setDeletedFlag(false);
        definition.setInputSchema("{}"); definition.setTargetModels("[]"); definition.setCmdRiskLevel("L1");
        definition.setExecutionConfig(json.writeValueAsString(Map.of("type", "create", "permissions", List.of(permission),
                "inputFields", List.of(label, quantity), "handlerParams", Map.of("targetRecordAuthorization", "business", "targetRecordAction", "create"))));
        commands.insert(definition); commandIds.add(definition.getId());
        String originalGrant = jdbc.queryForObject("SELECT to_jsonb(g)::text FROM ab_role_permission g WHERE tenant_id=? AND role_id=? AND permission_id=?",
                String.class, testTenant.getId(), testRole.getId(), permissionId);
        long before = count();
        var denied = request(Map.of(label, "denied-native", quantity, 4));
        assertThatThrownBy(() -> commandExecutor.execute(code, denied)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThat(count()).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ab_idempotency_record WHERE tenant_id=? AND command_code=? AND client_request_id=?",
                Long.class, testTenant.getId(), code, denied.getClientRequestId())).isZero();
        jdbc.execute("ALTER TABLE " + table + " ALTER COLUMN " + secret + " SET DEFAULT 11");
        assertThatThrownBy(() -> commandExecutor.execute(code, request(Map.of(label, "default-denied", quantity, 3))))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThat(count()).isEqualTo(before);
        jdbc.execute("ALTER TABLE " + table + " ALTER COLUMN " + secret + " SET DEFAULT 10");
        var allowed = request(Map.of(label, "allowed-native", quantity, "3"));
        allowed.setClientRequestId(denied.getClientRequestId());
        var result = commandExecutor.execute(code, allowed);
        String created = Objects.toString(result.getData().get("recordPid"));
        assertThat(result.getData()).containsOnlyKeys("recordPid", model + "_inserted");
        assertThat(stored(quantity, created)).isEqualTo(3);
        assertThat(stored(secret, created)).isEqualTo(10);
        assertThat(count()).isEqualTo(before + 1);
        assertThat(jdbc.queryForObject("SELECT created_by FROM " + table + " WHERE pid=?", Long.class, created)).isEqualTo(testUser.getId());
        assertThat(commandExecutor.execute(code, allowed).isIdempotentReplay()).isTrue();
        assertThat(count()).isEqualTo(before + 1);
        scopes.setScope(testTenant.getId(), testRole.getId(), model, "manage", "none", "MAX");
        assertThatThrownBy(() -> commandExecutor.execute(code, allowed)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThat(count()).isEqualTo(before + 1);
        scopes.setScope(testTenant.getId(), testRole.getId(), model, "manage", "all", "MAX");
        String blockedFields = json.writeValueAsString(Map.of("modelCode", model, "readFields", List.of(), "writeFields", List.of(label)));
        jdbc.update("UPDATE ab_role_permission SET field_scope=?::jsonb WHERE tenant_id=? AND role_id=? AND permission_id=?",
                blockedFields, testTenant.getId(), testRole.getId(), permissionId);
        assertThatThrownBy(() -> commandExecutor.execute(code, request(Map.of(label, "field-denied", quantity, 3))))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThat(count()).isEqualTo(before + 1);
        assertThatThrownBy(() -> commandExecutor.execute(code, allowed)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        jdbc.update("UPDATE ab_role_permission SET field_scope=?::jsonb WHERE tenant_id=? AND role_id=? AND permission_id=?",
                fieldScope, testTenant.getId(), testRole.getId(), permissionId);
        assertThat(jdbc.queryForObject("SELECT to_jsonb(g)::text FROM ab_role_permission g WHERE tenant_id=? AND role_id=? AND permission_id=?",
                String.class, testTenant.getId(), testRole.getId(), permissionId)).isEqualTo(originalGrant);
    }

    private String derivedCommand(boolean explicitInputs,boolean preserveInput) throws Exception {
        grantCommittedPermissionToTestRole(com.auraboot.framework.permission.constants.MetaPermission.COMMAND_EXECUTE,
                "API","command","execute","Command fixture invocation");
        String code = model+":derived";
        var definition = new com.auraboot.framework.meta.entity.CommandDefinition();
        definition.setTenantId(testTenant.getId()); definition.setPid(UniqueIdGenerator.generate()); definition.setCode(code);
        definition.setModelCode(model); definition.setDisplayName("Input origin fixture"); definition.setVersion(1);
        definition.setIsCurrent(true); definition.setStatus("published"); definition.setDeletedFlag(false);
        definition.setInputSchema("{}"); definition.setTargetModels("[]"); definition.setCmdRiskLevel("L1");
        Map<String,Object> configuration = new LinkedHashMap<>(Map.of("type","create","cacheResult",true,
                "permissions",List.of("model."+model+".create"),"autoSetFields",Map.of(secret,
                        Map.of("strategy","default_value","value",10,"preserveInput",preserveInput))));
        if (explicitInputs) configuration.put("inputFields",List.of(label));
        definition.setExecutionConfig(json.writeValueAsString(configuration)); commands.insert(definition); commandIds.add(definition.getId());
        return code;
    }

    private com.auraboot.framework.meta.dto.CommandExecuteRequest request(Map<String,Object> input) {
        var request = new com.auraboot.framework.meta.dto.CommandExecuteRequest();
        request.setPayload(input); request.setOperationType("create"); request.setClientRequestId(UniqueIdGenerator.generate());
        return request;
    }

    private void secretEditable(boolean allowed) {
        Field definition = fields.selectById(fieldIds.get(1));
        ExtensionBean extension = new ExtensionBean(); extension.setExtension(new HashMap<>(Map.of(
                "fieldPermission",Map.of("edit",List.of(allowed?testRole.getCode():"unassigned_"+model)))));
        definition.setExtension(extension); fields.updateById(definition); metadata.clearAllCache();
    }

    @Test void fullCommandPersistsReadonlyOwnerDefaultsWithoutChangingTheOriginalInput() throws Exception {
        String command = derivedCommand(false,false);
        var request = request(Map.of(label,"derived-command"));
        var result = commandExecutor.execute(command,request);
        assertThat(result.getPhaseReached()).isEqualTo("completed");
        assertThat(result.isIdempotentReplay()).isFalse();
        assertThat(request.getPayload()).containsExactly(Map.entry(label,"derived-command"));
        assertThat(jdbc.queryForObject("SELECT "+secret+" FROM "+table+" WHERE "+label+"='derived-command'",Integer.class)).isEqualTo(10);
        assertThat(count()).isEqualTo(2);
    }

    @Test void submittedReadonlyAutoSetInputIsRejectedEvenOutsideDeclaredInputFields() throws Exception {
        String command = derivedCommand(true,true);
        assertThatThrownBy(()->commandExecutor.execute(command,request(Map.of(label,"forbidden-command",secret,99))))
                .isInstanceOf(com.auraboot.framework.exception.BusinessException.class)
                .satisfies(error->assertThat(((com.auraboot.framework.exception.BusinessException)error).getResponseCode())
                        .isEqualTo(com.auraboot.framework.common.constant.ResponseCode.FORBIDDEN));
        assertThat(count()).isEqualTo(1);
    }

    @Test void fieldRevocationRejectsAnActuallyCachedAutoSetOverrideBeforeReplay() throws Exception {
        secretEditable(true);
        String command = derivedCommand(true,true);
        var request = request(Map.of(label,"cached-command",secret,11));
        assertThat(commandExecutor.execute(command,request).isIdempotentReplay()).isFalse();
        assertThat(commandExecutor.execute(command,request).isIdempotentReplay()).isTrue();
        secretEditable(false);
        assertThatThrownBy(()->commandExecutor.execute(command,request))
                .isInstanceOf(com.auraboot.framework.exception.BusinessException.class)
                .satisfies(error->assertThat(((com.auraboot.framework.exception.BusinessException)error).getResponseCode())
                        .isEqualTo(com.auraboot.framework.common.constant.ResponseCode.FORBIDDEN));
        assertThat(jdbc.queryForObject("SELECT "+secret+" FROM "+table+" WHERE "+label+"='cached-command'",Integer.class)).isEqualTo(11);
        assertThat(count()).isEqualTo(2);
    }
}

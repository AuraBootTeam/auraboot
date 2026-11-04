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
    @Autowired com.auraboot.framework.meta.mapper.CommandDefinitionMapper commands;
    @Autowired com.auraboot.framework.meta.service.CommandExecutor commandExecutor;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper json;
    @TempDir Path files;
    private final List<Long> fieldIds = new ArrayList<>(), permissionIds = new ArrayList<>();
    private final List<Long> commandIds = new ArrayList<>();
    private String model, table, label, secret, hidden, quantity, pid;
    private Long modelId;
    private boolean physicalCreated;

    @BeforeEach void fixture() {
        String suffix = UniqueIdGenerator.generate().toLowerCase(Locale.ROOT);
        model = "fw_" + suffix; label = "label_" + suffix; secret = "secret_" + suffix; quantity = "qty_" + suffix; hidden = "hidden_" + suffix;
        Model definition = new Model();
        definition.setTenantId(testTenant.getId()); definition.setPid(UniqueIdGenerator.generate());
        definition.setCode(model); definition.setVersion(1); definition.setIsCurrent(true);
        definition.setStatus("published"); definition.setDeletedFlag(false);
        ExtensionBean extension = new ExtensionBean(); extension.setExtension(new HashMap<>(Map.of("modelType","entity")));
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
        field.setStatus("published"); field.setDeletedFlag(false); field.setFeature(new FieldFeatureBean());
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

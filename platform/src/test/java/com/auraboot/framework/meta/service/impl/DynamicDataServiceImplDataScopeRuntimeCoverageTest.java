package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.file.service.FileService;
import com.auraboot.framework.meta.ddl.TableMetadataService;
import com.auraboot.framework.meta.dto.ActionExecutionResult;
import com.auraboot.framework.meta.dto.DynamicQueryRequest;
import com.auraboot.framework.meta.dto.DynamicBatchResponse;
import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.framework.meta.dto.QueryValidationResult;
import com.auraboot.framework.meta.dto.RelationDefinition;
import com.auraboot.framework.meta.exception.MetaServiceException;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.mapper.MetaModelMapper;
import com.auraboot.framework.meta.service.ChangeTracker;
import com.auraboot.framework.meta.service.DataDomainService;
import com.auraboot.framework.meta.service.DataPermissionEngine;
import com.auraboot.framework.meta.service.FieldMaskService;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.meta.service.NamedQueryService;
import com.auraboot.framework.meta.service.QueryBuilderService;
import com.auraboot.framework.meta.service.SchemaManagementService;
import com.auraboot.framework.meta.service.SecureSqlRewriter;
import com.auraboot.framework.meta.service.TypeSystemManager;
import com.auraboot.framework.meta.service.ValidationService;
import com.auraboot.framework.meta.service.VirtualFieldEngine;
import com.auraboot.framework.meta.service.executor.ExecutorRegistry;
import com.auraboot.framework.permission.engine.model.EvaluationStep;
import com.auraboot.framework.permission.engine.model.EvaluationVerdict;
import com.auraboot.framework.permission.engine.model.FieldPermissionSet;
import com.auraboot.framework.permission.engine.model.PermissionResult;
import com.auraboot.framework.permission.service.FieldPermissionService;
import com.auraboot.framework.permission.service.PermissionFacade;
import com.auraboot.framework.user.mapper.UserMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationContext;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DynamicDataServiceImplDataScopeRuntimeCoverageTest {

    private static final Long TENANT_ID = 10L;
    private static final Long USER_ID = 20L;
    private static final Long MEMBER_ID = 30L;
    private static final String MODEL_CODE = "phase_one_model";
    private static final String RECORD_ID = "record-1";

    @Mock private MetaModelService metadataService;
    @Mock private QueryBuilderService queryBuilderService;
    @Mock private ValidationService validationService;
    @Mock private NamedQueryService namedQueryService;
    @Mock private SecureSqlRewriter secureSqlRewriter;
    @Mock private TypeSystemManager typeSystemManager;
    @Mock private DynamicDataMapper dynamicDataMapper;
    @Mock private SchemaManagementService schemaManagementService;
    @Mock private TableMetadataService tableMetadataService;
    @Mock private ObjectMapper objectMapper;
    @Mock private VirtualFieldEngine virtualFieldEngine;
    @Mock private ChangeTracker changeTracker;
    @Mock private UserMapper userMapper;
    @Mock private FileService fileService;
    @Mock private DataPermissionEngine dataPermissionEngine;
    @Mock private FieldMaskService fieldMaskService;
    @Mock private DataDomainService dataDomainService;
    @Mock private MetaModelMapper metaModelMapper;
    @Mock private ApplicationContext applicationContext;
    @Mock private PayloadTemporalNormalizer payloadTemporalNormalizer;
    @Mock private FieldPermissionService fieldPermissionService;
    @Mock private ExecutorRegistry executorRegistry;
    @Mock private QueryBuilderService.QueryBuilder queryBuilder;

    @InjectMocks
    private DynamicDataServiceImpl service;

    @Mock private PermissionFacade permissionFacade;

    @BeforeEach
    void setUp() {
        MetaContext.setContext(TENANT_ID, USER_ID, "user-pid", "tester");
        MetaContext.setMemberId(MEMBER_ID);
        // getById runs the Rule Center record gate before the legacy DataScope gate.
        // These tests are about the DataScope gate, so let the Rule Center one pass —
        // without this the facade is an unstubbed bean lookup (null) and every case
        // dies with "Permission facade unavailable" before reaching what it asserts.
        lenient().when(applicationContext.getBean(PermissionFacade.class)).thenReturn(permissionFacade);
        lenient().when(permissionFacade.canOperate(anyLong(), anyString(), eq("read"), anyMap()))
                .thenReturn(PermissionResult.allow(List.of(
                        new EvaluationStep("RolePermission", EvaluationVerdict.ALLOW, "rbac ok"))));
    }

    @AfterEach
    void tearDown() {
        MetaContext.clear();
    }

    @Test
    @DisplayName("list reuses scoped filters for data and count queries within one request")
    void list_reusesScopedFiltersForDataAndCount() {
        ModelDefinition model = physicalModel(MODEL_CODE, "mt_phase_one_model");
        wireModel(model);
        QueryBuilderService.QueryBuilder dataBuilder = mock(QueryBuilderService.QueryBuilder.class);
        QueryBuilderService.QueryBuilder countBuilder = mock(QueryBuilderService.QueryBuilder.class);
        wireListBuilder(dataBuilder, "SELECT * FROM mt_phase_one_model");
        wireListBuilder(countBuilder, "SELECT * FROM mt_phase_one_model");
        when(queryBuilderService.buildConditionQuery(eq(model), anyList()))
                .thenReturn(dataBuilder, countBuilder);
        when(queryBuilderService.buildPaginationQuery(eq(dataBuilder), any())).thenReturn(dataBuilder);
        when(queryBuilderService.validateQuery(dataBuilder)).thenReturn(QueryValidationResult.valid());
        when(secureSqlRewriter.rewriteForCount("SELECT * FROM mt_phase_one_model"))
                .thenReturn("SELECT count(*) FROM mt_phase_one_model");
        when(dynamicDataMapper.selectByQuery(eq("SELECT * FROM mt_phase_one_model"), anyMap()))
                .thenReturn(List.of(Map.of("pid", RECORD_ID, "created_by", USER_ID)));
        when(dynamicDataMapper.countByQuery(eq("SELECT count(*) FROM mt_phase_one_model"), anyMap()))
                .thenReturn(1L);
        when(dataPermissionEngine.buildRowFilter(TENANT_ID, MODEL_CODE, USER_ID))
                .thenReturn("AND created_by = 20");
        when(dataDomainService.buildDomainFilter(MODEL_CODE, USER_ID)).thenReturn("AND domain_id = 7");
        when(dataPermissionEngine.getFieldMaskRules(TENANT_ID, MODEL_CODE, USER_ID)).thenReturn(List.of());
        when(fieldMaskService.applyMaskingForList(eq(MODEL_CODE), anyList(), eq(USER_ID)))
                .thenAnswer(invocation -> invocation.getArgument(1));
        when(fieldPermissionService.getFieldPermissions(MEMBER_ID, MODEL_CODE))
                .thenReturn(FieldPermissionSet.allAllowed(java.util.Set.of("pid")));

        service.list(MODEL_CODE, DynamicQueryRequest.builder()
                .pageNum(1)
                .pageSize(20)
                .conditions(List.of())
                .build());

        verify(dataPermissionEngine, times(1)).buildRowFilter(TENANT_ID, MODEL_CODE, USER_ID);
        verify(dataDomainService, times(1)).buildDomainFilter(MODEL_CODE, USER_ID);
        verify(dataBuilder).addRawCondition("AND created_by = 20");
        verify(countBuilder).addRawCondition("AND created_by = 20");
        verify(dataBuilder).addRawCondition("AND domain_id = 7");
        verify(countBuilder).addRawCondition("AND domain_id = 7");
    }

    @Test
    @DisplayName("list reuses permission and domain filters across one command query scope")
    void list_reusesScopedFiltersAcrossCommandQueryScope() {
        ModelDefinition model = physicalModel(MODEL_CODE, "mt_phase_one_model");
        wireModel(model);
        QueryBuilderService.QueryBuilder firstDataBuilder = mock(QueryBuilderService.QueryBuilder.class);
        QueryBuilderService.QueryBuilder firstCountBuilder = mock(QueryBuilderService.QueryBuilder.class);
        QueryBuilderService.QueryBuilder secondDataBuilder = mock(QueryBuilderService.QueryBuilder.class);
        QueryBuilderService.QueryBuilder secondCountBuilder = mock(QueryBuilderService.QueryBuilder.class);
        wireListBuilder(firstDataBuilder, "SELECT * FROM mt_phase_one_model");
        wireListBuilder(firstCountBuilder, "SELECT * FROM mt_phase_one_model");
        wireListBuilder(secondDataBuilder, "SELECT * FROM mt_phase_one_model");
        wireListBuilder(secondCountBuilder, "SELECT * FROM mt_phase_one_model");
        when(queryBuilderService.buildConditionQuery(eq(model), anyList()))
                .thenReturn(firstDataBuilder, firstCountBuilder, secondDataBuilder, secondCountBuilder);
        when(queryBuilderService.buildPaginationQuery(eq(firstDataBuilder), any())).thenReturn(firstDataBuilder);
        when(queryBuilderService.buildPaginationQuery(eq(secondDataBuilder), any())).thenReturn(secondDataBuilder);
        when(queryBuilderService.validateQuery(firstDataBuilder)).thenReturn(QueryValidationResult.valid());
        when(queryBuilderService.validateQuery(secondDataBuilder)).thenReturn(QueryValidationResult.valid());
        when(secureSqlRewriter.rewriteForCount("SELECT * FROM mt_phase_one_model"))
                .thenReturn("SELECT count(*) FROM mt_phase_one_model");
        when(dynamicDataMapper.selectByQuery(eq("SELECT * FROM mt_phase_one_model"), anyMap()))
                .thenReturn(List.of(Map.of("pid", RECORD_ID, "created_by", USER_ID)));
        when(dynamicDataMapper.countByQuery(eq("SELECT count(*) FROM mt_phase_one_model"), anyMap()))
                .thenReturn(1L);
        when(dataPermissionEngine.buildRowFilter(TENANT_ID, MODEL_CODE, USER_ID))
                .thenReturn("AND created_by = 20");
        when(dataDomainService.buildDomainFilter(MODEL_CODE, USER_ID)).thenReturn("AND domain_id = 7");
        when(dataPermissionEngine.getFieldMaskRules(TENANT_ID, MODEL_CODE, USER_ID)).thenReturn(List.of());
        when(fieldMaskService.applyMaskingForList(eq(MODEL_CODE), anyList(), eq(USER_ID)))
                .thenAnswer(invocation -> invocation.getArgument(1));
        when(fieldPermissionService.getFieldPermissions(MEMBER_ID, MODEL_CODE))
                .thenReturn(FieldPermissionSet.allAllowed(java.util.Set.of("pid")));

        try (DynamicDataQueryScope ignored = DynamicDataQueryScope.open()) {
            DynamicQueryRequest request = DynamicQueryRequest.builder()
                    .pageNum(1)
                    .pageSize(20)
                    .conditions(List.of())
                    .build();
            service.list(MODEL_CODE, request);
            service.list(MODEL_CODE, request);
        }

        verify(dataPermissionEngine, times(1)).buildRowFilter(TENANT_ID, MODEL_CODE, USER_ID);
        verify(dataDomainService, times(1)).buildDomainFilter(MODEL_CODE, USER_ID);
        verify(firstDataBuilder).addRawCondition("AND created_by = 20");
        verify(firstCountBuilder).addRawCondition("AND created_by = 20");
        verify(secondDataBuilder).addRawCondition("AND created_by = 20");
        verify(secondCountBuilder).addRawCondition("AND created_by = 20");
        verify(firstDataBuilder).addRawCondition("AND domain_id = 7");
        verify(firstCountBuilder).addRawCondition("AND domain_id = 7");
        verify(secondDataBuilder).addRawCondition("AND domain_id = 7");
        verify(secondCountBuilder).addRawCondition("AND domain_id = 7");
    }

    @Test
    @DisplayName("batchDelete uses scoped bulk SQL with tenant and DataScope guards")
    void batchDelete_usesScopedBulkSqlWithTenantAndDataScope() {
        ModelDefinition model = physicalModel(MODEL_CODE, "mt_phase_one_model");
        wireModel(model);
        when(dataPermissionEngine.buildRowFilter(TENANT_ID, MODEL_CODE, USER_ID))
                .thenReturn("AND created_by = 20");
        when(dataDomainService.buildDomainFilter(MODEL_CODE, USER_ID)).thenReturn("");
        when(dynamicDataMapper.deleteByQuery(anyString(), anyMap())).thenReturn(1);

        service.batchDelete(MODEL_CODE, List.of(RECORD_ID));

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Map<String, Object>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(dynamicDataMapper).deleteByQuery(sqlCaptor.capture(), paramsCaptor.capture());
        assertThat(sqlCaptor.getValue())
                .contains("DELETE FROM mt_phase_one_model")
                .contains("tenant_id = #{params.tenantId}")
                .contains("pid IN (#{params.id0})")
                .contains("created_by = 20");
        assertThat(paramsCaptor.getValue())
                .containsEntry("tenantId", TENANT_ID)
                .containsEntry("id0", RECORD_ID);
        verify(dataPermissionEngine, never()).canAccessRecord(any(), anyString(), any(), anyMap());
        verify(dynamicDataMapper, never()).delete(anyString(), anyMap());
    }

    @Test
    @DisplayName("batchCreate must not treat access-denied existing record as missing")
    void batchCreate_doesNotCreateWhenExistingPrimaryKeyIsOutsideDataScope() {
        ModelDefinition model = physicalModelWithName(MODEL_CODE, "mt_phase_one_model");
        wireModel(model);
        wireSingleRecordRead(model, Map.of("pid", RECORD_ID, "name", "hidden", "created_by", 999L));
        when(dataPermissionEngine.canAccessRecord(eq(TENANT_ID), eq(MODEL_CODE), eq(USER_ID), anyMap()))
                .thenReturn(false);

        DynamicBatchResponse response = service.batchCreate(MODEL_CODE, List.of(
                new java.util.HashMap<>(Map.of("pid", RECORD_ID, "name", "should-not-write"))));

        assertThat(response.getSuccess()).isZero();
        assertThat(response.getFailed()).isEqualTo(1);
        assertThat(response.getErrors()).singleElement()
                .asString()
                .contains("Access denied");
        verify(dynamicDataMapper, never()).insert(anyString(), anyMap());
        verify(dynamicDataMapper, never()).insertWithJsonb(anyString(), anyMap(), any());
    }

    @Test
    @DisplayName("update write SQL keeps tenant and DataScope guards")
    void update_appliesScopedSqlGuardsToWrite() {
        ModelDefinition model = physicalModelWithName(MODEL_CODE, "mt_phase_one_model");
        wireModel(model);
        wireSingleRecordRead(model, Map.of("pid", RECORD_ID, "name", "before", "created_by", USER_ID));
        when(dataPermissionEngine.canAccessRecord(eq(TENANT_ID), eq(MODEL_CODE), eq(USER_ID), anyMap()))
                .thenReturn(true);
        when(dataPermissionEngine.buildRowFilter(TENANT_ID, MODEL_CODE, USER_ID))
                .thenReturn("AND created_by = 20");
        when(dataDomainService.buildDomainFilter(MODEL_CODE, USER_ID)).thenReturn("AND domain_id = 7");
        when(dynamicDataMapper.updateByQuery(anyString(), anyMap())).thenReturn(1);

        service.update(MODEL_CODE, RECORD_ID, new java.util.HashMap<>(Map.of("name", "after")));

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Map<String, Object>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(dynamicDataMapper).updateByQuery(sqlCaptor.capture(), paramsCaptor.capture());
        assertThat(sqlCaptor.getValue())
                .contains("UPDATE mt_phase_one_model SET")
                .containsPattern("name = #\\{params\\.set\\d+}")
                .contains("WHERE pid = #{params.recordId}")
                .contains("tenant_id = #{params.tenantId}")
                .contains("created_by = 20")
                .contains("domain_id = 7");
        assertThat(paramsCaptor.getValue())
                .containsEntry("recordId", RECORD_ID)
                .containsEntry("tenantId", TENANT_ID)
                .containsValue("after");
        verify(dynamicDataMapper, never()).update(anyString(), anyMap(), anyMap());
        verify(dynamicDataMapper, never()).updateWithJsonb(anyString(), anyMap(), anyMap(), anySet());
    }

    @Test
    @DisplayName("delete write SQL keeps tenant and DataScope guards")
    void delete_appliesScopedSqlGuardsToWrite() {
        ModelDefinition model = physicalModel(MODEL_CODE, "mt_phase_one_model");
        wireModel(model);
        wireSingleRecordRead(model, Map.of("pid", RECORD_ID, "created_by", USER_ID));
        when(dataPermissionEngine.canAccessRecord(eq(TENANT_ID), eq(MODEL_CODE), eq(USER_ID), anyMap()))
                .thenReturn(true);
        when(dataPermissionEngine.buildRowFilter(TENANT_ID, MODEL_CODE, USER_ID))
                .thenReturn("AND created_by = 20");
        when(dataDomainService.buildDomainFilter(MODEL_CODE, USER_ID)).thenReturn("AND domain_id = 7");
        when(dynamicDataMapper.deleteByQuery(anyString(), anyMap())).thenReturn(1);

        service.delete(MODEL_CODE, RECORD_ID);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Map<String, Object>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(dynamicDataMapper).deleteByQuery(sqlCaptor.capture(), paramsCaptor.capture());
        assertThat(sqlCaptor.getValue())
                .contains("DELETE FROM mt_phase_one_model")
                .contains("WHERE pid = #{params.recordId}")
                .contains("tenant_id = #{params.tenantId}")
                .contains("created_by = 20")
                .contains("domain_id = 7");
        assertThat(paramsCaptor.getValue())
                .containsEntry("recordId", RECORD_ID)
                .containsEntry("tenantId", TENANT_ID);
        verify(dynamicDataMapper, never()).delete(anyString(), anyMap());
    }

    @Test
    @DisplayName("batchDelete fails when scoped bulk delete does not affect every requested ID")
    void batchDelete_failsOnPartialScopedBulkDelete() {
        ModelDefinition model = physicalModel(MODEL_CODE, "mt_phase_one_model");
        wireModel(model);
        when(dataPermissionEngine.buildRowFilter(TENANT_ID, MODEL_CODE, USER_ID))
                .thenReturn("AND created_by = 20");
        when(dataDomainService.buildDomainFilter(MODEL_CODE, USER_ID)).thenReturn("");
        when(dynamicDataMapper.deleteByQuery(anyString(), anyMap())).thenReturn(1);

        assertThatThrownBy(() -> service.batchDelete(MODEL_CODE, List.of("own", "other")))
                .isInstanceOf(MetaServiceException.class)
                .hasMessageContaining("Batch delete denied");
    }

    @Test
    @DisplayName("relation reads must deny when the source record is outside DataScope")
    void getRelationData_deniesWhenSourceRecordIsOutsideDataScope() {
        RelationDefinition relation = RelationDefinition.builder()
                .name("items")
                .relationType(RelationDefinition.RelationType.ONE_TO_MANY)
                .targetModel("child_model")
                .targetTable("mt_child_model")
                .targetField("parent_pid")
                .sourceField("pid")
                .build();
        ModelDefinition parent = physicalModel(MODEL_CODE, "mt_phase_one_model");
        parent.setRelations(List.of(relation));
        wireModel(parent);
        wireSingleRecordRead(parent, Map.of("pid", RECORD_ID, "created_by", 999L));
        when(dataPermissionEngine.canAccessRecord(eq(TENANT_ID), eq(MODEL_CODE), eq(USER_ID), anyMap()))
                .thenReturn(false);
        lenient().when(dataPermissionEngine.buildRowFilter(eq(TENANT_ID), eq("child_model"), eq(USER_ID)))
                .thenReturn("");
        lenient().when(dataDomainService.buildDomainFilter(eq("child_model"), eq(USER_ID))).thenReturn("");
        lenient().when(dynamicDataMapper.selectByQuery(anyString(), anyMap()))
                .thenReturn(List.of(Map.of("pid", RECORD_ID, "created_by", 999L)));

        assertThatThrownBy(() -> service.getRelationData(MODEL_CODE, RECORD_ID, "items", Map.of()))
                .isInstanceOf(MetaServiceException.class)
                .hasMessageContaining("Access denied");
    }

    @Test
    @DisplayName("custom count action must apply row-level DataScope before counting")
    void executeCustomAction_countAppliesRowScope() {
        ModelDefinition model = physicalModel(MODEL_CODE, "mt_phase_one_model");
        wireModel(model);
        when(dataPermissionEngine.buildRowFilter(TENANT_ID, MODEL_CODE, USER_ID))
                .thenReturn("AND created_by = 20");
        when(dataDomainService.buildDomainFilter(MODEL_CODE, USER_ID)).thenReturn("");
        when(dynamicDataMapper.selectByQuery(anyString(), anyMap()))
                .thenReturn(List.of(Map.of("cnt", 1L)));

        ActionExecutionResult result = service.executeCustomAction(MODEL_CODE, "count", Map.of());

        assertThat(result.getSuccess()).isTrue();
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(dynamicDataMapper).selectByQuery(sqlCaptor.capture(), anyMap());
        assertThat(sqlCaptor.getValue()).contains("created_by = 20");
    }

    @Test
    @DisplayName("custom truncate action is not a DataScope-safe Dynamic operation")
    void executeCustomAction_truncateRejected() {
        wireModel(physicalModel(MODEL_CODE, "mt_phase_one_model"));

        ActionExecutionResult result = service.executeCustomAction(MODEL_CODE, "truncate", Map.of());

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getErrorMessage()).contains("Unsupported action");
        verify(dynamicDataMapper, never()).delete(anyString(), anyMap());
    }

    private void wireModel(ModelDefinition model) {
        lenient().when(metadataService.getDefinitionByCode(model.getCode())).thenReturn(model);
        lenient().when(metadataService.getModelDefinition(model.getCode())).thenReturn(Optional.of(model));
        lenient().when(metadataService.getPrimaryKeyField(model.getCode())).thenReturn(primaryKey());
        lenient().when(executorRegistry.resolve(any())).thenReturn(Optional.empty());
    }

    private void wireSingleRecordRead(ModelDefinition model, Map<String, Object> record) {
        lenient().when(queryBuilderService.buildConditionQuery(eq(model), anyList())).thenReturn(queryBuilder);
        lenient().when(queryBuilder.getSql()).thenReturn("SELECT * FROM " + model.getTableName() + " WHERE pid = ?");
        lenient().when(queryBuilder.getParameterMap()).thenReturn(Map.of("pid", RECORD_ID, "tenant_id", TENANT_ID));
        lenient().when(dynamicDataMapper.selectByQuery(anyString(), anyMap())).thenReturn(List.of(record));
        lenient().when(dataPermissionEngine.getFieldMaskRules(TENANT_ID, model.getCode(), USER_ID)).thenReturn(List.of());
        lenient().when(fieldMaskService.applyMaskingForDetail(eq(model.getCode()), anyMap(), eq(USER_ID)))
                .thenAnswer(invocation -> invocation.getArgument(1));
        lenient().when(fieldPermissionService.getFieldPermissions(MEMBER_ID, model.getCode()))
                .thenReturn(FieldPermissionSet.allAllowed(java.util.Set.of("pid", "name", "created_by")));
        lenient().when(changeTracker.diff(any(), any(), eq(model.getCode()))).thenReturn(List.of());
    }

    private void wireListBuilder(QueryBuilderService.QueryBuilder builder, String sql) {
        lenient().when(builder.addCondition(anyString(), anyString(), any())).thenReturn(builder);
        lenient().when(builder.addRawCondition(anyString())).thenReturn(builder);
        lenient().when(builder.addOrderBy(anyString(), anyString())).thenReturn(builder);
        lenient().when(builder.getSql()).thenReturn(sql);
        lenient().when(builder.getParameterMap()).thenReturn(Map.of());
    }

    private ModelDefinition physicalModel(String code, String tableName) {
        return ModelDefinition.builder()
                .code(code)
                .tableName(tableName)
                .sourceType("physical")
                .fields(List.of(primaryKey()))
                .build();
    }

    private ModelDefinition physicalModelWithName(String code, String tableName) {
        return ModelDefinition.builder()
                .code(code)
                .tableName(tableName)
                .sourceType("physical")
                .fields(List.of(
                        primaryKey(),
                        FieldDefinition.builder()
                                .code("name")
                                .columnName("name")
                                .dataType("string")
                                .build()))
                .build();
    }

    private FieldDefinition primaryKey() {
        return FieldDefinition.builder()
                .code("pid")
                .columnName("pid")
                .primaryKey(true)
                .build();
    }
}

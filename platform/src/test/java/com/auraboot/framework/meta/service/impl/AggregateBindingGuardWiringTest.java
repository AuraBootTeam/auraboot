package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.file.service.FileService;
import com.auraboot.framework.meta.ddl.TableMetadataService;
import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.mapper.MetaModelMapper;
import com.auraboot.framework.meta.service.*;
import com.auraboot.framework.meta.service.executor.ExecutorRegistry;
import com.auraboot.framework.permission.engine.model.EvaluationStep;
import com.auraboot.framework.permission.engine.model.EvaluationVerdict;
import com.auraboot.framework.permission.engine.model.PermissionResult;
import com.auraboot.framework.permission.service.FieldPermissionService;
import com.auraboot.framework.permission.service.PermissionFacade;
import com.auraboot.framework.tenant.service.TenantMemberService;
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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * The aggregate-binding guard is <em>wired into the write paths</em>, not merely correct in
 * isolation.
 *
 * <p>Unit-testing the guard function proves the function works. It does not prove anybody calls
 * it: delete the one line in {@code executeScopedUpdate} and every isolated test still passes
 * while the boundary is gone. These tests drive the real {@code update} / {@code create} entry
 * points and read the SQL and column data that actually reach the mapper.</p>
 */
@ExtendWith(MockitoExtension.class)
class AggregateBindingGuardWiringTest {

    private static final String MODEL_CODE = "quote_line";
    private static final String RECORD_ID = "line-001";
    private static final Long TENANT_ID = 1L;
    private static final Long USER_ID = 42L;
    private static final String AUTHORIZED_AGGREGATE = "Q1001";

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
    @Mock private PermissionFacade permissionFacade;
    @Mock private TenantMemberService tenantMemberService;

    @InjectMocks
    private DynamicDataServiceImpl service;

    @Mock private QueryBuilderService.QueryBuilder queryBuilder;

    private ModelDefinition model;

    @BeforeEach
    void setUp() {
        MetaContext.setContext(TENANT_ID, USER_ID, "pid-42", "tester");
        MetaContext.setMemberId(USER_ID);

        FieldDefinition pk = FieldDefinition.builder().code("pid").columnName("pid").build();
        FieldDefinition quotePid = FieldDefinition.builder().code("quote_pid").columnName("quote_pid").build();
        FieldDefinition amount = FieldDefinition.builder().code("amount").columnName("amount").build();

        model = ModelDefinition.builder()
                .code(MODEL_CODE)
                .tableName("mt_" + MODEL_CODE)
                .sourceType("physical")
                .fields(List.of(pk, quotePid, amount))
                // The model opts in: its rows belong to a quote, proved by quote_pid.
                .aggregateBinding(ModelDefinition.AggregateBinding.builder()
                        .aggregateModel("quote")
                        .localField("quote_pid")
                        .build())
                .build();

        lenient().when(metadataService.getModelDefinition(MODEL_CODE)).thenReturn(Optional.of(model));
        lenient().when(metadataService.getDefinitionByCode(MODEL_CODE)).thenReturn(model);
        lenient().when(metadataService.getPrimaryKeyField(MODEL_CODE)).thenReturn(pk);
        lenient().when(tableMetadataService.tableExists("mt_" + MODEL_CODE)).thenReturn(true);
        lenient().when(dataPermissionEngine.getNonWritableFields(TENANT_ID, MODEL_CODE, USER_ID))
                .thenReturn(java.util.Set.of());

        lenient().when(executorRegistry.resolve(any())).thenReturn(Optional.empty());
        lenient().when(queryBuilderService.buildConditionQuery(eq(model), anyList())).thenReturn(queryBuilder);
        lenient().when(queryBuilder.getSql()).thenReturn("SELECT * FROM mt_quote_line WHERE pid = :pid");
        lenient().when(queryBuilder.getParameterMap()).thenReturn(Map.of("pid", RECORD_ID));
        lenient().when(dynamicDataMapper.selectByQuery(anyString(), anyMap()))
                .thenReturn(List.of(Map.of("pid", RECORD_ID, "quote_pid", AUTHORIZED_AGGREGATE, "amount", 10)));

        lenient().when(applicationContext.getBean(PermissionFacade.class)).thenReturn(permissionFacade);
        allowRead();
    }

    private void allowRead() {
        lenient().when(permissionFacade.canOperate(anyLong(), eq(MODEL_CODE), eq("read"), anyMap()))
                .thenReturn(PermissionResult.allow(List.of(
                        new EvaluationStep("RolePermission", EvaluationVerdict.ALLOW, "rbac ok"))));
        lenient().when(dataPermissionEngine.canAccessRecord(eq(TENANT_ID), eq(MODEL_CODE), anyLong(), anyMap()))
                .thenReturn(true);
        lenient().when(fieldMaskService.applyMaskingForDetail(eq(MODEL_CODE), anyMap(), anyLong()))
                .thenAnswer(inv -> inv.getArgument(1));
        lenient().when(fieldPermissionService.getFieldPermissions(anyLong(), eq(MODEL_CODE)))
                .thenReturn(com.auraboot.framework.permission.engine.model.FieldPermissionSet
                        .allAllowed(java.util.Set.of("pid", "quote_pid", "amount")));
    }

    @AfterEach
    void clear() {
        MetaContext.clear();
    }

    @Test
    @DisplayName("update under an aggregate scope pins the SQL to the authorized aggregate")
    void updateIsPinnedToAuthorizedAggregate() {
        when(dynamicDataMapper.updateByQuery(anyString(), anyMap())).thenReturn(1);
        Map<String, Object> data = new HashMap<>();
        data.put("amount", 99);

        MetaContext.runWithCommandAggregate(AUTHORIZED_AGGREGATE, () ->
                service.update(MODEL_CODE, RECORD_ID, data));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Map> params = ArgumentCaptor.forClass(Map.class);
        verify(dynamicDataMapper).updateByQuery(sql.capture(), params.capture());

        assertThat(sql.getValue())
                .as("the write must be pinned to the authorized aggregate in the SQL itself")
                .contains("quote_pid = #{params.authorizedAggregateId}");
        assertThat(params.getValue().get("authorizedAggregateId"))
                .as("the pinned value must be the aggregate the entry authorized")
                .isEqualTo(AUTHORIZED_AGGREGATE);
    }

    @Test
    @DisplayName("update outside an aggregate scope is unchanged")
    void updateWithoutScopeIsUnchanged() {
        when(dynamicDataMapper.updateByQuery(anyString(), anyMap())).thenReturn(1);
        Map<String, Object> data = new HashMap<>();
        data.put("amount", 99);

        service.update(MODEL_CODE, RECORD_ID, data);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(dynamicDataMapper).updateByQuery(sql.capture(), anyMap());
        assertThat(sql.getValue())
                .as("unscoped writes must behave exactly as before the guard existed")
                .doesNotContain("authorizedAggregateId");
    }

    @Test
    @DisplayName("create under an aggregate scope stamps the row with the authorized aggregate")
    void createIsStampedWithAuthorizedAggregate() {
        when(dynamicDataMapper.insert(anyString(), anyMap())).thenReturn(1);
        Map<String, Object> data = new HashMap<>();
        data.put("pid", RECORD_ID);
        data.put("amount", 10);
        // The payload claims a different quote. It must not win.
        data.put("quote_pid", "Q2002");

        MetaContext.runWithCommandAggregate(AUTHORIZED_AGGREGATE, () ->
                service.create(MODEL_CODE, data));

        ArgumentCaptor<Map> columns = ArgumentCaptor.forClass(Map.class);
        verify(dynamicDataMapper).insert(anyString(), columns.capture());

        assertThat(columns.getValue())
                .as("the aggregate the entry authorized must overwrite the one the payload claimed")
                .containsEntry("quote_pid", AUTHORIZED_AGGREGATE);
    }
}

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
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationContext;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Regression tests for the platform-internal read-back inside
 * {@link DynamicDataServiceImpl#create} and {@link DynamicDataServiceImpl#update}.
 *
 * <p>Both methods read the row back after writing it, to build the change-log
 * snapshot and the automation / SLA event payload. That read must NOT be
 * projected through the caller's read permissions: a caller may legitimately be
 * allowed to create or update a model without being allowed to read it, and the
 * automation/SLA payload must not be field-masked.
 *
 * <p>Regression guarded: when a record-level read check was added to
 * {@code getById}, every create/update performed by such a caller started
 * failing with "Access denied: you do not have permission to view this record",
 * even though the write itself had already succeeded and been authorized.
 *
 * <p>Falsifiability: both tests stub the permission facade to DENY reads. If the
 * read-back stops bypassing data permissions, the deny propagates out of
 * create/update and these tests fail.
 */
@ExtendWith(MockitoExtension.class)
class DynamicDataServiceImplWriteReadBackPermissionTest {

    private static final String MODEL_CODE = "test_model";
    private static final String RECORD_ID = "rec-001";
    private static final Long TENANT_ID = 1L;
    private static final Long USER_ID = 42L;

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
    void setUpContext() {
        MetaContext.setContext(TENANT_ID, USER_ID, "pid-42", "tester");
        MetaContext.setMemberId(USER_ID);

        FieldDefinition pk = FieldDefinition.builder()
                .code("pid")
                .columnName("pid")
                .build();
        FieldDefinition nameField = FieldDefinition.builder()
                .code("name")
                .columnName("name")
                .build();
        model = ModelDefinition.builder()
                .code(MODEL_CODE)
                .tableName("mt_" + MODEL_CODE)
                .sourceType("physical")
                .fields(List.of(pk, nameField))
                .build();

        lenient().when(metadataService.getModelDefinition(MODEL_CODE)).thenReturn(Optional.of(model));
        lenient().when(metadataService.getDefinitionByCode(MODEL_CODE)).thenReturn(model);
        lenient().when(metadataService.getPrimaryKeyField(MODEL_CODE)).thenReturn(pk);
        lenient().when(tableMetadataService.tableExists("mt_" + MODEL_CODE)).thenReturn(true);
        lenient().when(dataPermissionEngine.getNonWritableFields(TENANT_ID, MODEL_CODE, USER_ID))
                .thenReturn(java.util.Set.of());

        // read-back query path
        lenient().when(executorRegistry.resolve(any())).thenReturn(Optional.empty());
        lenient().when(queryBuilderService.buildConditionQuery(eq(model), anyList())).thenReturn(queryBuilder);
        lenient().when(queryBuilder.getSql()).thenReturn("SELECT * FROM mt_test_model WHERE pid = :pid");
        lenient().when(queryBuilder.getParameterMap()).thenReturn(Map.of("pid", RECORD_ID));
        lenient().when(dynamicDataMapper.selectByQuery(anyString(), anyMap()))
                .thenReturn(List.of(Map.of("pid", RECORD_ID, "name", "written-value")));

        lenient().when(applicationContext.getBean(PermissionFacade.class)).thenReturn(permissionFacade);
        // NOTE: canOperate is deliberately NOT stubbed here — each test states the
        // read verdict it needs, so there is no ambiguity about which stub wins.
    }

    private void stubReadVerdict(boolean granted) {
        PermissionResult result = granted
                ? PermissionResult.allow(List.of(
                        new EvaluationStep("RolePermission", EvaluationVerdict.ALLOW, "rbac ok")))
                : PermissionResult.deny(
                        "User lacks permission: test_model:read",
                        List.of(new EvaluationStep("RolePermission", EvaluationVerdict.DENY,
                                "User lacks permission: test_model:read")));
        lenient().when(permissionFacade.canOperate(anyLong(), eq(MODEL_CODE), eq("read"), anyMap()))
                .thenReturn(result);
        // getById runs a second, legacy row-level ACL after the Rule Center gate.
        // Both must agree, otherwise the legacy gate denies on its own (an unstubbed
        // boolean mock defaults to false) and the test would pass/fail for the wrong reason.
        lenient().when(dataPermissionEngine.canAccessRecord(eq(TENANT_ID), eq(MODEL_CODE), anyLong(), anyMap()))
                .thenReturn(granted);
        // Remaining getById gates that only run when permissions are NOT bypassed:
        // detail-view masking and field-permission filtering. Make them pass-through
        // so a permitted read reaches the end instead of failing closed on a bare mock.
        lenient().when(fieldMaskService.applyMaskingForDetail(eq(MODEL_CODE), anyMap(), anyLong()))
                .thenAnswer(inv -> inv.getArgument(1));
        lenient().when(fieldPermissionService.getFieldPermissions(anyLong(), eq(MODEL_CODE)))
                .thenReturn(com.auraboot.framework.permission.engine.model.FieldPermissionSet
                        .allAllowed(java.util.Set.of("pid", "name")));
    }

    @AfterEach
    void clearContext() {
        MetaContext.clear();
    }

    @Test
    @DisplayName("create succeeds when the caller may write but not read the model")
    void create_callerCannotReadModel_stillSucceeds() {
        stubReadVerdict(false); // caller may create, but may NOT read this model
        when(dynamicDataMapper.insert(anyString(), anyMap())).thenReturn(1);

        Map<String, Object> data = new HashMap<>();
        data.put("pid", RECORD_ID);
        data.put("name", "written-value");

        Map<String, Object> created = service.create(MODEL_CODE, data);

        assertThat(created).containsEntry("pid", RECORD_ID);
        // The read-back must not have been projected through the caller's read permission.
        verify(permissionFacade, never()).canOperate(anyLong(), anyString(), eq("read"), anyMap());
    }

    @Test
    @DisplayName("update's post-write read-back does not re-run the caller's read permission check")
    void update_readBack_doesNotReProjectCallerPermission() {
        // The PRE-update read of the existing record legitimately goes through the
        // caller's read permission ("you may not modify a record you cannot see"),
        // so allow it — otherwise we never reach the write and the read-back.
        stubReadVerdict(true);
        // update() writes through executeScopedUpdate → updateByQuery (tenant/DataScope
        // guards are inlined into the SQL), not the plain update() mapper method.
        when(dynamicDataMapper.updateByQuery(anyString(), anyMap())).thenReturn(1);

        Map<String, Object> data = new HashMap<>();
        data.put("name", "updated-value");

        assertThatCode(() -> service.update(MODEL_CODE, RECORD_ID, data)).doesNotThrowAnyException();

        // Exactly one permission evaluation — the pre-update read. If the post-write
        // read-back stops bypassing data permissions it adds a second one and this fails.
        verify(permissionFacade, times(1)).canOperate(anyLong(), eq(MODEL_CODE), eq("read"), anyMap());
    }
}

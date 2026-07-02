package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.file.service.FileService;
import com.auraboot.framework.meta.ddl.TableMetadataService;
import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.dto.FieldMaskRule;
import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.framework.meta.exception.MetaServiceException;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.mapper.MetaModelMapper;
import com.auraboot.framework.meta.service.*;
import com.auraboot.framework.meta.service.executor.ExecutorRegistry;
import com.auraboot.framework.meta.util.JsonbFieldHelper;
import com.auraboot.framework.permission.service.FieldPermissionService;
import com.auraboot.framework.user.mapper.UserMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationContext;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Mockito-based unit tests verifying that {@link DynamicDataServiceImpl#getById}
 * fails closed (throws {@link MetaServiceException}) when the security gates
 * encounter an internal error, rather than silently returning the unmasked record.
 *
 * <p>This is the follow-up test class promised in commit
 * {@code fix(meta): make DynamicDataService.getById fail-secure on row-ACL /
 * field-mask exceptions}. The three security gates covered are:
 * <ol>
 *   <li>Row-level access check ({@code dataPermissionEngine.canAccessRecord}) — §P4</li>
 *   <li>Policy-driven column masking ({@code dataPermissionEngine.getFieldMaskRules}) — §P4</li>
 *   <li>Configurable detail-view masking ({@code fieldMaskService.applyMaskingForDetail}) — §P4</li>
 * </ol>
 *
 * <p>No Spring context / DB / Kafka is started. All dependencies are Mockito mocks
 * injected via constructor ({@code @RequiredArgsConstructor} on the SUT) through
 * {@code @InjectMocks}.
 *
 * <p>See {@code docs/standards/core/catch-exception-pattern.md} §P4 for the
 * project-wide fail-secure rule.
 */
@ExtendWith(MockitoExtension.class)
class DynamicDataServiceImplGetByIdFailSecureTest {

    private static final String MODEL_CODE = "test_model";
    private static final String RECORD_ID = "rec-001";
    private static final Long TENANT_ID = 1L;
    private static final Long USER_ID = 42L;

    // ---- all constructor dependencies as mocks ----
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

    @InjectMocks
    private DynamicDataServiceImpl service;

    /** Mock inner QueryBuilder returned by queryBuilderService. */
    @Mock private QueryBuilderService.QueryBuilder queryBuilder;

    @BeforeEach
    void setUpContext() {
        MetaContext.setContext(TENANT_ID, USER_ID, "pid-42", "tester");
    }

    @AfterEach
    void clearContext() {
        MetaContext.clear();
    }

    /**
     * Wire up the common happy-path stubs so that the record is found in the DB
     * and the test can reach the security gate under test.
     *
     * <p>Stubs covered here:
     * <ul>
     *   <li>{@code executorRegistry.resolve} → empty (use physical path)</li>
     *   <li>{@code metadataService.getModelDefinition} → minimal ModelDefinition</li>
     *   <li>{@code metadataService.getPrimaryKeyField} → minimal pk FieldDefinition</li>
     *   <li>{@code queryBuilderService.buildConditionQuery} → mock QueryBuilder</li>
     *   <li>{@code queryBuilder.getSql/getParameterMap} → dummy values</li>
     *   <li>{@code dynamicDataMapper.selectByQuery} → one-record result</li>
     * </ul>
     */
    private void wireHappyPathDbStubs() {
        // Virtual-model executor: not present → fall through to physical path
        when(executorRegistry.resolve(any())).thenReturn(Optional.empty());

        // Model definition
        FieldDefinition pk = FieldDefinition.builder()
                .code("pid")
                .columnName("pid")
                .build();
        ModelDefinition model = ModelDefinition.builder()
                .code(MODEL_CODE)
                .tableName("mt_" + MODEL_CODE)
                .sourceType("physical")
                .fields(List.of(pk))
                .build();
        when(metadataService.getModelDefinition(MODEL_CODE)).thenReturn(Optional.of(model));
        when(metadataService.getPrimaryKeyField(MODEL_CODE)).thenReturn(pk);

        // QueryBuilder stub
        when(queryBuilderService.buildConditionQuery(eq(model), anyList())).thenReturn(queryBuilder);
        when(queryBuilder.getSql()).thenReturn("SELECT * FROM mt_test_model WHERE pid = :pid AND tenant_id = :tenant_id");
        when(queryBuilder.getParameterMap()).thenReturn(Map.of("pid", RECORD_ID, "tenant_id", TENANT_ID));

        // DB returns one record
        Map<String, Object> rawRecord = Map.of("pid", RECORD_ID, "name", "secret-value");
        when(dynamicDataMapper.selectByQuery(anyString(), anyMap())).thenReturn(List.of(rawRecord));
    }

    // =====================================================================
    // Test (a): row-ACL check throws → getById must NOT return the record
    // =====================================================================

    @Test
    @DisplayName("row-ACL engine throws RuntimeException → getById throws MetaServiceException (fail-secure, §P4)")
    void getById_rowAclEngineThrows_throwsMetaServiceException() {
        wireHappyPathDbStubs();

        // Simulate an internal error in the permission engine (e.g. DB timeout, NPE in policy eval)
        when(dataPermissionEngine.canAccessRecord(eq(TENANT_ID), eq(MODEL_CODE), eq(USER_ID), anyMap()))
                .thenThrow(new RuntimeException("permission engine DB timeout"));

        // The method must throw MetaServiceException — NOT return the record
        assertThatThrownBy(() -> service.getById(MODEL_CODE, RECORD_ID))
                .isInstanceOf(MetaServiceException.class)
                .hasMessageContaining("Data permission evaluation failed");
    }

    @Test
    @DisplayName("row-ACL engine returns false → getById throws MetaServiceException (access denied)")
    void getById_rowAclDenied_throwsMetaServiceException() {
        wireHappyPathDbStubs();

        // Explicit deny (policy says this user may not see this row)
        when(dataPermissionEngine.canAccessRecord(eq(TENANT_ID), eq(MODEL_CODE), eq(USER_ID), anyMap()))
                .thenReturn(false);

        assertThatThrownBy(() -> service.getById(MODEL_CODE, RECORD_ID))
                .isInstanceOf(MetaServiceException.class)
                .hasMessageContaining("Access denied");
    }

    // =====================================================================
    // Test (b): policy-driven field masking throws → must NOT return unmasked fields
    // =====================================================================

    @Test
    @DisplayName("getFieldMaskRules throws RuntimeException → getById throws MetaServiceException (fail-secure, §P4)")
    void getById_maskRulesLookupThrows_throwsMetaServiceException() {
        wireHappyPathDbStubs();

        // ACL check passes
        when(dataPermissionEngine.canAccessRecord(eq(TENANT_ID), eq(MODEL_CODE), eq(USER_ID), anyMap()))
                .thenReturn(true);

        // Simulate internal error while fetching masking rules
        when(dataPermissionEngine.getFieldMaskRules(eq(TENANT_ID), eq(MODEL_CODE), eq(USER_ID)))
                .thenThrow(new RuntimeException("field mask policy load error"));

        // Must NOT return the unmasked record
        assertThatThrownBy(() -> service.getById(MODEL_CODE, RECORD_ID))
                .isInstanceOf(MetaServiceException.class)
                .hasMessageContaining("Field masking evaluation failed");
    }

    @Test
    @DisplayName("applyFieldMasking throws RuntimeException → getById throws MetaServiceException (fail-secure, §P4)")
    void getById_applyFieldMaskingThrows_throwsMetaServiceException() {
        wireHappyPathDbStubs();

        // ACL check passes
        when(dataPermissionEngine.canAccessRecord(eq(TENANT_ID), eq(MODEL_CODE), eq(USER_ID), anyMap()))
                .thenReturn(true);

        // Masking rules are loaded successfully...
        List<FieldMaskRule> rules = List.of(mock(FieldMaskRule.class));
        when(dataPermissionEngine.getFieldMaskRules(eq(TENANT_ID), eq(MODEL_CODE), eq(USER_ID)))
                .thenReturn(rules);

        // ...but applyFieldMasking itself throws
        when(dataPermissionEngine.applyFieldMasking(anyList(), eq(rules)))
                .thenThrow(new RuntimeException("masking serialization error"));

        // Must NOT return the unmasked record
        assertThatThrownBy(() -> service.getById(MODEL_CODE, RECORD_ID))
                .isInstanceOf(MetaServiceException.class)
                .hasMessageContaining("Field masking evaluation failed");
    }

    // =====================================================================
    // Test (b2): field-permission filter lookup throws → must fail closed
    // (deep-review DR-20260701 W1-F2 — the two applyFieldPermissionFilter
    //  helpers used to log.warn + return the UNMASKED record)
    // =====================================================================

    @Test
    @DisplayName("getFieldPermissions throws → getById throws MetaServiceException (fail-secure, field-permission gate)")
    void getById_fieldPermissionLookupThrows_throwsMetaServiceException() {
        wireHappyPathDbStubs();

        // ACL + policy/configurable masking all pass, so control reaches the field-permission filter
        when(dataPermissionEngine.canAccessRecord(eq(TENANT_ID), eq(MODEL_CODE), eq(USER_ID), anyMap()))
                .thenReturn(true);
        when(dataPermissionEngine.getFieldMaskRules(eq(TENANT_ID), eq(MODEL_CODE), eq(USER_ID)))
                .thenReturn(List.of());
        when(fieldMaskService.applyMaskingForDetail(eq(MODEL_CODE), anyMap(), eq(USER_ID)))
                .thenAnswer(invocation -> invocation.getArgument(1));

        // Field-permission evaluation blows up (e.g. role lookup DB error / missing member context)
        when(fieldPermissionService.getFieldPermissions(any(), eq(MODEL_CODE)))
                .thenThrow(new RuntimeException("field permission role lookup error"));

        // Must NOT return the record with its hidden fields still present
        assertThatThrownBy(() -> service.getById(MODEL_CODE, RECORD_ID))
                .isInstanceOf(MetaServiceException.class)
                .hasMessageContaining("Field permission evaluation failed");
    }

    // =====================================================================
    // Test (c): configurable detail masking (fieldMaskService) throws
    // =====================================================================

    @Test
    @DisplayName("fieldMaskService.applyMaskingForDetail throws RuntimeException → getById throws MetaServiceException (fail-secure, §P4)")
    void getById_detailMaskServiceThrows_throwsMetaServiceException() {
        wireHappyPathDbStubs();

        // ACL check passes
        when(dataPermissionEngine.canAccessRecord(eq(TENANT_ID), eq(MODEL_CODE), eq(USER_ID), anyMap()))
                .thenReturn(true);

        // Policy-driven masking: no rules to apply (safe to skip that step)
        when(dataPermissionEngine.getFieldMaskRules(eq(TENANT_ID), eq(MODEL_CODE), eq(USER_ID)))
                .thenReturn(List.of());

        // configurable masking step throws
        when(fieldMaskService.applyMaskingForDetail(eq(MODEL_CODE), anyMap(), eq(USER_ID)))
                .thenThrow(new RuntimeException("configurable masking config error"));

        // Must NOT return the unmasked record
        assertThatThrownBy(() -> service.getById(MODEL_CODE, RECORD_ID))
                .isInstanceOf(MetaServiceException.class)
                .hasMessageContaining("Detail-view field masking failed");
    }

    // =====================================================================
    // Happy path sanity — ensures the stubs compose correctly and the
    // gate is in place but not incorrectly always throwing
    // =====================================================================

    @Test
    @DisplayName("all security gates pass → getById returns the record (no false denial)")
    void getById_allGatesPass_returnsRecord() {
        wireHappyPathDbStubs();

        // ACL check passes
        when(dataPermissionEngine.canAccessRecord(eq(TENANT_ID), eq(MODEL_CODE), eq(USER_ID), anyMap()))
                .thenReturn(true);

        // No policy mask rules
        when(dataPermissionEngine.getFieldMaskRules(eq(TENANT_ID), eq(MODEL_CODE), eq(USER_ID)))
                .thenReturn(List.of());

        // Configurable masking: return the record unchanged
        when(fieldMaskService.applyMaskingForDetail(eq(MODEL_CODE), anyMap(), eq(USER_ID)))
                .thenAnswer(invocation -> invocation.getArgument(1));

        // Field permission: no hidden fields
        when(fieldPermissionService.getFieldPermissions(any(), eq(MODEL_CODE)))
                .thenReturn(new com.auraboot.framework.permission.engine.model.FieldPermissionSet(
                        java.util.Set.of(), java.util.Set.of(), java.util.Set.of()));

        Map<String, Object> result = service.getById(MODEL_CODE, RECORD_ID);
        org.assertj.core.api.Assertions.assertThat(result).containsKey("pid");
    }
}

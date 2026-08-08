package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.file.service.FileService;
import com.auraboot.framework.meta.ddl.TableMetadataService;
import com.auraboot.framework.meta.dto.DataExportRequest;
import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.dto.ModelDefinition;
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
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationContext;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Verifies that export never falls back to unmasked data when configurable field masking fails.
 */
@ExtendWith(MockitoExtension.class)
class DynamicDataServiceImplExportFailSecureTest {

    private static final String MODEL_CODE = "crm_contact_common";
    private static final long TENANT_ID = 1L;
    private static final long USER_ID = 42L;

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

    @BeforeEach
    void setUp() {
        MetaContext.setContext(TENANT_ID, USER_ID, "user-pid", "export-user");
        MetaContext.setMemberId(USER_ID);

        FieldDefinition phone = FieldDefinition.builder()
                .code("crm_ct_mobile")
                .columnName("crm_ct_mobile")
                .displayName("Mobile")
                .build();
        ModelDefinition model = ModelDefinition.builder()
                .code(MODEL_CODE)
                .tableName("mt_" + MODEL_CODE)
                .sourceType("physical")
                .fields(List.of(phone))
                .build();

        when(metadataService.getModelDefinition(MODEL_CODE)).thenReturn(Optional.of(model));
        when(dataPermissionEngine.buildRowFilter(TENANT_ID, MODEL_CODE, USER_ID)).thenReturn("");
        when(dataDomainService.buildDomainFilter(MODEL_CODE, USER_ID)).thenReturn("");
        when(queryBuilderService.buildConditionQuery(eq(model), anyList())).thenReturn(queryBuilder);
        when(queryBuilder.addCondition("tenant_id", "EQ", TENANT_ID)).thenReturn(queryBuilder);
        when(queryBuilder.getSql()).thenReturn("SELECT crm_ct_mobile FROM mt_crm_contact_common");
        when(queryBuilder.getParameterMap()).thenReturn(Map.of());
        when(dynamicDataMapper.selectByQuery(anyString(), anyMap()))
                .thenReturn(List.of(Map.of("crm_ct_mobile", "13812345678")));
        when(dataPermissionEngine.getFieldMaskRules(TENANT_ID, MODEL_CODE, USER_ID))
                .thenReturn(List.of());
    }

    @AfterEach
    void clearContext() {
        MetaContext.clear();
    }

    @Test
    @DisplayName("configurable field-mask failure aborts export instead of returning raw PII")
    void configurableMaskingFailureFailsClosed() {
        when(fieldMaskService.applyMaskingForExport(eq(MODEL_CODE), anyList(), anyLong()))
                .thenThrow(new RuntimeException("mask store unavailable"));

        DataExportRequest request = DataExportRequest.builder()
                .format(DataExportRequest.ExportFormat.CSV)
                .build();

        assertThatThrownBy(() -> service.exportData(MODEL_CODE, request))
                .isInstanceOf(MetaServiceException.class)
                .hasMessageContaining("Configurable field masking failed for export");
    }
}

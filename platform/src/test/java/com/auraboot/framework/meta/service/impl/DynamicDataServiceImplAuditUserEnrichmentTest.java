package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.file.service.FileService;
import com.auraboot.framework.meta.ddl.TableMetadataService;
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
import com.auraboot.framework.permission.engine.model.FieldPermissionSet;
import com.auraboot.framework.user.mapper.UserMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DynamicDataServiceImplAuditUserEnrichmentTest {

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

    @BeforeEach
    void setContext() {
        MetaContext.setContext(42L, 7L, "user-pid", "tester");
        MetaContext.setMemberId(7L);
    }

    @AfterEach
    void clearContext() {
        MetaContext.clear();
    }

    @Test
    void visibleAuditFieldsUseOneBatchQueryForAllRowsAndActors() {
        List<Map<String, Object>> records = List.of(
                mutableRow(7L, 8L),
                mutableRow(7L, 9L));
        when(userMapper.findDisplayNamesByIdsInTenant(
                eq(42L),
                argThat(ids -> ids.containsAll(List.of(7L, 8L, 9L)) && ids.size() == 3)))
                .thenReturn(List.of(
                        Map.of("id", 7L, "display_name", "Alice Zhang"),
                        Map.of("id", 8L, "display_name", "Bob Li"),
                        Map.of("id", 9L, "display_name", "Carol Wu")));

        ReflectionTestUtils.invokeMethod(
                service,
                "enrichAuditUserDisplayFields",
                records,
                List.of("created_by", "updated_by"));

        verify(userMapper).findDisplayNamesByIdsInTenant(
                eq(42L),
                argThat((Collection<Long> ids) -> ids.containsAll(List.of(7L, 8L, 9L)) && ids.size() == 3));
        assertThat(records.get(0))
                .containsEntry("created_by_display", "Alice Zhang")
                .containsEntry("updated_by_display", "Bob Li");
        assertThat(records.get(1))
                .containsEntry("created_by_display", "Alice Zhang")
                .containsEntry("updated_by_display", "Carol Wu");
    }

    @Test
    void absentOrUnknownAuditFieldsDoNotQueryUsers() {
        List<Map<String, Object>> records = List.of(mutableRow(7L, 8L));

        ReflectionTestUtils.invokeMethod(
                service, "enrichAuditUserDisplayFields", records, null);
        ReflectionTestUtils.invokeMethod(
                service, "enrichAuditUserDisplayFields", records, List.of("password_hash"));

        verify(userMapper, never()).findDisplayNamesByIdsInTenant(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyCollection());
        assertThat(records.getFirst()).doesNotContainKeys(
                "created_by_display", "updated_by_display", "password_hash_display");
    }

    @Test
    void unresolvedTenantMemberNeverFallsBackToInternalUserId() {
        List<Map<String, Object>> records = List.of(mutableRow(77L, null));
        when(userMapper.findDisplayNamesByIdsInTenant(eq(42L), org.mockito.ArgumentMatchers.anyCollection()))
                .thenReturn(List.of());

        ReflectionTestUtils.invokeMethod(
                service, "enrichAuditUserDisplayFields", records, List.of("created_by"));

        assertThat(records.getFirst()).doesNotContainKey("created_by_display");
    }

    @Test
    void resolvesDisplayBeforeFieldPermissionsRemoveTheRawAuditUserId() {
        List<Map<String, Object>> records = List.of(mutableRow(7L, null));
        when(userMapper.findDisplayNamesByIdsInTenant(eq(42L), org.mockito.ArgumentMatchers.anyCollection()))
                .thenReturn(List.of(Map.of("id", 7L, "display_name", "Alice Zhang")));
        when(fieldPermissionService.getFieldPermissions(7L, "quote"))
                .thenReturn(new FieldPermissionSet(Set.of(), Set.of(), Set.of("created_by")));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> filtered = ReflectionTestUtils.invokeMethod(
                service,
                "enrichAuditUsersBeforeFieldPermissionFilter",
                "quote",
                records,
                List.of("created_by"));

        verify(userMapper).findDisplayNamesByIdsInTenant(eq(42L), org.mockito.ArgumentMatchers.anyCollection());
        assertThat(filtered.getFirst())
                .doesNotContainKey("created_by")
                .containsEntry("created_by_display", "Alice Zhang");
    }

    private static Map<String, Object> mutableRow(Long createdBy, Long updatedBy) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("created_by", createdBy);
        row.put("updated_by", updatedBy);
        return row;
    }
}

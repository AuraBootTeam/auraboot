package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.file.service.FileService;
import com.auraboot.framework.meta.ddl.TableMetadataService;
import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.mapper.MetaModelMapper;
import com.auraboot.framework.meta.service.*;
import com.auraboot.framework.meta.service.executor.ExecutorRegistry;
import com.auraboot.framework.permission.service.FieldPermissionService;
import com.auraboot.framework.user.mapper.UserMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for {@link DynamicDataServiceImpl#resolveEnrichmentTarget} — the decision that
 * drives which list columns get a resolved {@code <field>_display} name. Covers both classic
 * {@code reference} fields and renderComponent-driven picker fields (userselect →
 * {@code sys_user}, organizationselect → {@code org_department}), and confirms the multi-value
 * {@code memberpicker} and plain fields are intentionally NOT enriched.
 *
 * <p>Pure decision logic — no Spring/DB. All constructor deps are Mockito mocks; the private
 * method is invoked via {@link ReflectionTestUtils}.
 */
@ExtendWith(MockitoExtension.class)
class DynamicDataServiceImplReferenceEnrichmentTest {

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

    private String[] target(FieldDefinition field) {
        return (String[]) ReflectionTestUtils.invokeMethod(service, "resolveEnrichmentTarget", field);
    }

    private FieldDefinition field(String code, String dataType, Map<String, Object> extraProps) {
        FieldDefinition f = new FieldDefinition();
        f.setCode(code);
        f.setDataType(dataType);
        f.setExtraProps(extraProps);
        return f;
    }

    @Test
    @DisplayName("userselect (string) resolves to the sys_user target")
    void userselectResolvesToSysUser() {
        FieldDefinition f = field("sc_assignee", "string", Map.of("renderComponent", "userselect"));
        assertArrayEquals(new String[] {"sys_user", null}, target(f));
    }

    @Test
    @DisplayName("organizationselect (string) resolves to the org_department target")
    void organizationSelectResolvesToOrgDepartment() {
        FieldDefinition f = field("sc_department", "string", Map.of("renderComponent", "organizationselect"));
        assertArrayEquals(new String[] {"org_department", "org_dept_name"}, target(f));
    }

    @Test
    @DisplayName("reference field with a refTarget resolves via the canonical ref target")
    void referenceResolvesViaCanonicalRefTarget() {
        FieldDefinition f = field("sc_owner_user", "reference",
                Map.of("refTarget", Map.of("targetEntity", "sys_user")));
        assertArrayEquals(new String[] {"sys_user", null}, target(f));
    }

    @Test
    @DisplayName("memberpicker (multi-value) is NOT enriched")
    void memberPickerIsNotEnriched() {
        FieldDefinition f = field("sc_team_members", "string", Map.of("renderComponent", "memberpicker"));
        assertNull(target(f));
    }

    @Test
    @DisplayName("a plain string field with no renderComponent/refTarget is NOT enriched")
    void plainFieldIsNotEnriched() {
        assertNull(target(field("sc_name", "string", Map.of())));
        assertNull(target(field("sc_name", "string", null)));
    }
}

package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.file.service.FileService;
import com.auraboot.framework.meta.ddl.TableMetadataService;
import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.mapper.MetaModelMapper;
import com.auraboot.framework.meta.service.*;
import com.auraboot.framework.meta.service.executor.ExecutorRegistry;
import com.auraboot.framework.permission.service.FieldPermissionService;
import com.auraboot.framework.application.tenant.MetaContext;
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
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

    @BeforeEach
    void setContext() {
        MetaContext.setContext(1L, 2L, "user-pid", "tester");
    }

    @AfterEach
    void clearContext() {
        MetaContext.clear();
    }

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

    private String[] displayExpr(String targetModelCode, String displayField) {
        return (String[]) ReflectionTestUtils.invokeMethod(
                service, "resolveDisplayColumnExpression", Optional.empty(), targetModelCode, displayField);
    }

    @Test
    @DisplayName("system-table reference always uses the safe COALESCE display expression, ignoring any configured displayField")
    void systemTableAlwaysUsesCoalesceRegardlessOfDisplayField() {
        // A configured displayField that is not a real ab_user column (username, displayName) must
        // NOT be spliced into the SQL as a raw column — it would fail the enrichment query. All
        // values, including null and real columns, resolve to the COALESCE expression aliased as
        // display_value. Deleting the system-table branch in resolveDisplayColumnExpression fails
        // this test (the bogus values would come back as raw columns).
        for (String df : new String[] {null, "username", "displayName", "nick_name", "email"}) {
            String[] r = displayExpr("sys_user", df);
            assertEquals("display_value", r[1], "alias for displayField=" + df);
            assertTrue(r[0].startsWith("COALESCE("), "expression for displayField=" + df + " was " + r[0]);
            assertFalse(r[0].contains("username"), "must not leak a raw username column for displayField=" + df);
        }
    }

    @Test
    @DisplayName("configured displayName on sys_user uses the canonical physical display expression")
    void systemUserLogicalDisplayNameNeverBecomesAnUnvalidatedColumn() {
        FieldDefinition owner = field("crm_acc_owner", "reference", Map.of(
                "refTarget", Map.of("targetEntity", "sys_user", "displayField", "displayName")));
        owner.setColumnName("crm_acc_owner");
        ModelDefinition source = ModelDefinition.builder()
                .code("crm_account_common")
                .tableName("mt_crm_account_common")
                .fields(List.of(owner))
                .build();
        when(metadataService.getModelDefinition("crm_account_common")).thenReturn(Optional.of(source));
        when(metadataService.getModelDefinition("sys_user")).thenReturn(Optional.empty());
        when(dataPermissionEngine.getFieldMaskRules(1L, "sys_user", 2L)).thenReturn(List.of());
        when(dynamicDataMapper.selectByQuery(argThat(sql ->
                        sql.contains("COALESCE(NULLIF(nick_name, ''), NULLIF(user_name, ''), email) AS display_value")
                                && !sql.contains(" displayName ")), anyMap()))
                .thenReturn(List.of(Map.of("pid", "owner-1", "display_value", "Alice")));

        Map<String, Object> record = new HashMap<>();
        record.put("crm_acc_owner", "owner-1");
        List<Map<String, Object>> records = new ArrayList<>(List.of(record));
        ReflectionTestUtils.invokeMethod(service, "enrichReferenceDisplayFields",
                "crm_account_common", records);

        assertEquals("Alice", record.get("crm_acc_owner_display"));
        verify(dynamicDataMapper).selectByQuery(argThat(sql -> sql.contains(" AS display_value")), anyMap());
    }
}

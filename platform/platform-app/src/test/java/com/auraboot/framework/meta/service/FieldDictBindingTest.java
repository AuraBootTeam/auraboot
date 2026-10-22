package com.auraboot.framework.meta.service;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.exception.ValidationException;
import com.auraboot.framework.meta.constant.DataType;
import com.auraboot.framework.meta.constant.Status;
import com.auraboot.framework.meta.dto.DictDTO;
import com.auraboot.framework.meta.dto.MetaFieldDTO;
import com.auraboot.framework.meta.entity.Dict;
import com.auraboot.framework.meta.entity.Field;
import com.auraboot.framework.meta.entity.FieldDictBinding;
import com.auraboot.framework.meta.entity.payload.ExtensionBean;
import com.auraboot.framework.meta.entity.payload.FieldFeatureBean;
import com.auraboot.framework.meta.mapper.MetaFieldDictBindingMapper;
import com.auraboot.framework.meta.mapper.MetaFieldMapper;
import com.auraboot.framework.tenant.dao.entity.Tenant;
import com.auraboot.framework.tenant.dao.entity.TenantMember;
import com.auraboot.framework.tenant.service.TenantMemberService;
import com.auraboot.framework.tenant.service.TenantService;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for field-dictionary binding functionality.
 * 
 * Tests Property 4: Dictionary binding exclusivity
 * - For any field, at most one dictionary can be bound at any time
 * - Binding a new dictionary should replace the existing binding
 * - Unbinding should remove the binding completely
 * 
 * Uses real database, no mocking.
 * 
 * @author AuraBoot Team
 * @since 2.1.2
 */
@Slf4j
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Field-Dictionary Binding Integration Tests")
class FieldDictBindingTest {

    @Autowired
    private MetaFieldService metaFieldService;

    @Autowired
    private DictService dictService;

    @Autowired
    private MetaFieldMapper metaFieldMapper;

    @Autowired
    private MetaFieldDictBindingMapper fieldDictBindingMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserService userService;

    @Autowired
    private TenantService tenantService;

    @Autowired
    private TenantMemberService tenantMemberService;

    // Test context
    private User testUser;
    private Tenant testTenant;
    
    // Test data tracking for cleanup
    private final List<String> createdFieldPids = Collections.synchronizedList(new ArrayList<>());
    private final List<String> createdDictCodes = Collections.synchronizedList(new ArrayList<>());
    
    private boolean contextInitialized = false;

    @BeforeAll
    void setupContext() {
        setupTenantContext();
        contextInitialized = true;
        log.info("Test context initialized: tenant={}, user={}", testTenant.getId(), testUser.getId());
    }

    @BeforeEach
    void ensureContext() {
        if (!contextInitialized) {
            setupTenantContext();
            contextInitialized = true;
        }
        // Ensure MetaContext is set for each test
        MetaContext.setContext(testTenant.getId(), testUser.getId(), testUser.getPid(), testUser.getUserName());
    }

    @AfterAll
    void cleanup() {
        log.info("=== Cleaning up test data ===");
        
        // Ensure context is set for cleanup
        if (testTenant != null && testUser != null) {
            MetaContext.setContext(testTenant.getId(), testUser.getId(), testUser.getPid(), testUser.getUserName());
        }
        
        // Clean up bindings first (foreign key constraint)
        for (String fieldPid : createdFieldPids) {
            try {
                fieldDictBindingMapper.deleteByFieldPid(fieldPid, testTenant.getId());
            } catch (Exception e) {
                log.debug("Failed to cleanup binding for field: {}", fieldPid);
            }
        }
        
        // Clean up fields
        for (String fieldPid : createdFieldPids) {
            try {
                jdbcTemplate.update("DELETE FROM ab_meta_field WHERE pid = ? AND tenant_id = ?", 
                    fieldPid, testTenant.getId());
            } catch (Exception e) {
                log.debug("Failed to cleanup field: {}", fieldPid);
            }
        }
        
        // Clean up dictionaries
        for (String dictCode : createdDictCodes) {
            try {
                jdbcTemplate.update("DELETE FROM ab_dict WHERE code = ? AND tenant_id = ?", 
                    dictCode, testTenant.getId());
            } catch (Exception e) {
                log.debug("Failed to cleanup dict: {}", dictCode);
            }
        }
        
        createdFieldPids.clear();
        createdDictCodes.clear();
        log.info("Test cleanup completed");
    }

    // ==================== Property 4: Dictionary Binding Exclusivity ====================

    @Test
    @Order(1)
    @DisplayName("Property 4.1: Field can bind to one dictionary")
    void testBindDictionary_Success() {
        // Given
        Field field = createTestField("enum");
        Dict dict = createTestDict();
        
        // When
        boolean result = metaFieldService.bindDictionary(field.getPid(), dict.getCode());
        
        // Then
        assertTrue(result, "Binding should succeed");
        
        // Verify binding exists
        FieldDictBinding binding = fieldDictBindingMapper.findByFieldPid(field.getPid(), testTenant.getId());
        assertNotNull(binding, "Binding should exist in database");
        assertEquals(dict.getCode(), binding.getDictCode(), "Dict code should match");
        
        log.info("✓ Property 4.1: Field successfully bound to dictionary");
    }

    @Test
    @Order(2)
    @DisplayName("Property 4.2: Binding to unsupported field type should fail")
    void testBindDictionary_UnsupportedFieldType_ShouldFail() {
        // Given - BOOLEAN is not in the allowed types (ENUM, STRING, INTEGER, ARRAY)
        Field field = createTestField("boolean");
        Dict dict = createTestDict();
        
        // When & Then
        ValidationException exception = assertThrows(
            ValidationException.class,
            () -> metaFieldService.bindDictionary(field.getPid(), dict.getCode())
        );
        
        assertTrue(exception.getMessage().contains("不支持绑定字典"),
            "Error message should mention unsupported type");
        
        // Verify no binding was created
        FieldDictBinding binding = fieldDictBindingMapper.findByFieldPid(field.getPid(), testTenant.getId());
        assertNull(binding, "No binding should exist for unsupported field type");
        
        log.info("✓ Property 4.2: Unsupported field type binding correctly rejected");
    }

    @Test
    @Order(3)
    @DisplayName("Property 4.3: Binding to non-existent field should fail")
    void testBindDictionary_FieldNotFound_ShouldFail() {
        // Given
        String nonExistentFieldPid = "non-existent-field-" + UniqueIdGenerator.generate();
        Dict dict = createTestDict();
        
        // When & Then
        ValidationException exception = assertThrows(
            ValidationException.class,
            () -> metaFieldService.bindDictionary(nonExistentFieldPid, dict.getCode())
        );
        
        assertTrue(exception.getMessage().contains("不存在"),
            "Error message should indicate field not found");
        
        log.info("✓ Property 4.3: Non-existent field binding correctly rejected");
    }

    @Test
    @Order(4)
    @DisplayName("Property 4.4: Binding to non-existent dictionary should fail")
    void testBindDictionary_DictNotFound_ShouldFail() {
        // Given
        Field field = createTestField("enum");
        String nonExistentDictCode = "non-existent-dict-" + System.currentTimeMillis();
        
        // When & Then
        ValidationException exception = assertThrows(
            ValidationException.class,
            () -> metaFieldService.bindDictionary(field.getPid(), nonExistentDictCode)
        );
        
        assertTrue(exception.getMessage().contains("不存在"),
            "Error message should indicate dictionary not found");
        
        log.info("✓ Property 4.4: Non-existent dictionary binding correctly rejected");
    }

    @Test
    @Order(5)
    @DisplayName("Property 4.5: Rebinding should update existing binding")
    void testBindDictionary_UpdateExisting() {
        // Given
        Field field = createTestField("enum");
        Dict dict1 = createTestDict();
        Dict dict2 = createTestDict();
        
        // First binding
        boolean result1 = metaFieldService.bindDictionary(field.getPid(), dict1.getCode());
        assertTrue(result1, "First binding should succeed");
        
        // Verify first binding
        FieldDictBinding binding1 = fieldDictBindingMapper.findByFieldPid(field.getPid(), testTenant.getId());
        assertNotNull(binding1);
        assertEquals(dict1.getCode(), binding1.getDictCode());
        
        // When: Rebind to different dictionary
        boolean result2 = metaFieldService.bindDictionary(field.getPid(), dict2.getCode());
        
        // Then
        assertTrue(result2, "Rebinding should succeed");
        
        // Verify binding was updated (not duplicated)
        FieldDictBinding binding2 = fieldDictBindingMapper.findByFieldPid(field.getPid(), testTenant.getId());
        assertNotNull(binding2);
        assertEquals(dict2.getCode(), binding2.getDictCode(), "Binding should be updated to new dict");
        assertEquals(binding1.getId(), binding2.getId(), "Should be same binding record (updated)");
        
        log.info("✓ Property 4.5: Rebinding correctly updates existing binding");
    }

    @Test
    @Order(6)
    @DisplayName("Property 4.6: Unbind should remove binding")
    void testUnbindDictionary_Success() {
        // Given
        Field field = createTestField("enum");
        Dict dict = createTestDict();
        
        // Create binding first
        metaFieldService.bindDictionary(field.getPid(), dict.getCode());
        
        // Verify binding exists
        FieldDictBinding bindingBefore = fieldDictBindingMapper.findByFieldPid(field.getPid(), testTenant.getId());
        assertNotNull(bindingBefore, "Binding should exist before unbind");
        
        // When
        boolean result = metaFieldService.unbindDictionary(field.getPid());
        
        // Then
        assertTrue(result, "Unbinding should succeed");
        
        // Verify binding is removed
        FieldDictBinding bindingAfter = fieldDictBindingMapper.findByFieldPid(field.getPid(), testTenant.getId());
        assertNull(bindingAfter, "Binding should be removed after unbind");
        
        log.info("✓ Property 4.6: Unbind correctly removes binding");
    }

    @Test
    @Order(7)
    @DisplayName("Property 4.7: Unbind non-existent binding should return false")
    void testUnbindDictionary_NoBinding() {
        // Given
        Field field = createTestField("enum");
        // No binding created
        
        // When
        boolean result = metaFieldService.unbindDictionary(field.getPid());
        
        // Then
        assertFalse(result, "Unbinding non-existent binding should return false");
        
        log.info("✓ Property 4.7: Unbind non-existent binding returns false");
    }

    @Test
    @Order(8)
    @DisplayName("Property 4.8: Get bound dictionary should return correct dict")
    void testGetBoundDictionary_Success() {
        // Given
        Field field = createTestField("enum");
        Dict dict = createTestDict();
        metaFieldService.bindDictionary(field.getPid(), dict.getCode());
        
        // When
        Optional<DictDTO> result = metaFieldService.getBoundDictionary(field.getPid());
        
        // Then
        assertTrue(result.isPresent(), "Should return bound dictionary");
        assertEquals(dict.getCode(), result.get().getCode(), "Dictionary code should match");
        
        log.info("✓ Property 4.8: Get bound dictionary returns correct dict");
    }

    @Test
    @Order(9)
    @DisplayName("Property 4.9: Get bound dictionary for unbound field should return empty")
    void testGetBoundDictionary_NoBinding() {
        // Given
        Field field = createTestField("enum");
        // No binding created
        
        // When
        Optional<DictDTO> result = metaFieldService.getBoundDictionary(field.getPid());
        
        // Then
        assertFalse(result.isPresent(), "Should return empty for unbound field");
        
        log.info("✓ Property 4.9: Get bound dictionary for unbound field returns empty");
    }

    @Test
    @Order(10)
    @DisplayName("Property 4.10: Bind-unbind-bind cycle works correctly")
    void testBindUnbindBindCycle() {
        // Given
        Field field = createTestField("enum");
        Dict dict = createTestDict();
        
        // Step 1: Bind
        boolean bindResult1 = metaFieldService.bindDictionary(field.getPid(), dict.getCode());
        assertTrue(bindResult1, "Initial binding should succeed");
        assertNotNull(fieldDictBindingMapper.findByFieldPid(field.getPid(), testTenant.getId()));
        
        // Step 2: Unbind
        boolean unbindResult = metaFieldService.unbindDictionary(field.getPid());
        assertTrue(unbindResult, "Unbinding should succeed");
        assertNull(fieldDictBindingMapper.findByFieldPid(field.getPid(), testTenant.getId()));
        
        // Step 3: Bind again
        boolean bindResult2 = metaFieldService.bindDictionary(field.getPid(), dict.getCode());
        assertTrue(bindResult2, "Re-binding should succeed");
        assertNotNull(fieldDictBindingMapper.findByFieldPid(field.getPid(), testTenant.getId()));
        
        log.info("✓ Property 4.10: Bind-unbind-bind cycle works correctly");
    }

    @Test
    @Order(11)
    @DisplayName("Property 4.11: Multiple fields can bind to same dictionary")
    void testMultipleFieldsBindToSameDict() {
        // Given
        Field field1 = createTestField("enum");
        Field field2 = createTestField("enum");
        Dict dict = createTestDict();
        
        // When
        boolean result1 = metaFieldService.bindDictionary(field1.getPid(), dict.getCode());
        boolean result2 = metaFieldService.bindDictionary(field2.getPid(), dict.getCode());
        
        // Then
        assertTrue(result1, "First field binding should succeed");
        assertTrue(result2, "Second field binding should succeed");
        
        // Verify both bindings exist
        FieldDictBinding binding1 = fieldDictBindingMapper.findByFieldPid(field1.getPid(), testTenant.getId());
        FieldDictBinding binding2 = fieldDictBindingMapper.findByFieldPid(field2.getPid(), testTenant.getId());
        
        assertNotNull(binding1);
        assertNotNull(binding2);
        assertEquals(dict.getCode(), binding1.getDictCode());
        assertEquals(dict.getCode(), binding2.getDictCode());
        
        log.info("✓ Property 4.11: Multiple fields can bind to same dictionary");
    }

    @Test
    @Order(12)
    @DisplayName("Property 4.12: Unsupported field types cannot bind")
    void testUnsupportedFieldTypesCannotBind() {
        // Given - these types are NOT in the allowed list (ENUM, STRING, INTEGER, ARRAY)
        String[] unsupportedTypes = {"boolean", "date", "decimal", "text", "reference"};
        Dict dict = createTestDict();
        
        for (String dataType : unsupportedTypes) {
            Field field = createTestField(dataType);
            
            // When & Then
            ValidationException exception = assertThrows(
                ValidationException.class,
                () -> metaFieldService.bindDictionary(field.getPid(), dict.getCode()),
                "Should fail for " + dataType + " type"
            );
            
            assertTrue(exception.getMessage().contains("不支持绑定字典"),
                "Error should mention unsupported type for " + dataType);
        }
        
        log.info("✓ Property 4.12: All unsupported field types correctly rejected");
    }

    @Test
    @Order(13)
    @DisplayName("Property 4.13: Supported field types can bind")
    void testSupportedFieldTypesCanBind() {
        // Given - these types ARE in the allowed list (ENUM, STRING, INTEGER, ARRAY)
        String[] supportedTypes = {"enum", "string", "integer", "array"};
        
        for (String dataType : supportedTypes) {
            Field field = createTestField(dataType);
            Dict dict = createTestDict();
            
            // When
            boolean result = metaFieldService.bindDictionary(field.getPid(), dict.getCode());
            
            // Then
            assertTrue(result, "Binding should succeed for " + dataType + " type");
            
            // Verify binding exists
            FieldDictBinding binding = fieldDictBindingMapper.findByFieldPid(field.getPid(), testTenant.getId());
            assertNotNull(binding, "Binding should exist for " + dataType + " type");
        }
        
        log.info("✓ Property 4.13: All supported field types can bind");
    }

    // ==================== Helper Methods ====================

    private void setupTenantContext() {
        try {
            // Create or find test user
            String testEmail = "field-dict-binding-test@auraboot.com";
            testUser = userService.findByEmail(testEmail);
            if (testUser == null) {
                testUser = userService.signUp(testEmail, "test-password-123");
            }

            // Create or find test tenant
            String testTenantName = "field-dict-binding-test-tenant";
            testTenant = tenantService.findByName(testTenantName);
            if (testTenant == null) {
                Tenant tenant = new Tenant();
                tenant.setPid(UniqueIdGenerator.generate());
                tenant.setName(testTenantName);
                tenant.setDisplayName("Field Dict Binding Test Tenant");
                tenant.setStatus("active");
                tenant.setContactEmail("admin@field-dict-test.com");
                tenant.setDescription("Test tenant for FieldDictBinding integration tests");
                tenant.setDeletedFlag(false);
                tenant.setCreatedAt(Instant.now());
                tenant.setUpdatedAt(Instant.now());
                testTenant = tenantService.createTenant(tenant);
            }

            // Ensure tenant member relationship exists
            TenantMember member = tenantMemberService.findByTenantIdAndUserId(testTenant.getId(), testUser.getId());
            if (member == null) {
                tenantMemberService.addMember(testUser.getId(), testTenant.getId(), "active");
            }

            // Set MetaContext
            MetaContext.setContext(testTenant.getId(), testUser.getId(), testUser.getPid(), testUser.getUserName());
            
        } catch (Exception e) {
            throw new RuntimeException("Failed to setup tenant context", e);
        }
    }

    private Field createTestField(String dataType) {
        String fieldCode = "test_field_" + System.currentTimeMillis() + "_" + UUID.randomUUID().toString().substring(0, 8);
        String fieldPid = UniqueIdGenerator.generate();
        
        Field field = new Field();
        field.setPid(fieldPid);
        field.setTenantId(testTenant.getId());
        field.setCode(fieldCode);
        field.setDataType(dataType);
        field.setVersion(1);
        field.setIsCurrent(true);
        field.setStatus(Status.PUBLISHED.getCode());
        field.setCreatedAt(Instant.now());
        field.setUpdatedAt(Instant.now());
        field.setDeletedFlag(false);
        
        FieldFeatureBean feature = new FieldFeatureBean();
        feature.setRequired(false);
        field.setFeature(feature);
        
        ExtensionBean extension = new ExtensionBean();
        Map<String, Object> extensionMap = new HashMap<>();
        extensionMap.put("displayName", fieldCode.toUpperCase());
        extensionMap.put("description", "Test field for binding tests");
        extension.setExtension(extensionMap);
        field.setExtension(extension);
        
        metaFieldMapper.insert(field);
        createdFieldPids.add(fieldPid);
        
        log.debug("Created test field: pid={}, code={}, dataType={}", fieldPid, fieldCode, dataType);
        return field;
    }

    private Dict createTestDict() {
        String dictCode = "test_dict_" + System.currentTimeMillis() + "_" + UUID.randomUUID().toString().substring(0, 8);
        String dictPid = UniqueIdGenerator.generate();
        
        Dict dict = new Dict();
        dict.setPid(dictPid);
        dict.setTenantId(testTenant.getId());
        dict.setCode(dictCode);
        dict.setName("Test Dictionary " + dictCode);
        dict.setDictType("static");
        dict.setVersion(1);
        dict.setIsCurrent(true);
        dict.setStatus(Status.PUBLISHED.getCode());
        dict.setCreatedAt(Instant.now());
        dict.setUpdatedAt(Instant.now());
        
        // Insert directly via JDBC to avoid service layer complexity
        jdbcTemplate.update(
            "INSERT INTO ab_dict (pid, tenant_id, code, name, dict_type, version, is_current, status, created_at, updated_at) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            dict.getPid(), dict.getTenantId(), dict.getCode(), dict.getName(), dict.getDictType(),
            dict.getVersion(), dict.getIsCurrent(), dict.getStatus(), 
            java.sql.Timestamp.from(dict.getCreatedAt()), java.sql.Timestamp.from(dict.getUpdatedAt())
        );
        
        createdDictCodes.add(dictCode);
        
        log.debug("Created test dict: pid={}, code={}", dictPid, dictCode);
        return dict;
    }
}

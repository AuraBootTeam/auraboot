package com.auraboot.framework.integration.meta;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.exception.ValidationException;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.dto.DictCreateRequest;
import com.auraboot.framework.meta.dto.DictDTO;
import com.auraboot.framework.meta.dto.MetaFieldCreateRequest;
import com.auraboot.framework.meta.dto.MetaFieldDTO;
import com.auraboot.framework.meta.entity.FieldDictBinding;
import com.auraboot.framework.meta.mapper.MetaFieldDictBindingMapper;
import com.auraboot.framework.meta.service.DictService;
import com.auraboot.framework.meta.service.MetaFieldService;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Dictionary binding integration tests
 * 
 * Tests dictionary binding, unbinding, and error handling
 * 
 * @author AuraBoot Team
 * @since 2.1.2
 */
@Slf4j
@Transactional
@Rollback(true)
@DisplayName("Dictionary Binding Integration Tests")
public class DictBindingIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private MetaFieldService fieldService;

    @Autowired
    private DictService dictService;

    @Autowired
    private MetaFieldDictBindingMapper bindingMapper;

    @Test
    @DisplayName("Test bind dictionary to ENUM field")
    void testBindDictionaryToEnumField() {
        // Given: ENUM type field and dictionary
        String fieldCode = "enum_field_" + System.currentTimeMillis();
        String dictCode = "test_dict_" + System.currentTimeMillis();
        
        // Create ENUM field
        MetaFieldCreateRequest fieldRequest = new MetaFieldCreateRequest();
        fieldRequest.setCode(fieldCode);
        fieldRequest.setDataType("enum");
       
        MetaFieldDTO field = fieldService.create(fieldRequest);
        assertNotNull(field);
        
        // Create dictionary
        DictCreateRequest dictRequest = new DictCreateRequest();
        dictRequest.setCode(dictCode);
        dictRequest.setName("Test Dictionary");
        dictRequest.setDictType("static");
        dictRequest.setSourceType("static");
         
        
        DictDTO dict = dictService.create(dictRequest);
        assertNotNull(dict);

        // When: Bind dictionary to field
        boolean bindResult = fieldService.bindDictionary(field.getPid(), dictCode);

        // Then: Binding should succeed
        assertTrue(bindResult, "Dictionary binding should succeed");
        
        // Verify binding exists in database
        FieldDictBinding binding = bindingMapper.findByFieldPid(
            field.getPid(),
            MetaContext.getCurrentTenantId()
                  
                 
        );
        
        assertNotNull(binding, "Binding should exist in database");
        assertEquals(field.getPid(), binding.getFieldPid());
        assertEquals(dictCode, binding.getDictCode());
        assertEquals(field.getId(), binding.getFieldId());
        assertEquals(dict.getId(), binding.getDictId());
        
        log.info("Dictionary binding test passed: field={}, dict={}", fieldCode, dictCode);
    }

    @Test
    @DisplayName("Test unbind dictionary from field")
    void testUnbindDictionaryFromField() {
        // Given: Field with dictionary binding
        String fieldCode = "enum_field_unbind_" + System.currentTimeMillis();
        String dictCode = "test_dict_unbind_" + System.currentTimeMillis();
        
        // Create field and dictionary
        MetaFieldCreateRequest fieldRequest = new MetaFieldCreateRequest();
        fieldRequest.setCode(fieldCode);
        fieldRequest.setDataType("enum");
        
        
        MetaFieldDTO field = fieldService.create(fieldRequest);
        
        DictCreateRequest dictRequest = new DictCreateRequest();
        dictRequest.setCode(dictCode);
        dictRequest.setName("Test Dictionary");
        dictRequest.setDictType("static");
        dictRequest.setSourceType("static");
        
        
        dictService.create(dictRequest);
        
        // Bind dictionary
        fieldService.bindDictionary(field.getPid(), dictCode);
        
        // Verify binding exists
        Optional<DictDTO> boundDict = fieldService.getBoundDictionary(field.getPid());
        assertTrue(boundDict.isPresent(), "Dictionary should be bound");

        // When: Unbind dictionary
        boolean unbindResult = fieldService.unbindDictionary(field.getPid());

        // Then: Unbinding should succeed
        assertTrue(unbindResult, "Dictionary unbinding should succeed");
        
        // Verify binding no longer exists
        Optional<DictDTO> afterUnbind = fieldService.getBoundDictionary(field.getPid());
        assertFalse(afterUnbind.isPresent(), "Dictionary should no longer be bound");
        
        log.info("Dictionary unbinding test passed: field={}", fieldCode);
    }

    @Test
    @DisplayName("Test bind non-existent dictionary")
    void testBindNonExistentDictionary() {
        // Given: ENUM field
        String fieldCode = "enum_field_nodict_" + System.currentTimeMillis();
        
        MetaFieldCreateRequest fieldRequest = new MetaFieldCreateRequest();
        fieldRequest.setCode(fieldCode);
        fieldRequest.setDataType("enum");

        
        MetaFieldDTO field = fieldService.create(fieldRequest);
        assertNotNull(field);

        // When: Try to bind non-existent dictionary
        String nonExistentDictCode = "non_existent_dict_" + System.currentTimeMillis();

        // Then: Should throw exception
        assertThrows(ValidationException.class, () -> {
            fieldService.bindDictionary(field.getPid(), nonExistentDictCode);
        }, "Should throw ValidationException for non-existent dictionary");
        
        log.info("Non-existent dictionary binding test passed");
    }

    @Test
    @DisplayName("Test bind dictionary to non-ENUM field")
    void testBindDictionaryToNonEnumField() {
        // Given: STRING type field (not ENUM, but still allowed for dictionary binding)
        String fieldCode = "string_field_" + System.currentTimeMillis();
        String dictCode = "test_dict_string_" + System.currentTimeMillis();
        
        // Create STRING field
        MetaFieldCreateRequest fieldRequest = new MetaFieldCreateRequest();
        fieldRequest.setCode(fieldCode);
        fieldRequest.setDataType("string");
          
        
        MetaFieldDTO field = fieldService.create(fieldRequest);
        
        // Create dictionary
        DictCreateRequest dictRequest = new DictCreateRequest();
        dictRequest.setCode(dictCode);
        dictRequest.setName("Test Dictionary");
        dictRequest.setDictType("static");
        dictRequest.setSourceType("static");
          
        
        dictService.create(dictRequest);

        // When: Bind dictionary to STRING field (should succeed as STRING is allowed)
        boolean bindResult = fieldService.bindDictionary(field.getPid(), dictCode);

        // Then: Binding should succeed (STRING type supports dictionary binding)
        assertTrue(bindResult, "Dictionary binding to STRING field should succeed");
        
        // Verify binding exists
        Optional<DictDTO> boundDict = fieldService.getBoundDictionary(field.getPid());
        assertTrue(boundDict.isPresent(), "Dictionary should be bound");
        assertEquals(dictCode, boundDict.get().getCode());
        
        log.info("STRING field dictionary binding test passed");
    }

    @Test
    @DisplayName("Test update existing dictionary binding")
    void testUpdateExistingDictionaryBinding() {
        // Given: Field with dictionary binding
        String fieldCode = "enum_field_update_" + System.currentTimeMillis();
        String dictCode1 = "test_dict1_" + System.currentTimeMillis();
        String dictCode2 = "test_dict2_" + System.currentTimeMillis();
        
        // Create field
        MetaFieldCreateRequest fieldRequest = new MetaFieldCreateRequest();
        fieldRequest.setCode(fieldCode);
        fieldRequest.setDataType("enum");
          
        
        MetaFieldDTO field = fieldService.create(fieldRequest);
        
        // Create first dictionary
        DictCreateRequest dictRequest1 = new DictCreateRequest();
        dictRequest1.setCode(dictCode1);
        dictRequest1.setName("Test Dictionary 1");
        dictRequest1.setDictType("static");
        dictRequest1.setSourceType("static");

        
        dictService.create(dictRequest1);
        
        // Create second dictionary
        DictCreateRequest dictRequest2 = new DictCreateRequest();
        dictRequest2.setCode(dictCode2);
        dictRequest2.setName("Test Dictionary 2");
        dictRequest2.setDictType("static");
        dictRequest2.setSourceType("static");

        
        dictService.create(dictRequest2);
        
        // Bind first dictionary
        fieldService.bindDictionary(field.getPid(), dictCode1);
        
        // Verify first binding
        Optional<DictDTO> boundDict1 = fieldService.getBoundDictionary(field.getPid());
        assertTrue(boundDict1.isPresent());
        assertEquals(dictCode1, boundDict1.get().getCode());

        // When: Bind second dictionary (should update existing binding)
        boolean updateResult = fieldService.bindDictionary(field.getPid(), dictCode2);

        // Then: Update should succeed
        assertTrue(updateResult, "Dictionary binding update should succeed");
        
        // Verify binding was updated
        Optional<DictDTO> boundDict2 = fieldService.getBoundDictionary(field.getPid());
        assertTrue(boundDict2.isPresent(), "Dictionary should be bound");
        assertEquals(dictCode2, boundDict2.get().getCode(), "Should be bound to second dictionary");
        
        log.info("Dictionary binding update test passed: field={}, dict1={}, dict2={}", 
            fieldCode, dictCode1, dictCode2);
    }

    @Test
    @DisplayName("Test unbind non-existent binding")
    void testUnbindNonExistentBinding() {
        // Given: Field without dictionary binding
        String fieldCode = "enum_field_nobinding_" + System.currentTimeMillis();
        
        MetaFieldCreateRequest fieldRequest = new MetaFieldCreateRequest();
        fieldRequest.setCode(fieldCode);
        fieldRequest.setDataType("enum");
          
        
        MetaFieldDTO field = fieldService.create(fieldRequest);

        // When: Try to unbind non-existent binding
        boolean unbindResult = fieldService.unbindDictionary(field.getPid());

        // Then: Should return false (no binding to unbind)
        assertFalse(unbindResult, "Should return false when no binding exists");
        
        log.info("Non-existent binding unbind test passed");
    }

    @Test
    @DisplayName("Test get bound dictionary")
    void testGetBoundDictionary() {
        // Given: Field with dictionary binding
        String fieldCode = "enum_field_get_" + System.currentTimeMillis();
        String dictCode = "test_dict_get_" + System.currentTimeMillis();
        
        // Create field
        MetaFieldCreateRequest fieldRequest = new MetaFieldCreateRequest();
        fieldRequest.setCode(fieldCode);
        fieldRequest.setDataType("enum");
          
        
        MetaFieldDTO field = fieldService.create(fieldRequest);
        
        // Create dictionary
        DictCreateRequest dictRequest = new DictCreateRequest();
        dictRequest.setCode(dictCode);
        dictRequest.setName("Test Dictionary");
        dictRequest.setDictType("static");
        dictRequest.setSourceType("static");
          
        
        DictDTO createdDict = dictService.create(dictRequest);
        
        // Bind dictionary
        fieldService.bindDictionary(field.getPid(), dictCode);

        // When: Get bound dictionary
        Optional<DictDTO> boundDict = fieldService.getBoundDictionary(field.getPid());

        // Then: Should return the bound dictionary
        assertTrue(boundDict.isPresent(), "Bound dictionary should be present");
        assertEquals(dictCode, boundDict.get().getCode());
        assertEquals(createdDict.getId(), boundDict.get().getId());
        assertEquals("Test Dictionary", boundDict.get().getName());
        
        log.info("Get bound dictionary test passed: field={}, dict={}", fieldCode, dictCode);
    }

    @Test
    @DisplayName("Test get bound dictionary when none exists")
    void testGetBoundDictionaryWhenNoneExists() {
        // Given: Field without dictionary binding
        String fieldCode = "enum_field_nobound_" + System.currentTimeMillis();
        
        MetaFieldCreateRequest fieldRequest = new MetaFieldCreateRequest();
        fieldRequest.setCode(fieldCode);
        fieldRequest.setDataType("enum");
          
        
        MetaFieldDTO field = fieldService.create(fieldRequest);

        // When: Get bound dictionary
        Optional<DictDTO> boundDict = fieldService.getBoundDictionary(field.getPid());

        // Then: Should return empty
        assertFalse(boundDict.isPresent(), "Should return empty when no binding exists");
        
        log.info("Get non-existent bound dictionary test passed");
    }

    @Test
    @DisplayName("Test dictionary binding with tenant isolation")
    void testDictionaryBindingWithTenantIsolation() {
        // Given: Field and dictionary in current tenant
        String fieldCode = "enum_field_tenant_" + System.currentTimeMillis();
        String dictCode = "test_dict_tenant_" + System.currentTimeMillis();
        
        // Create field
        MetaFieldCreateRequest fieldRequest = new MetaFieldCreateRequest();
        fieldRequest.setCode(fieldCode);
        fieldRequest.setDataType("enum");
          
        
        MetaFieldDTO field = fieldService.create(fieldRequest);
        
        // Create dictionary
        DictCreateRequest dictRequest = new DictCreateRequest();
        dictRequest.setCode(dictCode);
        dictRequest.setName("Test Dictionary");
        dictRequest.setDictType("static");
        dictRequest.setSourceType("static");
          
        
        dictService.create(dictRequest);
        
        // Bind dictionary
        fieldService.bindDictionary(field.getPid(), dictCode);

        // When: Query binding with tenant context
        FieldDictBinding binding = bindingMapper.findByFieldPid(
            field.getPid(),
            MetaContext.getCurrentTenantId()
                  
                 
        );

        // Then: Binding should be found with correct tenant info
        assertNotNull(binding, "Binding should be found");
        assertEquals(MetaContext.getCurrentTenantId(), binding.getTenantId());
      
        
        log.info("Dictionary binding tenant isolation test passed");
    }

    @Test
    @DisplayName("Test dictionary binding lifecycle")
    void testDictionaryBindingLifecycle() {
        // Given: Field and dictionary
        String fieldCode = "enum_field_lifecycle_" + System.currentTimeMillis();
        String dictCode = "test_dict_lifecycle_" + System.currentTimeMillis();
        
        // Create field
        MetaFieldCreateRequest fieldRequest = new MetaFieldCreateRequest();
        fieldRequest.setCode(fieldCode);
        fieldRequest.setDataType("enum");
          
        
        MetaFieldDTO field = fieldService.create(fieldRequest);
        
        // Create dictionary
        DictCreateRequest dictRequest = new DictCreateRequest();
        dictRequest.setCode(dictCode);
        dictRequest.setName("Test Dictionary");
        dictRequest.setDictType("static");
        dictRequest.setSourceType("static");

        
        dictService.create(dictRequest);

        // Step 1: No binding initially
        Optional<DictDTO> initialDict = fieldService.getBoundDictionary(field.getPid());
        assertFalse(initialDict.isPresent(), "Should have no binding initially");

        // Step 2: Bind dictionary
        fieldService.bindDictionary(field.getPid(), dictCode);
        Optional<DictDTO> afterBind = fieldService.getBoundDictionary(field.getPid());
        assertTrue(afterBind.isPresent(), "Should have binding after bind");
        assertEquals(dictCode, afterBind.get().getCode());

        // Step 3: Unbind dictionary
        fieldService.unbindDictionary(field.getPid());
        Optional<DictDTO> afterUnbind = fieldService.getBoundDictionary(field.getPid());
        assertFalse(afterUnbind.isPresent(), "Should have no binding after unbind");

        // Step 4: Rebind dictionary
        fieldService.bindDictionary(field.getPid(), dictCode);
        Optional<DictDTO> afterRebind = fieldService.getBoundDictionary(field.getPid());
        assertTrue(afterRebind.isPresent(), "Should have binding after rebind");
        assertEquals(dictCode, afterRebind.get().getCode());
        
        log.info("Dictionary binding lifecycle test passed: field={}, dict={}", fieldCode, dictCode);
    }
}

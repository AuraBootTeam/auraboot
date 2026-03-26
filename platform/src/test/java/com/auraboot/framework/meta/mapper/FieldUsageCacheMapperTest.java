package com.auraboot.framework.meta.mapper;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.entity.Field;
import com.auraboot.framework.meta.entity.FieldUsageCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FieldUsageCacheMapper integration test
 * Tests field usage cache mapper operations
 */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("test")
@Transactional
@DisplayName("FieldUsageCacheMapper Test")
class FieldUsageCacheMapperTest extends BaseIntegrationTest {

    @Autowired
    private FieldUsageCacheMapper fieldUsageCacheMapper;

    @Autowired
    private MetaFieldMapper metaFieldMapper;

    private Field testField;
    private FieldUsageCache testCache;

    @BeforeEach
    void setUp() {
        super.setupTenantContext();
        
        // Create a real field first to satisfy foreign key constraint
        testField = createTestField();
        metaFieldMapper.insert(testField);
        
        // Now create cache with valid field_id
        testCache = createTestCache();
    }

    @Test
    @DisplayName("Test insert field usage cache")
    void testInsert() {
        // When
        int result = fieldUsageCacheMapper.insert(testCache);

        // Then
        assertEquals(1, result);
    }

    @Test
    @DisplayName("Test find by field ID")
    void testFindByFieldId() {
        // Given
        fieldUsageCacheMapper.insert(testCache);

        // When
        FieldUsageCache found = fieldUsageCacheMapper.findByFieldId(testCache.getFieldId());

        // Then
        assertNotNull(found);
        assertEquals(testCache.getFieldId(), found.getFieldId());
        assertEquals(testCache.getModelCount(), found.getModelCount());
    }

    @Test
    @DisplayName("Test find by tenant and field")
    void testFindByTenantAndField() {
        // Given
        fieldUsageCacheMapper.insert(testCache);

        // When
        FieldUsageCache found = fieldUsageCacheMapper.findByTenantAndField(
            testCache.getTenantId(), 
            testCache.getFieldId()
        );

        // Then
        assertNotNull(found);
        assertEquals(testCache.getTenantId(), found.getTenantId());
        assertEquals(testCache.getFieldId(), found.getFieldId());
    }

    @Test
    @DisplayName("Test find core fields")
    void testFindCoreFields() {
        // Given
        fieldUsageCacheMapper.insert(testCache);
        
        Field coreField = createTestField();
        coreField.setPid(UniqueIdGenerator.generate());
        coreField.setCode("core_field");
        metaFieldMapper.insert(coreField);
        
        FieldUsageCache coreCache = createTestCacheForField(coreField.getId());
        coreCache.setIsCoreField(true);
        coreCache.setUsageFrequency(BigDecimal.valueOf(90.0));
        fieldUsageCacheMapper.insert(coreCache);

        // When
        List<FieldUsageCache> coreFields = fieldUsageCacheMapper.findCoreFields(
            MetaContext.getCurrentTenantId()
        );

        // Then
        assertNotNull(coreFields);
        assertTrue(coreFields.stream().allMatch(FieldUsageCache::getIsCoreField));
    }

    @Test
    @DisplayName("Test find unused fields")
    void testFindUnusedFields() {
        // Given
        fieldUsageCacheMapper.insert(testCache);
        
        Field unusedField = createTestField();
        unusedField.setPid(UniqueIdGenerator.generate());
        unusedField.setCode("unused_field");
        metaFieldMapper.insert(unusedField);
        
        FieldUsageCache unusedCache = createTestCacheForField(unusedField.getId());
        unusedCache.setModelCount(0);
        unusedCache.setPageCount(0);
        unusedCache.setQueryCount(0);
        unusedCache.setTotalReferences(0);
        fieldUsageCacheMapper.insert(unusedCache);

        // When
        List<FieldUsageCache> unusedFields = fieldUsageCacheMapper.findUnusedFields(
            MetaContext.getCurrentTenantId()
        );

        // Then
        assertNotNull(unusedFields);
        assertTrue(unusedFields.stream().allMatch(cache -> cache.getTotalReferences() == 0));
    }

    @Test
    @DisplayName("Test find highly used fields")
    void testFindHighlyUsedFields() {
        // Given
        fieldUsageCacheMapper.insert(testCache);
        
        Field highlyUsedField = createTestField();
        highlyUsedField.setPid(UniqueIdGenerator.generate());
        highlyUsedField.setCode("highly_used_field");
        metaFieldMapper.insert(highlyUsedField);
        
        FieldUsageCache highlyUsedCache = createTestCacheForField(highlyUsedField.getId());
        highlyUsedCache.setUsageFrequency(BigDecimal.valueOf(75.0));
        fieldUsageCacheMapper.insert(highlyUsedCache);

        // When
        List<FieldUsageCache> highlyUsedFields = fieldUsageCacheMapper.findHighlyUsedFields(
            MetaContext.getCurrentTenantId(),
            50.0
        );

        // Then
        assertNotNull(highlyUsedFields);
        assertTrue(highlyUsedFields.stream()
            .allMatch(cache -> cache.getUsageFrequency().compareTo(BigDecimal.valueOf(50.0)) >= 0));
    }

    @Test
    @DisplayName("Test find by usage count range")
    void testFindByUsageCountRange() {
        // Given
        fieldUsageCacheMapper.insert(testCache);
        
        Field field1 = createTestField();
        field1.setPid(UniqueIdGenerator.generate());
        field1.setCode("field_1");
        metaFieldMapper.insert(field1);
        
        FieldUsageCache cache1 = createTestCacheForField(field1.getId());
        cache1.setTotalReferences(5);
        fieldUsageCacheMapper.insert(cache1);

        Field field2 = createTestField();
        field2.setPid(UniqueIdGenerator.generate());
        field2.setCode("field_2");
        metaFieldMapper.insert(field2);
        
        FieldUsageCache cache2 = createTestCacheForField(field2.getId());
        cache2.setTotalReferences(15);
        fieldUsageCacheMapper.insert(cache2);

        // When
        List<FieldUsageCache> results = fieldUsageCacheMapper.findByUsageCountRange(
            MetaContext.getCurrentTenantId(),
            3,
            10
        );

        // Then
        assertNotNull(results);
        assertTrue(results.stream()
            .allMatch(cache -> cache.getTotalReferences() >= 3 && cache.getTotalReferences() <= 10));
    }

    @Test
    @DisplayName("Test update usage statistics")
    void testUpdateUsageStatistics() {
        // Given
        fieldUsageCacheMapper.insert(testCache);

        // When
        int result = fieldUsageCacheMapper.updateUsageStatistics(
            testCache.getFieldId(),
            10,
            5,
            3,
            18,
            85.5
        );

        // Then
        assertEquals(1, result);

        // Verify update
        FieldUsageCache updated = fieldUsageCacheMapper.findByFieldId(testCache.getFieldId());
        assertNotNull(updated);
        assertEquals(10, updated.getModelCount());
        assertEquals(5, updated.getPageCount());
        assertEquals(3, updated.getQueryCount());
        assertEquals(18, updated.getTotalReferences());
    }

    @Test
    @DisplayName("Test upsert - insert new record")
    void testUpsertInsert() {
        // Given
        Field newField = createTestField();
        newField.setPid(UniqueIdGenerator.generate());
        newField.setCode("new_field");
        metaFieldMapper.insert(newField);
        
        FieldUsageCache newCache = createTestCacheForField(newField.getId());

        // When
        int result = fieldUsageCacheMapper.upsert(newCache);

        // Then
        assertTrue(result > 0);

        // Verify insert
        FieldUsageCache found = fieldUsageCacheMapper.findByFieldId(newCache.getFieldId());
        assertNotNull(found);
        assertEquals(newCache.getFieldId(), found.getFieldId());
    }

    @Test
    @DisplayName("Test upsert - update existing record")
    void testUpsertUpdate() {
        // Given
        fieldUsageCacheMapper.insert(testCache);

        // When - Update with new values
        testCache.setModelCount(20);
        testCache.setPageCount(10);
        int result = fieldUsageCacheMapper.upsert(testCache);

        // Then
        assertTrue(result > 0);

        // Verify update
        FieldUsageCache updated = fieldUsageCacheMapper.findByFieldId(testCache.getFieldId());
        assertNotNull(updated);
        assertEquals(20, updated.getModelCount());
        assertEquals(10, updated.getPageCount());
    }

    @Test
    @DisplayName("Test delete by field ID")
    void testDeleteByFieldId() {
        // Given
        fieldUsageCacheMapper.insert(testCache);
        Long fieldId = testCache.getFieldId();

        // When
        int result = fieldUsageCacheMapper.deleteByFieldId(fieldId);

        // Then
        assertEquals(1, result);

        // Verify deletion
        FieldUsageCache found = fieldUsageCacheMapper.findByFieldId(fieldId);
        assertNull(found);
    }

    @Test
    @DisplayName("Test find by tenant ID")
    void testFindByTenantId() {
        // Given
        fieldUsageCacheMapper.insert(testCache);

        Field field2 = createTestField();
        field2.setPid(UniqueIdGenerator.generate());
        field2.setCode("field_2");
        metaFieldMapper.insert(field2);
        
        FieldUsageCache cache2 = createTestCacheForField(field2.getId());
        fieldUsageCacheMapper.insert(cache2);

        // When
        List<FieldUsageCache> results = fieldUsageCacheMapper.findByTenantId(
            MetaContext.getCurrentTenantId()
        );

        // Then
        assertNotNull(results);
        assertTrue(results.size() >= 2);
        assertTrue(results.stream()
            .allMatch(cache -> cache.getTenantId().equals(MetaContext.getCurrentTenantId())));
    }

    @Test
    @DisplayName("Test count by tenant ID")
    void testCountByTenantId() {
        // Given
        fieldUsageCacheMapper.insert(testCache);

        Field field2 = createTestField();
        field2.setPid(UniqueIdGenerator.generate());
        field2.setCode("field_count_2");
        metaFieldMapper.insert(field2);
        
        FieldUsageCache cache2 = createTestCacheForField(field2.getId());
        fieldUsageCacheMapper.insert(cache2);

        // When
        long count = fieldUsageCacheMapper.countByTenantId(MetaContext.getCurrentTenantId());

        // Then
        assertTrue(count >= 2);
    }

    @Test
    @DisplayName("Test field usage cache entity methods")
    void testFieldUsageCacheEntityMethods() {
        // Given
        FieldUsageCache cache = FieldUsageCache.builder()
            .tenantId(1L)
            .fieldId(100L)
            .modelCount(5)
            .pageCount(3)
            .queryCount(2)
            .totalReferences(10)
            .isCoreField(false)
            .usageFrequency(BigDecimal.valueOf(60.0))
            .build();

        // Test getTotalUsageCount
        assertEquals(10, cache.getTotalUsageCount());

        // Test isUnused
        assertFalse(cache.isUnused());

        // Test isHighlyUsed
        assertTrue(cache.isHighlyUsed());

        // Test with unused cache
        FieldUsageCache unusedCache = FieldUsageCache.builder()
            .modelCount(0)
            .pageCount(0)
            .queryCount(0)
            .build();
        assertTrue(unusedCache.isUnused());

        // Test with low usage
        FieldUsageCache lowUsageCache = FieldUsageCache.builder()
            .usageFrequency(BigDecimal.valueOf(30.0))
            .build();
        assertFalse(lowUsageCache.isHighlyUsed());
    }

    /**
     * Create test field entity
     */
    private Field createTestField() {
        Field field = new Field();
        field.setPid(UniqueIdGenerator.generate());
        field.setCode("test_field");
        field.setDataType("string");
        field.setTenantId(MetaContext.getCurrentTenantId());
        field.setVersion(1);
        field.setIsCurrent(true);
        field.setStatus("published");
        field.setCreatedAt(Instant.now());
        field.setUpdatedAt(Instant.now());
        return field;
    }

    /**
     * Create test field usage cache
     */
    private FieldUsageCache createTestCache() {
        return FieldUsageCache.builder()
            .tenantId(MetaContext.getCurrentTenantId())
            .fieldId(testField.getId())
            .modelCount(5)
            .pageCount(3)
            .queryCount(2)
            .totalReferences(10)
            .isCoreField(false)
            .lastUsedAt(Instant.now())
            .usageFrequency(BigDecimal.valueOf(65.0))
            .updatedAt(Instant.now())
            .build();
    }

    /**
     * Create test cache for specific field
     */
    private FieldUsageCache createTestCacheForField(Long fieldId) {
        return FieldUsageCache.builder()
            .tenantId(MetaContext.getCurrentTenantId())
            .fieldId(fieldId)
            .modelCount(5)
            .pageCount(3)
            .queryCount(2)
            .totalReferences(10)
            .isCoreField(false)
            .lastUsedAt(Instant.now())
            .usageFrequency(BigDecimal.valueOf(65.0))
            .updatedAt(Instant.now())
            .build();
    }
}

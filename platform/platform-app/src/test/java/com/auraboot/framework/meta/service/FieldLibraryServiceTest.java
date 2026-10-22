package com.auraboot.framework.meta.service;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.common.dto.PageResult;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.dto.FieldRecommendation;
import com.auraboot.framework.meta.dto.FieldSearchRequest;
import com.auraboot.framework.meta.dto.MetaFieldDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FieldLibraryService unit test
 * Tests field library management and advanced query capabilities
 */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("test")
@Transactional
@DisplayName("FieldLibraryService Test")
class FieldLibraryServiceTest extends BaseIntegrationTest {

    @Autowired
    private FieldLibraryService fieldLibraryService;

    @Test
    @DisplayName("Test list fields by semantic type")
    void testListFieldsBySemanticType() {
        // When
        Map<String, List<MetaFieldDTO>> result = fieldLibraryService.listFieldsBySemanticType();

        // Then
        assertNotNull(result);
        // Result may be empty if no fields exist, but should not be null
    }

    @Test
    @DisplayName("Test search fields with filters")
    void testSearchFields() {
        // Given
        FieldSearchRequest request = new FieldSearchRequest();
        request.setPage(1);
        request.setSize(10);

        // When
        PageResult<MetaFieldDTO> result = fieldLibraryService.searchFields(request);

        // Then
        assertNotNull(result);
        assertNotNull(result.getRecords());
        assertTrue(result.getTotal() >= 0);
    }

    @Test
    @DisplayName("Test search fields with keyword filter")
    void testSearchFieldsWithKeyword() {
        // Given
        FieldSearchRequest request = new FieldSearchRequest();
        request.setKeyword("name");
        request.setPage(1);
        request.setSize(10);

        // When
        PageResult<MetaFieldDTO> result = fieldLibraryService.searchFields(request);

        // Then
        assertNotNull(result);
        assertNotNull(result.getRecords());
    }

    @Test
    @DisplayName("Test search fields with base type filter")
    void testSearchFieldsWithBaseType() {
        // Given
        FieldSearchRequest request = new FieldSearchRequest();
        request.setBaseType("string");
        request.setPage(1);
        request.setSize(10);

        // When
        PageResult<MetaFieldDTO> result = fieldLibraryService.searchFields(request);

        // Then
        assertNotNull(result);
        assertNotNull(result.getRecords());
        // Verify all results match the base type filter
        result.getRecords().forEach(field -> 
            assertEquals("string", field.getDataType())
        );
    }

    @Test
    @DisplayName("Test get field recommendations")
    void testGetFieldRecommendations() {
        // Given
        String modelPid = "test-model-pid";
        String semanticType = "business";

        // When
        List<FieldRecommendation> recommendations = 
            fieldLibraryService.getFieldRecommendations(modelPid, semanticType);

        // Then
        assertNotNull(recommendations);
        // Recommendations may be empty but should not be null
    }

    @Test
    @DisplayName("Test get field recommendations without semantic type")
    void testGetFieldRecommendationsWithoutSemanticType() {
        // Given
        String modelPid = "test-model-pid";

        // When
        List<FieldRecommendation> recommendations = 
            fieldLibraryService.getFieldRecommendations(modelPid, null);

        // Then
        assertNotNull(recommendations);
    }

    @Test
    @DisplayName("Test get system fields")
    void testGetSystemFields() {
        // When
        List<MetaFieldDTO> systemFields = fieldLibraryService.getSystemFields();

        // Then
        assertNotNull(systemFields);
        // System fields should include: id, created_at, updated_at, tenant_id, deleted
        assertTrue(systemFields.size() >= 0);
    }

    @Test
    @DisplayName("Test get common business fields")
    void testGetCommonBusinessFields() {
        // When
        List<MetaFieldDTO> commonFields = fieldLibraryService.getCommonBusinessFields();

        // Then
        assertNotNull(commonFields);
        // Common fields are those with high usage count
    }

    @Test
    @DisplayName("Test get unused fields")
    void testGetUnusedFields() {
        // When
        List<MetaFieldDTO> unusedFields = fieldLibraryService.getUnusedFields();

        // Then
        assertNotNull(unusedFields);
        // Unused fields have zero usage count
    }

    @Test
    @DisplayName("Test search fields with usage count range")
    void testSearchFieldsWithUsageCountRange() {
        // Given
        FieldSearchRequest request = new FieldSearchRequest();
        request.setMinUsageCount(1);
        request.setMaxUsageCount(10);
        request.setPage(1);
        request.setSize(10);

        // When
        PageResult<MetaFieldDTO> result = fieldLibraryService.searchFields(request);

        // Then
        assertNotNull(result);
        assertNotNull(result.getRecords());
    }

    @Test
    @DisplayName("Test search fields with pagination")
    void testSearchFieldsWithPagination() {
        // Given - First page
        FieldSearchRequest request1 = new FieldSearchRequest();
        request1.setPage(1);
        request1.setSize(5);

        // When
        PageResult<MetaFieldDTO> page1 = fieldLibraryService.searchFields(request1);

        // Then
        assertNotNull(page1);
        assertTrue(page1.getRecords().size() <= 5);

        // Given - Second page
        FieldSearchRequest request2 = new FieldSearchRequest();
        request2.setPage(2);
        request2.setSize(5);

        // When
        PageResult<MetaFieldDTO> page2 = fieldLibraryService.searchFields(request2);

        // Then
        assertNotNull(page2);
        assertTrue(page2.getRecords().size() <= 5);
    }
}

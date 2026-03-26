//package com.auraboot.framework.meta.component;
//
//import com.auraboot.framework.tenant.service.DictService;
//import com.auraboot.framework.tenant.service.FieldService;
//import com.auraboot.framework.tenant.dto.DictEntityResponse;
//import com.auraboot.framework.tenant.dto.DictFieldResponse;
//import org.junit.jupiter.api.BeforeEach;
//import org.junit.jupiter.api.Test;
//import org.junit.jupiter.api.extension.ExtendWith;
//import org.mockito.InjectMocks;
//import org.mockito.Mock;
//import org.mockito.junit.jupiter.MockitoExtension;
//import org.springframework.test.util.ReflectionTestUtils;
//
//import java.util.*;
//
//import static org.junit.jupiter.api.Assertions.*;
//import static org.mockito.ArgumentMatchers.*;
//import static org.mockito.Mockito.*;
//
///**
// * EntityModeResolver单元测试
// *
// * @author AuraBoot
// * @since 2024-01-01
// */
//@ExtendWith(MockitoExtension.class)
//class EntityModeResolverTest {
//
//    @Mock
//    private DictService dictEntityService;
//
//    @Mock
//    private FieldService dictFieldService;
//
//    @InjectMocks
//    private EntityModeResolver entityModeResolver;
//
//    private Long tenantId;
//    private String entityCode;
//    private DictEntityResponse mockEntity;
//    private List<DictFieldResponse> mockFields;
//
//    @BeforeEach
//    void setUp() {
//        tenantId = 1L;
//        entityCode = "user";
//
//        // 设置模拟实体
//        mockEntity = new DictEntityResponse();
//        mockEntity.setPid("entity-001");
//        mockEntity.setEntityCode(entityCode);
//        mockEntity.setEntityName("用户");
//        mockEntity.setTableName("t_user");
//        mockEntity.setStorageMode("metadata");
//
//        // 设置模拟字段
//        mockFields = Arrays.asList(
//            createMockField("field-001", "name", "姓名", "string", true),
//            createMockField("field-002", "email", "邮箱", "string", true),
//            createMockField("field-003", "age", "年龄", "integer", false)
//        );
//    }
//
//    @Test
//    void testResolveStorageMode_MetadataMode() {
//        // Given
//        when(dictEntityService.findByCode(tenantId, entityCode)).thenReturn(mockEntity);
//
//        // When
//        EntityModeResolver.StorageMode result = entityModeResolver.resolveStorageMode(tenantId, entityCode);
//
//        // Then
//        assertEquals(EntityModeResolver.StorageMode.METADATA, result);
//        verify(dictEntityService).findByCode(tenantId, entityCode);
//    }
//
//    @Test
//    void testResolveStorageMode_EntityMode() {
//        // Given
//        mockEntity.setStorageMode("entity");
//        when(dictEntityService.findByCode(tenantId, entityCode)).thenReturn(mockEntity);
//
//        // When
//        EntityModeResolver.StorageMode result = entityModeResolver.resolveStorageMode(tenantId, entityCode);
//
//        // Then
//        assertEquals(EntityModeResolver.StorageMode.ENTITY, result);
//        verify(dictEntityService).findByCode(tenantId, entityCode);
//    }
//
//    @Test
//    void testResolveStorageMode_DefaultToMetadata() {
//        // Given
//        mockEntity.setStorageMode(null);
//        when(dictEntityService.findByCode(tenantId, entityCode)).thenReturn(mockEntity);
//
//        // When
//        EntityModeResolver.StorageMode result = entityModeResolver.resolveStorageMode(tenantId, entityCode);
//
//        // Then
//        assertEquals(EntityModeResolver.StorageMode.METADATA, result);
//        verify(dictEntityService).findByCode(tenantId, entityCode);
//    }
//
//    @Test
//    void testResolveStorageMode_EntityNotFound() {
//        // Given
//        when(dictEntityService.findByCode(tenantId, entityCode)).thenReturn(null);
//
//        // When
//        EntityModeResolver.StorageMode result = entityModeResolver.resolveStorageMode(tenantId, entityCode);
//
//        // Then
//        assertEquals(EntityModeResolver.StorageMode.METADATA, result);
//        verify(dictEntityService).findByCode(tenantId, entityCode);
//    }
//
//    @Test
//    void testIsMetadataMode_True() {
//        // Given
//        when(dictEntityService.findByCode(tenantId, entityCode)).thenReturn(mockEntity);
//
//        // When
//        boolean result = entityModeResolver.isMetadataMode(tenantId, entityCode);
//
//        // Then
//        assertTrue(result);
//        verify(dictEntityService).findByCode(tenantId, entityCode);
//    }
//
//    @Test
//    void testIsMetadataMode_False() {
//        // Given
//        mockEntity.setStorageMode("entity");
//        when(dictEntityService.findByCode(tenantId, entityCode)).thenReturn(mockEntity);
//
//        // When
//        boolean result = entityModeResolver.isMetadataMode(tenantId, entityCode);
//
//        // Then
//        assertFalse(result);
//        verify(dictEntityService).findByCode(tenantId, entityCode);
//    }
//
//    @Test
//    void testGetFieldMapping_Success() {
//        // Given
//        when(dictEntityService.findByCode(tenantId, entityCode)).thenReturn(mockEntity);
//        when(dictFieldService.findByEntityPid(mockEntity.getPid())).thenReturn(mockFields);
//
//        // When
//        Map<String, Object> result = entityModeResolver.getFieldMapping(tenantId, entityCode);
//
//        // Then
//        assertNotNull(result);
//        assertEquals(entityCode, result.get("entityCode"));
//        assertEquals("用户", result.get("entityName"));
//        assertEquals("t_user", result.get("tableName"));
//        assertEquals("metadata", result.get("storageMode"));
//
//        @SuppressWarnings("unchecked")
//        List<Map<String, Object>> fields = (List<Map<String, Object>>) result.get("fields");
//        assertEquals(3, fields.size());
//
//        Map<String, Object> firstField = fields.get(0);
//        assertEquals("name", firstField.get("fieldCode"));
//        assertEquals("姓名", firstField.get("fieldName"));
//        assertEquals("string", firstField.get("fieldType"));
//        assertEquals(true, firstField.get("required"));
//
//        verify(dictEntityService).findByCode(tenantId, entityCode);
//        verify(dictFieldService).findByEntityPid(mockEntity.getPid());
//    }
//
//    @Test
//    void testGetFieldMapping_EntityNotFound() {
//        // Given
//        when(dictEntityService.findByCode(tenantId, entityCode)).thenReturn(null);
//
//        // When
//        Map<String, Object> result = entityModeResolver.getFieldMapping(tenantId, entityCode);
//
//        // Then
//        assertNotNull(result);
//        assertEquals(entityCode, result.get("entityCode"));
//        assertNull(result.get("entityName"));
//        assertNull(result.get("tableName"));
//        assertEquals("metadata", result.get("storageMode"));
//
//        @SuppressWarnings("unchecked")
//        List<Map<String, Object>> fields = (List<Map<String, Object>>) result.get("fields");
//        assertTrue(fields.isEmpty());
//
//        verify(dictEntityService).findByCode(tenantId, entityCode);
//        verify(dictFieldService, never()).findByEntityPid(anyString());
//    }
//
//    @Test
//    void testGetFieldMapping_NoFields() {
//        // Given
//        when(dictEntityService.findByCode(tenantId, entityCode)).thenReturn(mockEntity);
//        when(dictFieldService.findByEntityPid(mockEntity.getPid())).thenReturn(Collections.emptyList());
//
//        // When
//        Map<String, Object> result = entityModeResolver.getFieldMapping(tenantId, entityCode);
//
//        // Then
//        assertNotNull(result);
//        assertEquals(entityCode, result.get("entityCode"));
//
//        @SuppressWarnings("unchecked")
//        List<Map<String, Object>> fields = (List<Map<String, Object>>) result.get("fields");
//        assertTrue(fields.isEmpty());
//
//        verify(dictEntityService).findByCode(tenantId, entityCode);
//        verify(dictFieldService).findByEntityPid(mockEntity.getPid());
//    }
//
//    @Test
//    void testGetTableName_Success() {
//        // Given
//        when(dictEntityService.findByCode(tenantId, entityCode)).thenReturn(mockEntity);
//
//        // When
//        String result = entityModeResolver.getTableName(tenantId, entityCode);
//
//        // Then
//        assertEquals("t_user", result);
//        verify(dictEntityService).findByCode(tenantId, entityCode);
//    }
//
//    @Test
//    void testGetTableName_EntityNotFound() {
//        // Given
//        when(dictEntityService.findByCode(tenantId, entityCode)).thenReturn(null);
//
//        // When
//        String result = entityModeResolver.getTableName(tenantId, entityCode);
//
//        // Then
//        assertNull(result);
//        verify(dictEntityService).findByCode(tenantId, entityCode);
//    }
//
//    @Test
//    void testGetTableName_NoTableName() {
//        // Given
//        mockEntity.setTableName(null);
//        when(dictEntityService.findByCode(tenantId, entityCode)).thenReturn(mockEntity);
//
//        // When
//        String result = entityModeResolver.getTableName(tenantId, entityCode);
//
//        // Then
//        assertNull(result);
//        verify(dictEntityService).findByCode(tenantId, entityCode);
//    }
//
//    @Test
//    void testStorageModeEnum() {
//        // Test enum values
//        assertEquals("metadata", EntityModeResolver.StorageMode.METADATA.name());
//        assertEquals("entity", EntityModeResolver.StorageMode.ENTITY.name());
//
//        // Test enum valueOf
//        assertEquals(EntityModeResolver.StorageMode.METADATA,
//            EntityModeResolver.StorageMode.valueOf("metadata"));
//        assertEquals(EntityModeResolver.StorageMode.ENTITY,
//            EntityModeResolver.StorageMode.valueOf("entity"));
//
//        // Test enum values array
//        EntityModeResolver.StorageMode[] values = EntityModeResolver.StorageMode.values();
//        assertEquals(2, values.length);
//        assertTrue(Arrays.asList(values).contains(EntityModeResolver.StorageMode.METADATA));
//        assertTrue(Arrays.asList(values).contains(EntityModeResolver.StorageMode.ENTITY));
//    }
//
//    @Test
//    void testConcurrentAccess() throws InterruptedException {
//        // Given
//        when(dictEntityService.findByCode(tenantId, entityCode)).thenReturn(mockEntity);
//        when(dictFieldService.findByEntityPid(mockEntity.getPid())).thenReturn(mockFields);
//
//        // When - 并发访问测试
//        List<Thread> threads = new ArrayList<>();
//        List<EntityModeResolver.StorageMode> results = Collections.synchronizedList(new ArrayList<>());
//
//        for (int i = 0; i < 10; i++) {
//            Thread thread = new Thread(() -> {
//                EntityModeResolver.StorageMode mode = entityModeResolver.resolveStorageMode(tenantId, entityCode);
//                results.add(mode);
//            });
//            threads.add(thread);
//            thread.start();
//        }
//
//        // 等待所有线程完成
//        for (Thread thread : threads) {
//            thread.join();
//        }
//
//        // Then
//        assertEquals(10, results.size());
//        results.forEach(mode -> assertEquals(EntityModeResolver.StorageMode.METADATA, mode));
//    }
//
//    @Test
//    void testFieldMappingWithNullValues() {
//        // Given
//        DictFieldResponse fieldWithNulls = new DictFieldResponse();
//        fieldWithNulls.setPid("field-004");
//        fieldWithNulls.setFieldCode("description");
//        fieldWithNulls.setFieldName(null); // null name
//        fieldWithNulls.setFieldType(null); // null type
//        fieldWithNulls.setRequired(null); // null required
//
//        List<DictFieldResponse> fieldsWithNulls = Arrays.asList(
//            mockFields.get(0),
//            fieldWithNulls
//        );
//
//        when(dictEntityService.findByCode(tenantId, entityCode)).thenReturn(mockEntity);
//        when(dictFieldService.findByEntityPid(mockEntity.getPid())).thenReturn(fieldsWithNulls);
//
//        // When
//        Map<String, Object> result = entityModeResolver.getFieldMapping(tenantId, entityCode);
//
//        // Then
//        assertNotNull(result);
//
//        @SuppressWarnings("unchecked")
//        List<Map<String, Object>> fields = (List<Map<String, Object>>) result.get("fields");
//        assertEquals(2, fields.size());
//
//        Map<String, Object> fieldWithNullsMap = fields.get(1);
//        assertEquals("description", fieldWithNullsMap.get("fieldCode"));
//        assertNull(fieldWithNullsMap.get("fieldName"));
//        assertNull(fieldWithNullsMap.get("fieldType"));
//        assertEquals(false, fieldWithNullsMap.get("required")); // 默认为false
//    }
//
//    /**
//     * 创建模拟字段
//     */
//    private DictFieldResponse createMockField(String pid, String code, String name, String type, Boolean required) {
//        DictFieldResponse field = new DictFieldResponse();
//        field.setPid(pid);
//        field.setFieldCode(code);
//        field.setFieldName(name);
//        field.setFieldType(type);
//        field.setRequired(required);
//        return field;
//    }
//}
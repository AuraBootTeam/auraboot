package com.auraboot.framework.meta.entity;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PageSchemaHistory实体类单元测试
 */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("test")
@Transactional
@DisplayName("PageSchemaHistory实体测试")
class PageSchemaHistoryTest extends BaseIntegrationTest {

    private PageSchemaHistory testPageSchemaHistory;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        // 设置测试租户上下文，使用-1L匹配测试环境的租户ID
          super.setupTenantContext();
        
        // 初始化ObjectMapper
        objectMapper = new ObjectMapper();
        
        // 创建测试数据
        testPageSchemaHistory = createTestPageSchemaHistory();
    }

    @Nested
    @DisplayName("实体类基本功能测试")
    class BasicFunctionalityTests {

        @Test
        @DisplayName("测试实体类基本字段的getter/setter")
        void testBasicGettersAndSetters() {
            // Given
            String pagePid = "test-page-pid-123";
            String op = "create";
            String opBy = "test-user-pid";
            Instant opAt = Instant.now();
            
            // When
            testPageSchemaHistory.setPid(pagePid);
            testPageSchemaHistory.setOp(op);
            testPageSchemaHistory.setOpBy(opBy);
            testPageSchemaHistory.setOpAt(opAt);
            
            // Then
            assertEquals(pagePid, testPageSchemaHistory.getPid());
            assertEquals(op, testPageSchemaHistory.getOp());
            assertEquals(opBy, testPageSchemaHistory.getOpBy());
            assertEquals(opAt, testPageSchemaHistory.getOpAt());
        }

        @Test
        @DisplayName("测试继承自AbstractEntity的字段")
        void testInheritedFields() {
            // Given
            String pid = UniqueIdGenerator.generate();
            Long tenantId = -1L;
            Instant createdAt = Instant.now();
            
            // When
            // testPageSchemaHistory.setPid(pid); // PageSchemaHistory 不再有 pid 字段
            testPageSchemaHistory.setTenantId(tenantId);
            testPageSchemaHistory.setCreatedAt(createdAt);
            
            // Then
            // assertEquals(pid, testPageSchemaHistory.getPid()); // PageSchemaHistory 不再有 pid 字段
            assertEquals(tenantId, testPageSchemaHistory.getTenantId());
            assertEquals(createdAt, testPageSchemaHistory.getCreatedAt());
        }

        @Test
        @DisplayName("测试实体类默认值")
        void testDefaultValues() {
            // Given
            PageSchemaHistory newHistory = new PageSchemaHistory();
            
            // Then
            assertNull(newHistory.getPid());
            assertNull(newHistory.getSnapshot());
            assertNull(newHistory.getOp());
            assertNull(newHistory.getOpBy());
            assertNull(newHistory.getOpAt());
            
            // 继承字段的默认值
            // assertNull(newHistory.getPid()); // PageSchemaHistory 不再有 pid 字段
            assertNull(newHistory.getTenantId());
            assertNull(newHistory.getCreatedAt());
        }
    }

    @Nested
    @DisplayName("历史记录字段测试")
    class HistoryFieldsTests {

        @Test
        @DisplayName("测试pagePid字段关联")
        void testPagePidAssociation() {
            // Given
            String originalPagePid = "original-page-123";
            
            // When
            testPageSchemaHistory.setPid(originalPagePid);
            
            // Then
            assertEquals(originalPagePid, testPageSchemaHistory.getPid());
            assertNotNull(testPageSchemaHistory.getPid());
        }

        @Test
        @DisplayName("测试操作类型字段")
        void testOperationTypeField() {
            // Given - 测试所有标准操作类型
            String[] validOps = {"create", "update", "publish", "archive", "delete", "restore"};
            
            for (String op : validOps) {
                // When
                testPageSchemaHistory.setOp(op);
                
                // Then
                assertEquals(op, testPageSchemaHistory.getOp());
            }
        }

        @Test
        @DisplayName("测试操作人和操作时间字段")
        void testOperatorAndTimeFields() {
            // Given
            String opBy = "operator-user-pid";
            Instant opAt = Instant.now();
            
            // When
            testPageSchemaHistory.setOpBy(opBy);
            testPageSchemaHistory.setOpAt(opAt);
            
            // Then
            assertEquals(opBy, testPageSchemaHistory.getOpBy());
            assertEquals(opAt, testPageSchemaHistory.getOpAt());
        }

        @Test
        @DisplayName("测试历史记录完整性")
        void testHistoryRecordCompleteness() {
            // Given
            String pagePid = "test-page-pid";
            String op = "update";
            String opBy = "test-user";
            Instant opAt = Instant.now();
            Map<String, Object> snapshot = createTestSnapshot();
            
            // When
            testPageSchemaHistory.setPid(pagePid);
            testPageSchemaHistory.setOp(op);
            testPageSchemaHistory.setOpBy(opBy);
            testPageSchemaHistory.setOpAt(opAt);
            testPageSchemaHistory.setSnapshot(snapshot);
            
            // Then - 验证历史记录的完整性
            assertNotNull(testPageSchemaHistory.getPid());
            assertNotNull(testPageSchemaHistory.getOp());
            assertNotNull(testPageSchemaHistory.getOpBy());
            assertNotNull(testPageSchemaHistory.getOpAt());
            assertNotNull(testPageSchemaHistory.getSnapshot());
            
            // 验证快照内容
            assertEquals("test-page", testPageSchemaHistory.getSnapshot().get("name"));
            assertEquals("测试页面", testPageSchemaHistory.getSnapshot().get("title"));
        }
    }

    @Nested
    @DisplayName("JSONB字段处理测试")
    class JsonbFieldTests {

        @Test
        @DisplayName("测试snapshot字段JSONB序列化")
        void testSnapshotJsonbSerialization() throws Exception {
            // Given
            Map<String, Object> complexSnapshot = createComplexSnapshot();
            
            // When
            testPageSchemaHistory.setSnapshot(complexSnapshot);
            
            // Then
            assertNotNull(testPageSchemaHistory.getSnapshot());
            assertEquals(complexSnapshot, testPageSchemaHistory.getSnapshot());
            
            // 验证嵌套结构
            @SuppressWarnings("unchecked")
            Map<String, Object> dslSchema = (Map<String, Object>) complexSnapshot.get("dsl_schema");
            assertNotNull(dslSchema);
            assertEquals("form", dslSchema.get("type"));
            assertEquals("测试表单", dslSchema.get("title"));
        }

        @Test
        @DisplayName("测试snapshot字段JSON结构验证")
        void testSnapshotJsonStructureValidation() throws Exception {
            // Given
            Map<String, Object> snapshot = createTestSnapshot();
            testPageSchemaHistory.setSnapshot(snapshot);
            
            // When - 将Map转换为JSON字符串再解析，模拟JSONB处理过程
            String jsonString = objectMapper.writeValueAsString(snapshot);
            JsonNode jsonNode = objectMapper.readTree(jsonString);
            
            // Then - 验证JSON结构
            assertTrue(jsonNode.has("name"));
            assertTrue(jsonNode.has("title"));
            assertTrue(jsonNode.has("page_type"));
            assertTrue(jsonNode.has("version"));
            
            assertEquals("test-page", jsonNode.get("name").asText());
            assertEquals("测试页面", jsonNode.get("title").asText());
            assertEquals("form", jsonNode.get("page_type").asText());
            assertEquals(1, jsonNode.get("version").asInt());
        }

        @Test
        @DisplayName("测试snapshot字段空值处理")
        void testSnapshotNullHandling() {
            // Given & When
            testPageSchemaHistory.setSnapshot(null);
            
            // Then
            assertNull(testPageSchemaHistory.getSnapshot());
        }

        @Test
        @DisplayName("测试snapshot字段复杂数据结构")
        void testSnapshotComplexDataStructure() {
            // Given
            Map<String, Object> complexSnapshot = new HashMap<>();
            complexSnapshot.put("name", "complex-page");
            complexSnapshot.put("title", "复杂页面");
            
            // 嵌套对象
            Map<String, Object> metaInfo = new HashMap<>();
            metaInfo.put("author", "test-author");
            metaInfo.put("version", "2.0.0");
            metaInfo.put("tags", new String[]{"form", "complex", "test"});
            complexSnapshot.put("meta_info", metaInfo);
            
            // 嵌套数组
            Map<String, Object> field1 = new HashMap<>();
            field1.put("name", "field1");
            field1.put("type", "input");
            field1.put("required", true);
            
            Map<String, Object> field2 = new HashMap<>();
            field2.put("name", "field2");
            field2.put("type", "select");
            field2.put("required", false);
            
            complexSnapshot.put("fields", new Object[]{field1, field2});
            
            // When
            testPageSchemaHistory.setSnapshot(complexSnapshot);
            
            // Then
            assertNotNull(testPageSchemaHistory.getSnapshot());
            assertEquals("complex-page", testPageSchemaHistory.getSnapshot().get("name"));
            assertEquals("复杂页面", testPageSchemaHistory.getSnapshot().get("title"));
            
            @SuppressWarnings("unchecked")
            Map<String, Object> retrievedMetaInfo = (Map<String, Object>) testPageSchemaHistory.getSnapshot().get("meta_info");
            assertNotNull(retrievedMetaInfo);
            assertEquals("test-author", retrievedMetaInfo.get("author"));
            assertEquals("2.0.0", retrievedMetaInfo.get("version"));
        }
    }

    @Nested
    @DisplayName("与主表逻辑关联测试")
    class MainTableAssociationTests {

        @Test
        @DisplayName("测试与PageSchema的逻辑关联")
        void testPageSchemaAssociation() {
            // Given - 模拟主表PageSchema的PID
            String mainPagePid = "main-page-schema-pid";
            
            // When - 创建历史记录关联到主表
            testPageSchemaHistory.setPid(mainPagePid);
            
            // Then - 验证关联关系
            assertEquals(mainPagePid, testPageSchemaHistory.getPid());
            assertNotNull(testPageSchemaHistory.getPid());
        }

        @Test
        @DisplayName("测试历史记录版本追踪")
        void testVersionTracking() {
            // Given
            Map<String, Object> snapshot = createTestSnapshot();
            snapshot.put("version", 1);
            snapshot.put("semver", "1.0.0");
            snapshot.put("row_version", 1);
            
            // When
            testPageSchemaHistory.setSnapshot(snapshot);
            testPageSchemaHistory.setOp("create");
            
            // Then
            assertEquals(1, testPageSchemaHistory.getSnapshot().get("version"));
            assertEquals("1.0.0", testPageSchemaHistory.getSnapshot().get("semver"));
            assertEquals(1, testPageSchemaHistory.getSnapshot().get("row_version"));
            assertEquals("create", testPageSchemaHistory.getOp());
        }

        @Test
        @DisplayName("测试多租户隔离")
        void testMultiTenantIsolation() {
            // Given
            Long tenantId1 = -1L;
            Long tenantId2 = -2L;
            
            PageSchemaHistory history1 = createTestPageSchemaHistory();
            PageSchemaHistory history2 = createTestPageSchemaHistory();
            
            // When
            history1.setTenantId(tenantId1);
            history2.setTenantId(tenantId2);
            
            // Then
            assertEquals(tenantId1, history1.getTenantId());
            assertEquals(tenantId2, history2.getTenantId());
            assertNotEquals(history1.getTenantId(), history2.getTenantId());
        }
    }

    /**
     * 创建测试PageSchemaHistory对象
     */
    private PageSchemaHistory createTestPageSchemaHistory() {
        PageSchemaHistory history = new PageSchemaHistory();
        // history.setPid(UniqueIdGenerator.generate()); // PageSchemaHistory 不再有 pid 字段
        history.setTenantId(-1L); // 设置租户ID
        history.setPid("test-page-pid");
        history.setSnapshot(createTestSnapshot());
        history.setOp("create");
        history.setOpBy("test-user-pid");
        history.setOpAt(Instant.now());
        history.setCreatedAt(Instant.now());
        return history;
    }

    /**
     * 创建测试快照数据
     */
    private Map<String, Object> createTestSnapshot() {
        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("name", "test-page");
        snapshot.put("title", "测试页面");
        snapshot.put("description", "这是一个测试页面");
        snapshot.put("page_type", "form");
        snapshot.put("version", 1);
        snapshot.put("semver", "1.0.0");
        snapshot.put("row_version", 1);
        snapshot.put("is_template", false);
        return snapshot;
    }

    /**
     * 创建复杂快照数据
     */
    private Map<String, Object> createComplexSnapshot() {
        Map<String, Object> snapshot = createTestSnapshot();
        
        // 添加DSL Schema
        Map<String, Object> dslSchema = new HashMap<>();
        dslSchema.put("type", "form");
        dslSchema.put("title", "测试表单");
        
        Map<String, Object> fields = new HashMap<>();
        Map<String, Object> nameField = new HashMap<>();
        nameField.put("type", "input");
        nameField.put("label", "姓名");
        nameField.put("required", true);
        fields.put("name", nameField);
        dslSchema.put("fields", fields);
        
        snapshot.put("dsl_schema", dslSchema);
        
        // 添加元信息
        Map<String, Object> metaInfo = new HashMap<>();
        metaInfo.put("author", "test-user");
        metaInfo.put("version", "1.0.0");
        metaInfo.put("lastModified", "2024-01-01T00:00:00Z");
        snapshot.put("meta_info", metaInfo);
        
        // 添加标签
        snapshot.put("tags", new String[]{"form", "user", "test"});
        
        return snapshot;
    }
}
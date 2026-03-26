//package com.auraboot.framework.meta.mapper;
//
//import com.auraboot.framework.application.TestApplication;
//import com.auraboot.framework.application.tenant.MetaContext;
//import com.auraboot.framework.meta.entity.PageSchemaHistory;
//import com.auraboot.framework.common.util.UniqueIdGenerator;
//import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
//import org.junit.jupiter.api.BeforeEach;
//import org.junit.jupiter.api.Test;
//import org.junit.jupiter.api.DisplayName;
//import org.junit.jupiter.api.Nested;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.boot.test.context.SpringBootTest;
//import org.springframework.test.context.ActiveProfiles;
//import org.springframework.transaction.annotation.Transactional;
//
//import com.fasterxml.jackson.databind.JsonNode;
//import com.fasterxml.jackson.databind.ObjectMapper;
//
//import java.time.Instant;
//import java.util.HashMap;
//import java.util.Map;
//import java.util.List;
//
//import static org.junit.jupiter.api.Assertions.*;
//
///**
// * PageSchemaHistoryMapper单元测试
// */
//@SpringBootTest(classes = TestApplication.class)
//@ActiveProfiles("test")
//@Transactional
//@DisplayName("PageSchemaHistoryMapper测试")
//class PageSchemaHistoryMapperTest {
//
//    @Autowired
//    private PageSchemaHistoryMapper pageSchemaHistoryMapper;
//
//    private ObjectMapper objectMapper;
//    private PageSchemaHistory testHistory;
//
//    @BeforeEach
//    void setUp() {
//        // 设置测试租户上下文，使用-1L匹配测试环境的租户ID
//        MetaContext.setContext(-1L, -1L, "test-user-pid", "testuser");
//
//        // 初始化ObjectMapper
//        objectMapper = new ObjectMapper();
//
//        // 创建测试数据
//        testHistory = createTestPageSchemaHistory();
//    }
//
//    @Nested
//    @DisplayName("基础CRUD操作测试")
//    class BasicCrudTests {
//
//        @Test
//        @DisplayName("测试插入历史记录")
//        void testInsert() {
//            // When
//            int result = pageSchemaHistoryMapper.insert(testHistory);
//
//            // Then
//            assertEquals(1, result);
//        }
//
//        @Test
//        @DisplayName("测试根据ID查询历史记录")
//        void testSelectById() {
//            // Given
//            pageSchemaHistoryMapper.insert(testHistory);
//
//            // When
//            PageSchemaHistory found = pageSchemaHistoryMapper.selectById(testHistory.getId());
//
//            // Then
//            assertNotNull(found);
//            assertEquals(testHistory.getPagePid(), found.getPagePid());
//            assertEquals(testHistory.getOp(), found.getOp());
//            assertEquals(testHistory.getOpBy(), found.getOpBy());
//        }
//
//        @Test
//        @DisplayName("测试更新历史记录")
//        void testUpdate() {
//            // Given
//            pageSchemaHistoryMapper.insert(testHistory);
//            String newOp = "update";
//
//            // When
//            testHistory.setOp(newOp);
//            int result = pageSchemaHistoryMapper.updateById(testHistory);
//
//            // Then
//            assertEquals(1, result);
//            PageSchemaHistory updated = pageSchemaHistoryMapper.selectById(testHistory.getId());
//            assertEquals(newOp, updated.getOp());
//        }
//
//        @Test
//        @DisplayName("测试删除历史记录")
//        void testDelete() {
//            // Given
//            pageSchemaHistoryMapper.insert(testHistory);
//
//            // When
//            int result = pageSchemaHistoryMapper.deleteById(testHistory.getId());
//
//            // Then
//            assertEquals(1, result);
//            PageSchemaHistory deleted = pageSchemaHistoryMapper.selectById(testHistory.getId());
//            assertNull(deleted);
//        }
//    }
//
//    @Nested
//    @DisplayName("历史记录查询测试")
//    class HistoryQueryTests {
//
//        @Test
//        @DisplayName("测试按页面PID查询历史记录")
//        void testFindByPagePid() {
//            // Given
//            String pagePid = "test-page-pid";
//            PageSchemaHistory history1 = createTestPageSchemaHistory();
//            history1.setPagePid(pagePid);
//            history1.setOp("create");
//
//            PageSchemaHistory history2 = createTestPageSchemaHistory();
//            history2.setPagePid(pagePid);
//            history2.setOp("update");
//
//            pageSchemaHistoryMapper.insert(history1);
//            pageSchemaHistoryMapper.insert(history2);
//
//            // When
//            List<PageSchemaHistory> histories = pageSchemaHistoryMapper.findByPagePid(pagePid);
//
//            // Then
//            assertNotNull(histories);
//            assertEquals(2, histories.size());
//            // 验证按时间倒序排列
//            assertTrue(histories.get(0).getOpAt().isAfter(histories.get(1).getOpAt()) ||
//                      histories.get(0).getOpAt().equals(histories.get(1).getOpAt()));
//        }
//
//        @Test
//        @DisplayName("测试分页查询页面历史记录")
//        void testFindByPagePidWithPagination() {
//            // Given
//            String pagePid = "test-page-pid";
//            for (int i = 0; i < 5; i++) {
//                PageSchemaHistory history = createTestPageSchemaHistory();
//                history.setPagePid(pagePid);
//                history.setOp("create_" + i);
//                pageSchemaHistoryMapper.insert(history);
//            }
//
//            // When
//            Page<PageSchemaHistory> page = pageSchemaHistoryMapper.findByPagePidWithPagination(pagePid, 1, 3);
//
//            // Then
//            assertNotNull(page);
//            assertEquals(3, page.getSize());
//            assertEquals(5, page.getTotal());
//            assertEquals(2, page.getPages());
//        }
//
//        @Test
//        @DisplayName("测试按页面PID和操作类型查询")
//        void testFindByPagePidAndOp() {
//            // Given
//            String pagePid = "test-page-pid";
//            String op = "create";
//
//            PageSchemaHistory history1 = createTestPageSchemaHistory();
//            history1.setPagePid(pagePid);
//            history1.setOp(op);
//
//            PageSchemaHistory history2 = createTestPageSchemaHistory();
//            history2.setPagePid(pagePid);
//            history2.setOp("update");
//
//            pageSchemaHistoryMapper.insert(history1);
//            pageSchemaHistoryMapper.insert(history2);
//
//            // When
//            List<PageSchemaHistory> histories = pageSchemaHistoryMapper.findByPagePidAndOp(pagePid, op);
//
//            // Then
//            assertNotNull(histories);
//            assertEquals(1, histories.size());
//            assertEquals(op, histories.get(0).getOp());
//        }
//
//        @Test
//        @DisplayName("测试按操作者查询")
//        void testFindByOperator() {
//            // Given
//            String opBy = "test-operator";
//
//            PageSchemaHistory history1 = createTestPageSchemaHistory();
//            history1.setOpBy(opBy);
//
//            PageSchemaHistory history2 = createTestPageSchemaHistory();
//            history2.setOpBy("other-operator");
//
//            pageSchemaHistoryMapper.insert(history1);
//            pageSchemaHistoryMapper.insert(history2);
//
//            // When
//            List<PageSchemaHistory> histories = pageSchemaHistoryMapper.findByOperator(opBy);
//
//            // Then
//            assertNotNull(histories);
//            assertEquals(1, histories.size());
//            assertEquals(opBy, histories.get(0).getOpBy());
//        }
//
//        @Test
//        @DisplayName("测试按时间范围查询")
//        void testFindByPagePidAndTimeRange() {
//            // Given
//            String pagePid = "test-page-pid";
//            Instant startTime = Instant.now().minusSeconds(3600); // 1小时前
//            Instant endTime = Instant.now().plusSeconds(3600);   // 1小时后
//
//            PageSchemaHistory history = createTestPageSchemaHistory();
//            history.setPagePid(pagePid);
//            history.setOpAt(Instant.now());
//
//            pageSchemaHistoryMapper.insert(history);
//
//            // When
//            List<PageSchemaHistory> histories = pageSchemaHistoryMapper.findByPagePidAndTimeRange(pagePid, startTime, endTime);
//
//            // Then
//            assertNotNull(histories);
//            assertEquals(1, histories.size());
//        }
//
//        @Test
//        @DisplayName("测试查询最新历史记录")
//        void testFindLatestByPagePid() {
//            // Given
//            String pagePid = "test-page-pid";
//
//            PageSchemaHistory history1 = createTestPageSchemaHistory();
//            history1.setPagePid(pagePid);
//            history1.setOpAt(Instant.now().minusSeconds(60));
//
//            PageSchemaHistory history2 = createTestPageSchemaHistory();
//            history2.setPagePid(pagePid);
//            history2.setOpAt(Instant.now());
//
//            pageSchemaHistoryMapper.insert(history1);
//            pageSchemaHistoryMapper.insert(history2);
//
//            // When
//            PageSchemaHistory latest = pageSchemaHistoryMapper.findLatestByPagePid(pagePid);
//
//            // Then
//            assertNotNull(latest);
//            assertEquals(history2.getOpAt(), latest.getOpAt());
//        }
//    }
//
//    @Nested
//    @DisplayName("统计查询测试")
//    class CountQueryTests {
//
//        @Test
//        @DisplayName("测试按页面PID统计数量")
//        void testCountByPagePid() {
//            // Given
//            String pagePid = "test-page-pid";
//
//            for (int i = 0; i < 3; i++) {
//                PageSchemaHistory history = createTestPageSchemaHistory();
//                history.setPagePid(pagePid);
//                pageSchemaHistoryMapper.insert(history);
//            }
//
//            // When
//            Long count = pageSchemaHistoryMapper.countByPagePid(pagePid);
//
//            // Then
//            assertEquals(3L, count);
//        }
//
//        @Test
//        @DisplayName("测试按页面PID和操作类型统计数量")
//        void testCountByPagePidAndOp() {
//            // Given
//            String pagePid = "test-page-pid";
//            String op = "create";
//
//            PageSchemaHistory history1 = createTestPageSchemaHistory();
//            history1.setPagePid(pagePid);
//            history1.setOp(op);
//
//            PageSchemaHistory history2 = createTestPageSchemaHistory();
//            history2.setPagePid(pagePid);
//            history2.setOp("update");
//
//            pageSchemaHistoryMapper.insert(history1);
//            pageSchemaHistoryMapper.insert(history2);
//
//            // When
//            Long count = pageSchemaHistoryMapper.countByPagePidAndOp(pagePid, op);
//
//            // Then
//            assertEquals(1L, count);
//        }
//    }
//
//    @Nested
//    @DisplayName("JSONB字段处理测试")
//    class JsonbFieldTests {
//
//        @Test
//        @DisplayName("测试JSONB快照字段存储和查询")
//        void testSnapshotJsonbField() throws Exception {
//            // Given
//            Map<String, Object> snapshotData = new HashMap<>();
//            snapshotData.put("title", "测试页面");
//            snapshotData.put("version", "1.0");
//            snapshotData.put("components", List.of("header", "content", "footer"));
//
//            testHistory.setSnapshot(snapshotData);
//            pageSchemaHistoryMapper.insert(testHistory);
//
//            // When
//            PageSchemaHistory found = pageSchemaHistoryMapper.selectById(testHistory.getId());
//
//            // Then
//            assertNotNull(found.getSnapshot());
//            Map<String, Object> snapshot = found.getSnapshot();
//            assertEquals("测试页面", snapshot.get("title"));
//            assertEquals("1.0", snapshot.get("version"));
//            assertNotNull(snapshot.get("components"));
//        }
//
//        @Test
//        @DisplayName("测试按JSON路径查询")
//        void testFindBySnapshotJsonPath() {
//            // Given
//            Long tenantId = -1L;
//            String pagePid = "test-page-pid";
//            String jsonPath = "title";
//            String value = "测试页面";
//
//            Map<String, Object> snapshotData = new HashMap<>();
//            snapshotData.put("title", value);
//
//            testHistory.setPagePid(pagePid);
//            testHistory.setSnapshot(snapshotData);
//            pageSchemaHistoryMapper.insert(testHistory);
//
//            // When
//            List<PageSchemaHistory> histories = pageSchemaHistoryMapper.findBySnapshotJsonPath(tenantId, pagePid, jsonPath, value);
//
//            // Then
//            assertNotNull(histories);
//            assertFalse(histories.isEmpty());
//        }
//
//        @Test
//        @DisplayName("测试按JSON包含查询")
//        void testFindBySnapshotContains() {
//            // Given
//            Long tenantId = -1L;
//            String pagePid = "test-page-pid";
//            String jsonQuery = "{\"version\": \"1.0\"}";
//
//            Map<String, Object> snapshotData = new HashMap<>();
//            snapshotData.put("title", "测试页面");
//            snapshotData.put("version", "1.0");
//
//            testHistory.setPagePid(pagePid);
//            testHistory.setSnapshot(snapshotData);
//            pageSchemaHistoryMapper.insert(testHistory);
//
//            // When
//            List<PageSchemaHistory> histories = pageSchemaHistoryMapper.findBySnapshotContains(tenantId, pagePid, jsonQuery);
//
//            // Then
//            assertNotNull(histories);
//            assertFalse(histories.isEmpty());
//        }
//
//        @Test
//        @DisplayName("测试空JSON快照处理")
//        void testNullSnapshotHandling() {
//            // Given - 由于数据库约束 snapshot 不能为 null，使用空的 Map
//            Map<String, Object> emptySnapshot = new HashMap<>();
//            testHistory.setSnapshot(emptySnapshot);
//
//            // When
//            int result = pageSchemaHistoryMapper.insert(testHistory);
//
//            // Then
//            assertEquals(1, result);
//            PageSchemaHistory found = pageSchemaHistoryMapper.selectById(testHistory.getId());
//            assertNotNull(found.getSnapshot());
//            assertTrue(found.getSnapshot().isEmpty());
//        }
//    }
//
//    @Nested
//    @DisplayName("多租户隔离测试")
//    class MultiTenantTests {
//
//        @Test
//        @DisplayName("测试多租户数据隔离")
//        void testTenantIsolation() {
//            // Given - 当前租户数据
//            PageSchemaHistory currentTenantHistory = createTestPageSchemaHistory();
//            currentTenantHistory.setPagePid("current-tenant-page");
//            pageSchemaHistoryMapper.insert(currentTenantHistory);
//
//            // 模拟其他租户数据（通过直接设置不同的租户ID）
//            MetaContext.setContext(-2L, -2L, "other-user-pid", "otheruser");
//            PageSchemaHistory otherTenantHistory = createTestPageSchemaHistory();
//            otherTenantHistory.setPagePid("other-tenant-page");
//            pageSchemaHistoryMapper.insert(otherTenantHistory);
//
//            // 切换回原租户
//            MetaContext.setContext(-1L, -1L, "test-user-pid", "testuser");
//
//            // When - 查询当前租户的数据
//            List<PageSchemaHistory> currentTenantHistories = pageSchemaHistoryMapper.findByPagePid("current-tenant-page");
//            List<PageSchemaHistory> otherTenantHistories = pageSchemaHistoryMapper.findByPagePid("other-tenant-page");
//
//            // Then - 只能查询到当前租户的数据
//            assertNotNull(currentTenantHistories);
//            assertEquals(1, currentTenantHistories.size());
//
//            assertNotNull(otherTenantHistories);
//            assertEquals(0, otherTenantHistories.size()); // 不能查询到其他租户的数据
//        }
//    }
//
//    /**
//     * 创建测试用的PageSchemaHistory对象
//     */
//    private PageSchemaHistory createTestPageSchemaHistory() {
//        PageSchemaHistory history = new PageSchemaHistory();
//        // history.setPid(UniqueIdGenerator.generate()); // PageSchemaHistory 不再有 pid 字段
//        history.setPagePid("test-page-001"); // 使用固定长度的 PID
//        history.setOp("create");
//        history.setOpBy("test-user");
//        history.setOpAt(Instant.now());
//
//        // 创建测试快照数据
//        Map<String, Object> snapshotData = new HashMap<>();
//        snapshotData.put("name", "test-page");
//        snapshotData.put("title", "测试页面");
//        snapshotData.put("version", 1);
//        history.setSnapshot(snapshotData);
//
//        return history;
//    }
//
//    private PageSchemaHistory createTestPageSchemaHistory(String pagePid, String op) {
//        PageSchemaHistory history = new PageSchemaHistory();
//        history.setPagePid(pagePid);
//        history.setOp(op);
//        history.setOpBy("test-user");
//        history.setOpAt(Instant.now());
//
//        // 创建测试快照数据
//        Map<String, Object> snapshotData = new HashMap<>();
//        snapshotData.put("name", "test-page");
//        snapshotData.put("title", "测试页面");
//        snapshotData.put("version", 1);
//        history.setSnapshot(snapshotData);
//
//        return history;
//    }
//}
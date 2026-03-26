//package com.auraboot.framework.meta.aspect;
//
//import com.auraboot.framework.application.TestApplication;
//import com.auraboot.framework.git.router.LayeredGitFirstRouter;
//import com.auraboot.framework.meta.annotation.GitFirstCheck;
//import com.auraboot.framework.meta.annotation.GitFirstOperation;
//import com.auraboot.framework.meta.exception.MetaBusinessException;
//import com.auraboot.framework.meta.exception.MetaErrorCode;
//import lombok.extern.slf4j.Slf4j;
//import org.junit.jupiter.api.DisplayName;
//import org.junit.jupiter.api.Test;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.boot.test.context.SpringBootTest;
//import org.springframework.stereotype.Component;
//import org.springframework.test.context.ActiveProfiles;
//
//import static org.junit.jupiter.api.Assertions.*;
//
///**
// * GitFirstAspect 单元测试
// * 测试 Git-First 切面的拦截和验证逻辑
// *
// * @author AuraBoot Team
// * @since 2.2.0
// */
//@Slf4j
//@SpringBootTest(classes = TestApplication.class)
//@ActiveProfiles("integration-test")
//@DisplayName("GitFirstAspect 单元测试")
//class GitFirstAspectTest {
//
//    @Autowired
//    private GitFirstAspect gitFirstAspect;
//
//    @Autowired
//    private LayeredGitFirstRouter router;
//
//    @Autowired
//    private TestService testService;
//
//    // ==================== 核心层资源测试 ====================
//
//    @Test
//    @DisplayName("核心层资源 - DICT CREATE 应该被拦截")
//    void testDictCreateShouldBeBlocked() {
//        // 验证 DICT 是核心层资源
//        assertTrue(router.requiresGitFirst("dict"));
//
//        // 执行操作应该抛出异常
//        MetaBusinessException exception = assertThrows(
//            MetaBusinessException.class,
//            () -> testService.createDict("test_dict")
//        );
//
//        // 验证异常信息
//        assertEquals(MetaErrorCode.GIT_FIRST_VIOLATION, exception.getErrorCode());
//        assertTrue(exception.getMessage().contains("字典"));
//        assertTrue(exception.getMessage().contains("Git"));
//        assertTrue(exception.getMessage().contains("dsl/dicts/"));
//    }
//
//    @Test
//    @DisplayName("核心层资源 - FIELD UPDATE 应该被拦截")
//    void testFieldUpdateShouldBeBlocked() {
//        assertTrue(router.requiresGitFirst("field"));
//
//        MetaBusinessException exception = assertThrows(
//            MetaBusinessException.class,
//            () -> testService.updateField("test_field")
//        );
//
//        assertEquals(MetaErrorCode.GIT_FIRST_VIOLATION, exception.getErrorCode());
//        assertTrue(exception.getMessage().contains("字段"));
//        assertTrue(exception.getMessage().contains("dsl/fields/"));
//    }
//
//    @Test
//    @DisplayName("核心层资源 - MODEL DELETE 应该被拦截")
//    void testModelDeleteShouldBeBlocked() {
//        assertTrue(router.requiresGitFirst("model"));
//
//        MetaBusinessException exception = assertThrows(
//            MetaBusinessException.class,
//            () -> testService.deleteModel("test_model")
//        );
//
//        assertEquals(MetaErrorCode.GIT_FIRST_VIOLATION, exception.getErrorCode());
//        assertTrue(exception.getMessage().contains("模型"));
//    }
//
//    // ==================== 配置层资源测试 ====================
//
//    @Test
//    @DisplayName("配置层资源 - MENU_CONFIG 应该允许在线操作")
//    void testMenuConfigShouldBeAllowed() {
//        // 验证 MENU_CONFIG 不是核心层资源
//        assertFalse(router.requiresGitFirst("menu_config"));
//
//        // 执行操作不应该抛出异常
//        assertDoesNotThrow(() -> testService.createMenuConfig("test_menu"));
//    }
//
//    @Test
//    @DisplayName("配置层资源 - DICT_ITEM 应该允许在线操作")
//    void testDictItemShouldBeAllowed() {
//        assertFalse(router.requiresGitFirst("dict_item"));
//        assertDoesNotThrow(() -> testService.createDictItem("test_item"));
//    }
//
//    // ==================== 错误消息测试 ====================
//
//    @Test
//    @DisplayName("错误消息应该包含操作类型")
//    void testErrorMessageShouldContainOperationType() {
//        MetaBusinessException createException = assertThrows(
//            MetaBusinessException.class,
//            () -> testService.createDict("test")
//        );
//        assertTrue(createException.getMessage().contains("创建"));
//
//        MetaBusinessException updateException = assertThrows(
//            MetaBusinessException.class,
//            () -> testService.updateDict("test")
//        );
//        assertTrue(updateException.getMessage().contains("更新"));
//
//        MetaBusinessException deleteException = assertThrows(
//            MetaBusinessException.class,
//            () -> testService.deleteDict("test")
//        );
//        assertTrue(deleteException.getMessage().contains("删除"));
//    }
//
//    @Test
//    @DisplayName("错误消息应该包含 DSL 路径")
//    void testErrorMessageShouldContainDslPath() {
//        MetaBusinessException exception = assertThrows(
//            MetaBusinessException.class,
//            () -> testService.createDict("test")
//        );
//
//        String message = exception.getMessage();
//        assertTrue(message.contains("dsl/dicts/"));
//        assertTrue(message.contains(".json"));
//    }
//
//    @Test
//    @DisplayName("错误消息应该包含操作步骤")
//    void testErrorMessageShouldContainSteps() {
//        MetaBusinessException exception = assertThrows(
//            MetaBusinessException.class,
//            () -> testService.createDict("test")
//        );
//
//        String message = exception.getMessage();
//        assertTrue(message.contains("1."));
//        assertTrue(message.contains("2."));
//        assertTrue(message.contains("3."));
//    }
//
//    // ==================== 边界情况测试 ====================
//
//    @Test
//    @DisplayName("未知资源类型应该默认拦截")
//    void testUnknownResourceTypeShouldBeBlocked() {
//        // 未知资源类型默认需要 Git-First
//        assertTrue(router.requiresGitFirst("unknown_resource"));
//
//        MetaBusinessException exception = assertThrows(
//            MetaBusinessException.class,
//            () -> testService.createUnknownResource("test")
//        );
//
//        assertEquals(MetaErrorCode.GIT_FIRST_VIOLATION, exception.getErrorCode());
//    }
//
//    @Test
//    @DisplayName("空资源类型应该抛出异常")
//    void testNullResourceTypeShouldThrowException() {
//        assertThrows(
//            Exception.class,
//            () -> testService.createNullResource("test")
//        );
//    }
//
//    // ==================== 测试服务类 ====================
//
//    /**
//     * 测试服务 - 用于模拟各种 Git-First 场景
//     */
//    @Component
//    static class TestService {
//
//        // 核心层资源操作
//
//        @GitFirstCheck(resourceType = "dict", operation = GitFirstOperation.CREATE)
//        public void createDict(String code) {
//            log.info("Creating dict: {}", code);
//        }
//
//        @GitFirstCheck(resourceType = "dict", operation = GitFirstOperation.UPDATE)
//        public void updateDict(String code) {
//            log.info("Updating dict: {}", code);
//        }
//
//        @GitFirstCheck(resourceType = "dict", operation = GitFirstOperation.DELETE)
//        public void deleteDict(String code) {
//            log.info("Deleting dict: {}", code);
//        }
//
//        @GitFirstCheck(resourceType = "field", operation = GitFirstOperation.UPDATE)
//        public void updateField(String code) {
//            log.info("Updating field: {}", code);
//        }
//
//        @GitFirstCheck(resourceType = "model", operation = GitFirstOperation.DELETE)
//        public void deleteModel(String code) {
//            log.info("Deleting model: {}", code);
//        }
//
//        // 配置层资源操作（不应该被拦截）
//
//        @GitFirstCheck(resourceType = "menu_config", operation = GitFirstOperation.CREATE)
//        public void createMenuConfig(String code) {
//            log.info("Creating menu config: {}", code);
//        }
//
//        @GitFirstCheck(resourceType = "dict_item", operation = GitFirstOperation.CREATE)
//        public void createDictItem(String code) {
//            log.info("Creating dict item: {}", code);
//        }
//
//        // 边界情况
//
//        @GitFirstCheck(resourceType = "unknown_resource", operation = GitFirstOperation.CREATE)
//        public void createUnknownResource(String code) {
//            log.info("Creating unknown resource: {}", code);
//        }
//
//        @GitFirstCheck(resourceType = "", operation = GitFirstOperation.CREATE)
//        public void createNullResource(String code) {
//            log.info("Creating null resource: {}", code);
//        }
//    }
//}

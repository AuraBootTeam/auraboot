package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.meta.dto.PageSchemaVersionDTO;
import com.auraboot.framework.meta.dto.PageSchemaVersionComparisonDTO;
import com.auraboot.framework.meta.entity.PageSchema;
import com.auraboot.framework.meta.entity.PageSchemaHistory;
import com.auraboot.framework.meta.mapper.PageSchemaMapper;
import com.auraboot.framework.meta.mapper.PageSchemaHistoryMapper;
import com.auraboot.framework.exception.ValidationException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * PageSchemaVersionServiceImpl 单元测试
 * 测试版本管理服务的所有功能，包括版本创建、比较、回滚和发布
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PageSchemaVersionService 单元测试")
@Transactional
class PageSchemaVersionServiceImplTest {

    @Mock
    private PageSchemaMapper pageSchemaMapper;

    @Mock
    private PageSchemaHistoryMapper pageSchemaHistoryMapper;

    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private PageSchemaVersionServiceImpl versionService;

    private PageSchema testPageSchema;
    private PageSchemaHistory testHistory;
    private Map<String, Object> testSnapshot;

    @BeforeEach
    void setUp() {
        // 初始化测试数据
        testPageSchema = createTestPageSchema();
        testHistory = createTestHistory();
        testSnapshot = createTestSnapshot();
    }

    private PageSchema createTestPageSchema() {
        PageSchema schema = new PageSchema();
        schema.setId(1L);
        schema.setPid("test-page-pid");
        schema.setTenantId(1L);
        schema.setName("test-page");
        schema.setTitle("Test Page");
        schema.setPageType("form");
        schema.setDslSchema("{\"type\":\"form\",\"fields\":[]}");
        schema.setVersion(1);
        schema.setStatus("draft");
        schema.setDeletedFlag(false);
        schema.setCreatedAt(Instant.now());
        schema.setUpdatedAt(Instant.now());
        return schema;
    }

    private PageSchemaHistory createTestHistory() {
        PageSchemaHistory history = new PageSchemaHistory();
        history.setId(1L);
        history.setPid("test-page-pid");
        history.setOp("create");
        history.setOpBy("test-user");
        history.setOpAt(Instant.now());
        history.setSnapshot(testSnapshot);
        history.setCreatedAt(Instant.now());
        return history;
    }

    private Map<String, Object> createTestSnapshot() {
        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("name", "test-page");
        snapshot.put("title", "Test Page");
        snapshot.put("pageType", "form");
        snapshot.put("dslSchema", "{\"type\":\"form\",\"fields\":[]}");
        snapshot.put("version", 1);
        snapshot.put("status", "draft");
        return snapshot;
    }

    @Nested
    @DisplayName("版本创建测试")
    class CreateVersionTests {

        @Test
        @DisplayName("成功创建版本")
        void testCreateVersion_Success() {
            // Given
            when(pageSchemaMapper.selectByPid("test-page-pid")).thenReturn(testPageSchema);
            when(pageSchemaHistoryMapper.insert(any(PageSchemaHistory.class))).thenReturn(1);

            // When
            PageSchemaVersionDTO result = versionService.createVersion(
                "test-page-pid", "update", "test-user", "测试更新");

            // Then
            assertThat(result).isNotNull();
            assertThat(result.getPagePid()).isEqualTo("test-page-pid");
            assertThat(result.getOperation()).isEqualTo("update");
            assertThat(result.getOperatorPid()).isEqualTo("test-user");
            verify(pageSchemaHistoryMapper).insert(any(PageSchemaHistory.class));
        }

        @Test
        @DisplayName("页面PID为空时抛出异常")
        void testCreateVersion_EmptyPagePid() {
            // When & Then
            assertThatThrownBy(() -> versionService.createVersion(
                "", "update", "test-user", "测试更新"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("页面PID不能为空");
        }

        @Test
        @DisplayName("操作类型为空时抛出异常")
        void testCreateVersion_EmptyOperation() {
            // When & Then
            assertThatThrownBy(() -> versionService.createVersion(
                "test-page-pid", "", "test-user", "测试更新"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("操作类型不能为空");
        }

        @Test
        @DisplayName("操作人为空时抛出异常")
        void testCreateVersion_EmptyOperator() {
            // When & Then
            assertThatThrownBy(() -> versionService.createVersion(
                "test-page-pid", "update", "", "测试更新"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("操作人PID不能为空");
        }

        @Test
        @DisplayName("页面不存在时抛出异常")
        void testCreateVersion_PageNotFound() {
            // Given
            when(pageSchemaMapper.selectByPid("non-existent-pid")).thenReturn(null);

            // When & Then
            assertThatThrownBy(() -> versionService.createVersion(
                "non-existent-pid", "update", "test-user", "测试更新"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("页面配置不存在");
        }
    }

    @Nested
    @DisplayName("版本历史查询测试")
    class VersionHistoryTests {

        @Test
        @DisplayName("成功获取版本历史列表")
        void testGetVersionHistory_Success() {
            // Given
            List<PageSchemaHistory> histories = Arrays.asList(testHistory);
            when(pageSchemaHistoryMapper.findByPagePid("test-page-pid")).thenReturn(histories);

            // When
            List<PageSchemaVersionDTO> result = versionService.getVersionHistory("test-page-pid");

            // Then
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getPagePid()).isEqualTo("test-page-pid");
        }

        @Test
        @DisplayName("页面PID为空时抛出异常")
        void testGetVersionHistory_EmptyPagePid() {
            // When & Then
            assertThatThrownBy(() -> versionService.getVersionHistory(""))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("页面PID不能为空");
        }

        @Test
        @DisplayName("成功根据ID获取版本")
        void testGetVersionById_Success() {
            // Given
            when(pageSchemaHistoryMapper.selectById(1L)).thenReturn(testHistory);

            // When
            PageSchemaVersionDTO result = versionService.getVersionById(1L);

            // Then
            assertThat(result).isNotNull();
            assertThat(result.getId()).isEqualTo(1L);
        }

        @Test
        @DisplayName("版本ID为空时抛出异常")
        void testGetVersionById_NullId() {
            // When & Then
            assertThatThrownBy(() -> versionService.getVersionById(null))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("历史记录ID不能为空");
        }

        @Test
        @DisplayName("版本不存在时抛出异常")
        void testGetVersionById_NotFound() {
            // Given
            when(pageSchemaHistoryMapper.selectById(999L)).thenReturn(null);

            // When & Then
            assertThatThrownBy(() -> versionService.getVersionById(999L))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("版本记录不存在");
        }

        @Test
        @DisplayName("成功获取最新版本")
        void testGetLatestVersion_Success() {
            // Given
            when(pageSchemaHistoryMapper.findLatestByPagePid("test-page-pid")).thenReturn(testHistory);

            // When
            PageSchemaVersionDTO result = versionService.getLatestVersion("test-page-pid");

            // Then
            assertThat(result).isNotNull();
            assertThat(result.getPagePid()).isEqualTo("test-page-pid");
        }

        @Test
        @DisplayName("没有版本历史时返回null")
        void testGetLatestVersion_NoHistory() {
            // Given
            when(pageSchemaHistoryMapper.findLatestByPagePid("test-page-pid")).thenReturn(null);

            // When
            PageSchemaVersionDTO result = versionService.getLatestVersion("test-page-pid");

            // Then
            assertThat(result).isNull();
        }
    }

    @Nested
    @DisplayName("版本比较测试")
    class VersionComparisonTests {

        @Test
        @DisplayName("成功比较两个版本")
        void testCompareVersions_Success() {
            // Given
            PageSchemaHistory sourceHistory = createTestHistory();
            PageSchemaHistory targetHistory = createTestHistory();
            targetHistory.setId(2L);
            Map<String, Object> targetSnapshot = new HashMap<>(testSnapshot);
            targetSnapshot.put("title", "Updated Test Page");
            targetHistory.setSnapshot(targetSnapshot);

            when(pageSchemaHistoryMapper.selectById(1L)).thenReturn(sourceHistory);
            when(pageSchemaHistoryMapper.selectById(2L)).thenReturn(targetHistory);

            // When
            PageSchemaVersionComparisonDTO result = versionService.compareVersions(1L, 2L);

            // Then
            assertThat(result).isNotNull();
            assertThat(result.getSourceVersion()).isNotNull();
            assertThat(result.getTargetVersion()).isNotNull();
            assertThat(result.getDifferences()).isNotEmpty();
            assertThat(result.getSummary()).isNotNull();
        }

        @Test
        @DisplayName("版本ID为空时抛出异常")
        void testCompareVersions_NullIds() {
            // When & Then
            assertThatThrownBy(() -> versionService.compareVersions(null, 2L))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("版本ID不能为空");
        }

        @Test
        @DisplayName("源版本不存在时抛出异常")
        void testCompareVersions_SourceNotFound() {
            // Given
            when(pageSchemaHistoryMapper.selectById(1L)).thenReturn(null);
            when(pageSchemaHistoryMapper.selectById(2L)).thenReturn(testHistory);

            // When & Then
            assertThatThrownBy(() -> versionService.compareVersions(1L, 2L))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("源版本不存在");
        }

        @Test
        @DisplayName("成功与当前版本比较")
        void testCompareWithCurrent_Success() {
            // Given
            // 确保历史记录有快照数据
            testHistory.setSnapshot(testSnapshot);
            
            when(pageSchemaMapper.selectByPid("test-page-pid")).thenReturn(testPageSchema);
            when(pageSchemaHistoryMapper.selectById(1L)).thenReturn(testHistory);

            // When
            PageSchemaVersionComparisonDTO result = versionService.compareWithCurrent("test-page-pid", 1L);

            // Then
            assertThat(result).isNotNull();
            assertThat(result.getSourceVersion()).isNotNull();
            assertThat(result.getTargetVersion()).isNotNull();
            
            // 验证调用
            verify(pageSchemaMapper).selectByPid("test-page-pid");
            verify(pageSchemaHistoryMapper).selectById(1L);
        }
    }

    @Nested
    @DisplayName("版本回滚测试")
    class RollbackTests {

        @Test
        @DisplayName("成功回滚到指定版本")
        void testRollbackToVersion_Success() {
            // Given
            // 确保历史记录有快照数据
            testHistory.setSnapshot(testSnapshot);
            
            when(pageSchemaMapper.selectByPid("test-page-pid")).thenReturn(testPageSchema);
            when(pageSchemaHistoryMapper.selectById(1L)).thenReturn(testHistory);
            when(pageSchemaMapper.updateById(any(PageSchema.class))).thenReturn(1);
            when(pageSchemaHistoryMapper.insert(any(PageSchemaHistory.class))).thenReturn(1);

            // When
            PageSchemaVersionDTO result = versionService.rollbackToVersion(
                "test-page-pid", 1L, "test-user", "回滚测试");

            // Then
            assertThat(result).isNotNull();
            assertThat(result.getOperation()).isEqualTo("rollback");
            verify(pageSchemaMapper).updateById(any(PageSchema.class));
            verify(pageSchemaHistoryMapper, times(2)).insert(any(PageSchemaHistory.class)); // 备份 + 回滚
        }

        @Test
        @DisplayName("参数验证失败时抛出异常")
        void testRollbackToVersion_InvalidParams() {
            // When & Then
            assertThatThrownBy(() -> versionService.rollbackToVersion(
                "", 1L, "test-user", "回滚测试"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("页面PID不能为空");
        }

        @Test
        @DisplayName("检查是否可以回滚 - 成功")
        void testCanRollbackToVersion_Success() {
            // Given
            // 确保历史记录有快照数据
            testHistory.setSnapshot(testSnapshot);
            
            when(pageSchemaMapper.selectByPid("test-page-pid")).thenReturn(testPageSchema);
            when(pageSchemaHistoryMapper.selectById(1L)).thenReturn(testHistory);

            // When
            boolean result = versionService.canRollbackToVersion("test-page-pid", 1L);

            // Then
            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("检查是否可以回滚 - 页面不存在")
        void testCanRollbackToVersion_PageNotFound() {
            // Given
            when(pageSchemaMapper.selectByPid("test-page-pid")).thenReturn(null);

            // When
            boolean result = versionService.canRollbackToVersion("test-page-pid", 1L);

            // Then
            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("检查是否可以回滚 - 版本不存在")
        void testCanRollbackToVersion_VersionNotFound() {
            // Given
            when(pageSchemaMapper.selectByPid("test-page-pid")).thenReturn(testPageSchema);
            when(pageSchemaHistoryMapper.selectById(1L)).thenReturn(null);

            // When
            boolean result = versionService.canRollbackToVersion("test-page-pid", 1L);

            // Then
            assertThat(result).isFalse();
        }
    }

    @Nested
    @DisplayName("版本发布测试")
    class PublishTests {

        @Test
        @DisplayName("成功发布版本")
        void testPublishVersion_Success() {
            // Given
            testHistory.setSnapshot(testSnapshot);
            when(pageSchemaMapper.selectByPid("test-page-pid")).thenReturn(testPageSchema);
            when(pageSchemaHistoryMapper.selectById(1L)).thenReturn(testHistory);
            when(pageSchemaMapper.updateById(any(PageSchema.class))).thenReturn(1);
            when(pageSchemaHistoryMapper.insert(any(PageSchemaHistory.class))).thenReturn(1);

            // When
            PageSchemaVersionDTO result = versionService.publishVersion("test-page-pid", 1L, "test-user");

            // Then
            assertThat(result).isNotNull();
            assertThat(result.getOperation()).isEqualTo("publish");
            verify(pageSchemaMapper).updateById(any(PageSchema.class));
        }

        @Test
        @DisplayName("参数验证失败时抛出异常")
        void testPublishVersion_InvalidParams() {
            // When & Then
            assertThatThrownBy(() -> versionService.publishVersion("", 1L, "test-user"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("页面PID不能为空");
        }

        @Test
        @DisplayName("成功取消发布版本")
        void testUnpublishVersion_Success() {
            // Given
            testPageSchema.setStatus("published");
            when(pageSchemaMapper.selectByPid("test-page-pid")).thenReturn(testPageSchema);
            when(pageSchemaMapper.updateById(any(PageSchema.class))).thenReturn(1);
            when(pageSchemaHistoryMapper.insert(any(PageSchemaHistory.class))).thenReturn(1);

            // When
            PageSchemaVersionDTO result = versionService.unpublishVersion("test-page-pid", 1L, "test-user");

            // Then
            assertThat(result).isNotNull();
            assertThat(result.getOperation()).isEqualTo("unpublish");
            verify(pageSchemaMapper).updateById(any(PageSchema.class));
        }
    }

    @Nested
    @DisplayName("版本统计测试")
    class VersionStatsTests {

        @Test
        @DisplayName("成功统计版本数量")
        void testCountVersions_Success() {
            // Given
            when(pageSchemaHistoryMapper.countByPagePid("test-page-pid")).thenReturn(5L);

            // When
            Long result = versionService.countVersions("test-page-pid");

            // Then
            assertThat(result).isEqualTo(5L);
        }

        @Test
        @DisplayName("页面PID为空时抛出异常")
        void testCountVersions_EmptyPagePid() {
            // When & Then
            assertThatThrownBy(() -> versionService.countVersions(""))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("页面PID不能为空");
        }

        @Test
        @DisplayName("成功按操作类型统计版本数量")
        void testCountVersionsByOperation_Success() {
            // Given
            when(pageSchemaHistoryMapper.countByPagePidAndOp("test-page-pid", "update")).thenReturn(3L);

            // When
            Long result = versionService.countVersionsByOperation("test-page-pid", "update");

            // Then
            assertThat(result).isEqualTo(3L);
        }

        @Test
        @DisplayName("成功获取已发布版本列表")
        void testGetPublishedVersions_Success() {
            // Given
            List<PageSchemaHistory> publishedHistories = Arrays.asList(testHistory);
            when(pageSchemaHistoryMapper.findByPagePidAndOp("test-page-pid", "publish"))
                .thenReturn(publishedHistories);

            // When
            List<PageSchemaVersionDTO> result = versionService.getPublishedVersions("test-page-pid");

            // Then
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getPagePid()).isEqualTo("test-page-pid");
        }
    }

    @Nested
    @DisplayName("版本清理测试")
    class VersionCleanupTests {

        @Test
        @DisplayName("成功清理旧版本")
        void testCleanupOldVersions_Success() {
            // Given
            List<PageSchemaHistory> allHistories = Arrays.asList(
                createHistoryWithId(1L), createHistoryWithId(2L), 
                createHistoryWithId(3L), createHistoryWithId(4L), 
                createHistoryWithId(5L));
            when(pageSchemaHistoryMapper.findByPagePid("test-page-pid")).thenReturn(allHistories);
            when(pageSchemaHistoryMapper.deleteById(anyLong())).thenReturn(1);

            // When
            Integer result = versionService.cleanupOldVersions("test-page-pid", 3);

            // Then
            assertThat(result).isEqualTo(2); // 删除了2个旧版本
            verify(pageSchemaHistoryMapper, times(2)).deleteById(anyLong());
        }

        @Test
        @DisplayName("保留数量大于等于总数时不删除")
        void testCleanupOldVersions_NoCleanupNeeded() {
            // Given
            List<PageSchemaHistory> allHistories = Arrays.asList(
                createHistoryWithId(1L), createHistoryWithId(2L));
            when(pageSchemaHistoryMapper.findByPagePid("test-page-pid")).thenReturn(allHistories);

            // When
            Integer result = versionService.cleanupOldVersions("test-page-pid", 5);

            // Then
            assertThat(result).isEqualTo(0);
            verify(pageSchemaHistoryMapper, never()).deleteById(anyLong());
        }

        private PageSchemaHistory createHistoryWithId(Long id) {
            PageSchemaHistory history = createTestHistory();
            history.setId(id);
            // 使用非重要操作类型，这样可以被清理
            history.setOp("update");
            return history;
        }
    }

    @Nested
    @DisplayName("版本完整性验证测试")
    class VersionIntegrityTests {

        @Test
        @DisplayName("版本完整性验证成功")
        void testValidateVersionIntegrity_Success() {
            // Given
            // 确保历史记录有快照数据
            testHistory.setSnapshot(testSnapshot);
            
            when(pageSchemaHistoryMapper.selectById(1L)).thenReturn(testHistory);

            // When
            boolean result = versionService.validateVersionIntegrity(1L);

            // Then
            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("版本不存在时验证失败")
        void testValidateVersionIntegrity_VersionNotFound() {
            // Given
            when(pageSchemaHistoryMapper.selectById(1L)).thenReturn(null);

            // When
            boolean result = versionService.validateVersionIntegrity(1L);

            // Then
            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("快照数据为空时验证失败")
        void testValidateVersionIntegrity_EmptySnapshot() {
            // Given
            testHistory.setSnapshot(null);
            when(pageSchemaHistoryMapper.selectById(1L)).thenReturn(testHistory);

            // When
            boolean result = versionService.validateVersionIntegrity(1L);

            // Then
            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("快照缺少必要字段时验证失败")
        void testValidateVersionIntegrity_MissingRequiredFields() {
            // Given
            Map<String, Object> incompleteSnapshot = new HashMap<>();
            incompleteSnapshot.put("title", "Test Page");
            // 缺少 name 和 dslSchema 字段
            testHistory.setSnapshot(incompleteSnapshot);
            when(pageSchemaHistoryMapper.selectById(1L)).thenReturn(testHistory);

            // When
            boolean result = versionService.validateVersionIntegrity(1L);

            // Then
            assertThat(result).isFalse();
        }
    }
}

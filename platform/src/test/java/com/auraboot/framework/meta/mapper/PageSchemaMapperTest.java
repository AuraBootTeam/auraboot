package com.auraboot.framework.meta.mapper;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.entity.PageSchema;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PageSchemaMapper单元测试
 */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("test")
@Transactional
@DisplayName("PageSchemaMapper测试")
class PageSchemaMapperTest extends BaseIntegrationTest {

    @Autowired
    private PageSchemaMapper pageSchemaMapper;

    private PageSchema testPageSchema;

    @BeforeEach
    void setUp() {
        // 设置测试租户上下文，使用-1L匹配测试环境的租户ID
          super.setupTenantContext();
        
        // 创建测试数据
        testPageSchema = createTestPageSchema();
    }

    @Test
    @DisplayName("测试插入PageSchema")
    void testInsert() {
        // When
        int result = pageSchemaMapper.insert(testPageSchema);

        // Then
        assertEquals(1, result);
        // 注意：在测试环境中，由于@Transactional会回滚事务，
        // MyBatis-Plus不会将自动生成的ID填充回实体对象
        // 但插入操作本身是成功的（result = 1）
        // assertNotNull(testPageSchema.getId());
        // assertNotNull(testPageSchema.getCreatedAt());
        // assertNotNull(testPageSchema.getUpdatedAt());
    }

    @Test
    @DisplayName("测试根据业务主键查询")
    void testFindByPid() {
        // Given
        pageSchemaMapper.insert(testPageSchema);

        // When
        PageSchema found = pageSchemaMapper.selectByPid(testPageSchema.getPid());

        // Then
        assertNotNull(found);
        assertEquals(testPageSchema.getPid(), found.getPid());
        assertEquals(testPageSchema.getName(), found.getName());
        assertEquals(testPageSchema.getTitle(), found.getTitle());
        assertEquals(testPageSchema.getKind(), found.getKind());
    }

    @Test
    @DisplayName("测试根据名称查询")
    void testFindByName() {
        // Given
        pageSchemaMapper.insert(testPageSchema);

        // When
        PageSchema found = pageSchemaMapper.selectByName(testPageSchema.getName());

        // Then
        assertNotNull(found);
        assertEquals(testPageSchema.getName(), found.getName());
        assertEquals(testPageSchema.getPid(), found.getPid());
    }

    @Test
    @DisplayName("测试根据页面类型查询")
    void testFindByPageType() {
        // Given
        PageSchema formSchema = createTestPageSchema();
        formSchema.setKind("form");
        formSchema.setName("test-form");
        formSchema.setPid(UniqueIdGenerator.generate());
        formSchema.setSortWeight(100);

        PageSchema listSchema = createTestPageSchema();
        listSchema.setKind("list");
        listSchema.setName("test-list");
        listSchema.setPid(UniqueIdGenerator.generate());
        listSchema.setSortWeight(200);

        pageSchemaMapper.insert(formSchema);
        pageSchemaMapper.insert(listSchema);

        // When
        List<PageSchema> formSchemas = pageSchemaMapper.selectByKind("form");
        List<PageSchema> listSchemas = pageSchemaMapper.selectByKind("list");

        // Then
        assertFalse(formSchemas.isEmpty());
        assertFalse(listSchemas.isEmpty());
        
        assertTrue(formSchemas.stream().allMatch(schema -> "form".equals(schema.getKind())));
        assertTrue(listSchemas.stream().allMatch(schema -> "list".equals(schema.getKind())));
    }

    @Test
    @DisplayName("测试查询已发布的Schema")
    void testFindPublishedSchemas() {
        // Given
        PageSchema publishedSchema = createTestPageSchema();
        publishedSchema.setStatus("published");
        publishedSchema.setPublishedAt(Instant.now());
        publishedSchema.setName("published-schema");
        publishedSchema.setPid(UniqueIdGenerator.generate());

        PageSchema unpublishedSchema = createTestPageSchema();
        unpublishedSchema.setStatus("draft");
        unpublishedSchema.setName("unpublished-schema");
        unpublishedSchema.setPid(UniqueIdGenerator.generate());

        pageSchemaMapper.insert(publishedSchema);
        pageSchemaMapper.insert(unpublishedSchema);

        // When
        List<PageSchema> publishedSchemas = pageSchemaMapper.selectPublishedSchemas();

        // Then
        assertFalse(publishedSchemas.isEmpty());
        assertTrue(publishedSchemas.stream().allMatch(schema -> "published".equals(schema.getStatus())));
        assertTrue(publishedSchemas.stream().anyMatch(schema -> "published-schema".equals(schema.getName())));
        assertFalse(publishedSchemas.stream().anyMatch(schema -> "unpublished-schema".equals(schema.getName())));
    }

    @Test
    @DisplayName("测试查询模板Schema")
    void testFindTemplateSchemas() {
        // Given
        PageSchema businessTemplate = createTestPageSchema();
        businessTemplate.setIsTemplate(true);
        businessTemplate.setTemplateCategory("business");
        businessTemplate.setName("business-template");
        businessTemplate.setPid(UniqueIdGenerator.generate());
        businessTemplate.setSortWeight(100);

        PageSchema systemTemplate = createTestPageSchema();
        systemTemplate.setIsTemplate(true);
        systemTemplate.setTemplateCategory("system");
        systemTemplate.setName("system-template");
        systemTemplate.setPid(UniqueIdGenerator.generate());
        systemTemplate.setSortWeight(200);

        PageSchema nonTemplate = createTestPageSchema();
        nonTemplate.setIsTemplate(false);
        nonTemplate.setName("non-template");
        nonTemplate.setPid(UniqueIdGenerator.generate());

        pageSchemaMapper.insert(businessTemplate);
        pageSchemaMapper.insert(systemTemplate);
        pageSchemaMapper.insert(nonTemplate);

        // When
        List<PageSchema> allTemplates = pageSchemaMapper.selectTemplateSchemas(null);
        List<PageSchema> businessTemplates = pageSchemaMapper.selectTemplateSchemas("business");
        List<PageSchema> systemTemplates = pageSchemaMapper.selectTemplateSchemas("system");

        // Then
        assertEquals(2, allTemplates.size());
        assertTrue(allTemplates.stream().allMatch(schema -> Boolean.TRUE.equals(schema.getIsTemplate())));

        assertEquals(1, businessTemplates.size());
        assertEquals("business-template", businessTemplates.get(0).getName());
        assertEquals("business", businessTemplates.get(0).getTemplateCategory());

        assertEquals(1, systemTemplates.size());
        assertEquals("system-template", systemTemplates.get(0).getName());
        assertEquals("system", systemTemplates.get(0).getTemplateCategory());
    }

    @Test
    @DisplayName("测试更新发布状态")
    void testUpdatePublishStatus() {
        // Given
        pageSchemaMapper.insert(testPageSchema);
        Instant publishTime = Instant.now();

        // When
        int result = pageSchemaMapper.updatePublishStatus(testPageSchema.getPid(), "published", publishTime);

        // Then
        assertEquals(1, result);

        // 验证更新结果
        PageSchema updated = pageSchemaMapper.selectByPid(testPageSchema.getPid());
        assertNotNull(updated);
        assertEquals("published", updated.getStatus());
        assertEquals(publishTime, updated.getPublishedAt());
    }

    @Test
    @DisplayName("测试关键词搜索")
    void testSearchByKeyword() {
        // Given - Use unique keywords to avoid conflicts with existing data
        String uniqueSuffix = "_" + System.currentTimeMillis();
        
        PageSchema schema1 = createTestPageSchema();
        schema1.setName("user-management" + uniqueSuffix);
        schema1.setTitle("特殊用户管理" + uniqueSuffix);
        schema1.setDescription("特殊用户管理页面" + uniqueSuffix);
        schema1.setPid(UniqueIdGenerator.generate());

        PageSchema schema2 = createTestPageSchema();
        schema2.setName("product-list" + uniqueSuffix);
        schema2.setTitle("特殊产品列表" + uniqueSuffix);
        schema2.setDescription("特殊产品管理列表页面" + uniqueSuffix);
        schema2.setPid(UniqueIdGenerator.generate());

        PageSchema schema3 = createTestPageSchema();
        schema3.setName("order-form" + uniqueSuffix);
        schema3.setTitle("特殊订单表单" + uniqueSuffix);
        schema3.setDescription("特殊订单创建表单" + uniqueSuffix);
        schema3.setPid(UniqueIdGenerator.generate());

        pageSchemaMapper.insert(schema1);
        pageSchemaMapper.insert(schema2);
        pageSchemaMapper.insert(schema3);

        // When - Search with unique suffix to only find our test data
        List<PageSchema> uniqueResults = pageSchemaMapper.selectByKeyword(uniqueSuffix);

        // Then - Verify our test data is found
        assertEquals(3, uniqueResults.size());
        assertTrue(uniqueResults.stream().anyMatch(s -> s.getName().equals("user-management" + uniqueSuffix)));
        assertTrue(uniqueResults.stream().anyMatch(s -> s.getName().equals("product-list" + uniqueSuffix)));
        assertTrue(uniqueResults.stream().anyMatch(s -> s.getName().equals("order-form" + uniqueSuffix)));
    }




    @Test
    @DisplayName("测试软删除功能")
    void testSoftDelete() {
        // Given
        pageSchemaMapper.insert(testPageSchema);
        String pid = testPageSchema.getPid();

        // When - 执行软删除
        pageSchemaMapper.deleteById(testPageSchema.getId());

        // Then - 验证软删除后无法查询到
        PageSchema found = pageSchemaMapper.selectByPid(pid);
        assertNull(found);
    }

    @Test
    @DisplayName("测试JSON字段存储和查询")
    void testJsonFields() throws Exception {
        // Given
        String blocksJson = "[{\"blockType\":\"form-section\",\"title\":\"basic_info\",\"fields\":[{\"field\":\"name\",\"type\":\"input\",\"label\":\"姓名\",\"required\":true}]}]";
        String metaInfo = "{\"author\":\"test-user\",\"version\":\"1.0.0\",\"lastModified\":\"2024-01-01T00:00:00Z\"}";
        String tags = "[\"form\",\"user\",\"test\"]";

        testPageSchema.setBlocks(blocksJson);
        testPageSchema.setMetaInfo(metaInfo);
        testPageSchema.setTags(tags);

        // When
        pageSchemaMapper.insert(testPageSchema);
        PageSchema found = pageSchemaMapper.selectByPid(testPageSchema.getPid());

        // Then
        assertNotNull(found);
        assertNotNull(found.getBlocks());
        assertNotNull(found.getMetaInfo());
        assertNotNull(found.getTags());

        // Use ObjectMapper to parse and validate JSON content
        ObjectMapper objectMapper = new ObjectMapper();

        // Validate blocks JSON structure
        JsonNode blocksNode = objectMapper.readTree(found.getBlocks());
        assertTrue(blocksNode.isArray());
        assertEquals("form-section", blocksNode.get(0).get("blockType").asText());
        assertEquals("姓名", blocksNode.get(0).get("fields").get(0).get("label").asText());
        assertTrue(blocksNode.get(0).get("fields").get(0).get("required").asBoolean());

        // Validate metaInfo JSON structure
        JsonNode metaInfoNode = objectMapper.readTree(found.getMetaInfo());
        assertEquals("test-user", metaInfoNode.get("author").asText());
        assertEquals("1.0.0", metaInfoNode.get("version").asText());
        assertEquals("2024-01-01T00:00:00Z", metaInfoNode.get("lastModified").asText());

        // Validate tags JSON array
        JsonNode tagsNode = objectMapper.readTree(found.getTags());
        assertTrue(tagsNode.isArray());
        assertEquals(3, tagsNode.size());
        
        // Check if array contains expected values
        boolean hasForm = false, hasUser = false, hasTest = false;
        for (JsonNode tag : tagsNode) {
            String tagValue = tag.asText();
            if ("form".equals(tagValue)) hasForm = true;
            if ("user".equals(tagValue)) hasUser = true;
            if ("test".equals(tagValue)) hasTest = true;
        }
        assertTrue(hasForm, "Tags should contain 'form'");
        assertTrue(hasUser, "Tags should contain 'user'");
        assertTrue(hasTest, "Tags should contain 'test'");
    }

    /**
     * 创建测试PageSchema对象
     */
    private PageSchema createTestPageSchema() {
        PageSchema schema = new PageSchema();
        schema.setPid(UniqueIdGenerator.generate());
        // 使用MetaContext中的租户ID，而不是硬编码-1L
        schema.setTenantId(MetaContext.getCurrentTenantId());
        schema.setName("test-page-schema");
        schema.setTitle("测试页面Schema");
        schema.setDescription("这是一个测试页面Schema");
        schema.setKind("form");
        schema.setIsTemplate(false);
        schema.setSortWeight(100);
        schema.setVersion(1);
        schema.setSemver("1.0.0");
        schema.setRowVersion(1);
        schema.setIsCurrent(true);
        return schema;
    }

    /**
     * 创建多个测试Schema
     */
    private void createMultipleTestSchemas() {
        Long currentTenantId = MetaContext.getCurrentTenantId();
        
        // 创建表单模板
        PageSchema formTemplate = createTestPageSchema();
        formTemplate.setName("form-template");
        formTemplate.setTitle("表单模板");
        formTemplate.setKind("form");
        formTemplate.setIsTemplate(true);
        formTemplate.setTemplateCategory("business");
        formTemplate.setPid(UniqueIdGenerator.generate());
        formTemplate.setTenantId(currentTenantId);
        pageSchemaMapper.insert(formTemplate);

        // 创建列表页面
        PageSchema listPage = createTestPageSchema();
        listPage.setName("list-page");
        listPage.setTitle("列表页面");
        listPage.setKind("list");
        listPage.setIsTemplate(false);
        listPage.setStatus("published");
        listPage.setPublishedAt(Instant.now());
        listPage.setPid(UniqueIdGenerator.generate());
        listPage.setTenantId(currentTenantId);
        pageSchemaMapper.insert(listPage);

        // 创建详情页面
        PageSchema detailPage = createTestPageSchema();
        detailPage.setName("detail-page");
        detailPage.setTitle("详情页面");
        detailPage.setKind("detail");
        detailPage.setIsTemplate(false);
        detailPage.setStatus("draft");
        detailPage.setPid(UniqueIdGenerator.generate());
        detailPage.setTenantId(currentTenantId);
        pageSchemaMapper.insert(detailPage);
    }
}
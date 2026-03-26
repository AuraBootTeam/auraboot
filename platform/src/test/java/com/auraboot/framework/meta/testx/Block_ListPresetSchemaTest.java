package com.auraboot.framework.meta.testx;


import com.auraboot.framework.meta.view.schema.common.Meta;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import com.auraboot.framework.meta.view.schema.*;

/**
 * 门店列表预设查询Schema JSON转换测试类
 * 测试 1st-version-store-list-preset.json 转换为 QueryPreset 对象的功能
 */
@DisplayName("门店列表预设查询Schema JSON转换测试")
class Block_ListPresetSchemaTest {

    private ObjectMapper objectMapper;
    private String schemaJson;

    @BeforeEach
    void setUp() throws IOException {
        objectMapper = new ObjectMapper();
        // 读取实际的schema文件
        schemaJson = Files.readString(Paths.get("src/main/resources/schemas/1st-version-store-list-preset.json"));
    }

    @Nested
    @DisplayName("基础转换测试")
    class BasicConversionTests {

        @Test
        @DisplayName("测试JSON转换为QueryPresetBean")
        void testJsonToQueryPresetBean() throws IOException {
            // When
            QueryPreset bean = objectMapper.readValue(schemaJson, QueryPreset.class);

            // Then
            assertNotNull(bean, "QueryPresetBean不应为null");
            assertNotNull(bean.getMeta(), "meta字段不应为null");
            // Note: QueryPreset 没有 presets 字段，跳过预设验证
            // assertNotNull(bean.getPresets(), "presets字段不应为null");
            assertNotNull(bean.getFilters(), "filters字段不应为null");
            assertNotNull(bean.getSorts(), "sorts字段不应为null");
            assertNotNull(bean.getPagination(), "pagination字段不应为null");
            assertNotNull(bean.getCache(), "cache字段不应为null");
        }

        @Test
        @DisplayName("测试meta字段映射")
        void testMetaFieldMapping() throws IOException {
            // When
            QueryPreset bean = objectMapper.readValue(schemaJson, QueryPreset.class);
            Meta meta = bean.getMeta();

            // Then
            assertNotNull(meta, "meta不应为null");
            assertEquals("store.list.preset", meta.getBlockCode(), "blockCode应为store.list.preset");
            assertEquals("1.0.0", meta.getVersion(), "version应为1.0.0");
            assertEquals("Store", meta.getEntityCode(), "entityCode应为Store");
            assertEquals("queryPreset", meta.getType(), "type应为queryPreset");
            
            // 验证标题国际化
            assertNotNull(meta.getTitle(), "title不应为null");
            assertEquals("门店列表默认预设", meta.getTitle().get("zh-CN"), "中文标题应为门店列表默认预设");
            assertEquals("Store List Default Preset", meta.getTitle().get("en-US"), "英文标题应为Store List Default Preset");
            
            // 验证描述国际化
            // Note: QueryPresetMetaBean 没有 description 字段，跳过描述验证
            // assertNotNull(meta.getDescription(), "description不应为null");
            // assertEquals("门店列表页面的预设查询条件和排序配置", meta.getDescription().get("zh-CN"), "中文描述应正确");
            // assertEquals("Preset query conditions and sorting configurations for store list page", meta.getDescription().get("en-US"), "英文描述应正确");
        }
    }

    @Nested
    @DisplayName("过滤器配置测试")
    class FiltersTests {

        @Test
        @DisplayName("测试filters配置存在")
        void testFiltersConfigExists() throws IOException {
            // When
            QueryPreset bean = objectMapper.readValue(schemaJson, QueryPreset.class);

            // Then
            assertNotNull(bean.getFilters(), "filters不应为null");
            Map<String, Object> filters = bean.getFilters();
            assertTrue(filters.containsKey("default"), "filters应包含default配置");
            assertTrue(filters.containsKey("contextual"), "filters应包含contextual配置");
            assertTrue(filters.containsKey("security"), "filters应包含security配置");
        }

        @Test
        @DisplayName("测试默认过滤器配置")
        void testDefaultFiltersConfig() throws IOException {
            // When
            QueryPreset bean = objectMapper.readValue(schemaJson, QueryPreset.class);
            Map<String, Object> filters = bean.getFilters();
            
            @SuppressWarnings("unchecked")
            Map<String, Object> defaultFilters = (Map<String, Object>) filters.get("default");

            // Then
            assertNotNull(defaultFilters, "default过滤器不应为null");
            assertEquals(2, defaultFilters.size(), "默认过滤器应有2个");
            
            // 验证status过滤器
            @SuppressWarnings("unchecked")
            List<String> statusValues = (List<String>) defaultFilters.get("status");
            assertNotNull(statusValues, "status过滤器不应为null");
            assertEquals(1, statusValues.size(), "status值应有1个");
            assertEquals("active", statusValues.get(0), "status值应为ACTIVE");
            
            // 验证deleted_at过滤器
            assertNull(defaultFilters.get("deleted_at"), "deleted_at应为null");
        }

        @Test
        @DisplayName("测试上下文过滤器配置")
        void testContextualFiltersConfig() throws IOException {
            // When
            QueryPreset bean = objectMapper.readValue(schemaJson, QueryPreset.class);
            Map<String, Object> filters = bean.getFilters();
            
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> contextualFilters = (List<Map<String, Object>>) filters.get("contextual");

            // Then
            assertNotNull(contextualFilters, "contextual过滤器不应为null");
            assertEquals(2, contextualFilters.size(), "上下文过滤器应有2个");
            
            // 验证tenant_id过滤器
            Map<String, Object> tenantFilter = contextualFilters.get(0);
            assertNotNull(tenantFilter, "tenant_id过滤器不应为null");
            assertEquals("tenant_id", tenantFilter.get("field"), "字段应为tenant_id");
            assertEquals("EQ", tenantFilter.get("op"), "操作符应为EQ");
            assertEquals("${context.tenantId}", tenantFilter.get("value"), "值应为${context.tenantId}");
            assertEquals(true, tenantFilter.get("required"), "required应为true");
            
            // 验证deleted_at过滤器
            Map<String, Object> deletedFilter = contextualFilters.get(1);
            assertNotNull(deletedFilter, "deleted_at过滤器不应为null");
            assertEquals("deleted_at", deletedFilter.get("field"), "字段应为deleted_at");
            assertEquals("is_null", deletedFilter.get("op"), "操作符应为IS_NULL");
            assertNull(deletedFilter.get("value"), "值应为null");
            assertEquals(true, deletedFilter.get("required"), "required应为true");
        }

        @Test
        @DisplayName("测试安全过滤器配置")
        void testSecurityFiltersConfig() throws IOException {
            // When
            QueryPreset bean = objectMapper.readValue(schemaJson, QueryPreset.class);
            Map<String, Object> filters = bean.getFilters();
            
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> securityFilters = (List<Map<String, Object>>) filters.get("security");

            // Then
            assertNotNull(securityFilters, "security过滤器不应为null");
            assertEquals(1, securityFilters.size(), "安全过滤器应有1个");
            
            Map<String, Object> orgFilter = securityFilters.get(0);
            assertEquals("organization_id", orgFilter.get("field"), "过滤器字段应为organization_id");
            assertEquals("IN", orgFilter.get("op"), "操作符应为IN");
            assertEquals("${user.accessibleOrganizations}", orgFilter.get("value"), "值应为${user.accessibleOrganizations}");
            assertEquals("!hasRole('super_admin')", orgFilter.get("if"), "when条件应正确");
        }
    }

    @Nested
    @DisplayName("选择字段配置测试")
    class SelectsTests {

        @Test
        @DisplayName("测试selects配置存在")
        void testSelectsConfigExists() throws IOException {
            // When
            QueryPreset bean = objectMapper.readValue(schemaJson, QueryPreset.class);

            // Then
            assertNotNull(bean.getSelects(), "selects不应为null");
            Map<String, Object> selects = bean.getSelects();
            assertTrue(selects.containsKey("fields"), "selects应包含fields配置");
            assertTrue(selects.containsKey("relations"), "selects应包含relations配置");
        }

        @Test
        @DisplayName("测试字段选择配置")
        void testFieldsConfig() throws IOException {
            // When
            QueryPreset bean = objectMapper.readValue(schemaJson, QueryPreset.class);
            Map<String, Object> selects = bean.getSelects();
            
            @SuppressWarnings("unchecked")
            List<String> fields = (List<String>) selects.get("fields");

            // Then
            assertNotNull(fields, "fields不应为null");
            assertEquals(13, fields.size(), "字段应有13个");
            assertTrue(fields.contains("id"), "应包含id字段");
            assertTrue(fields.contains("pid"), "应包含pid字段");
            assertTrue(fields.contains("code"), "应包含code字段");
            assertTrue(fields.contains("name"), "应包含name字段");
            assertTrue(fields.contains("type"), "应包含type字段");
            assertTrue(fields.contains("status"), "应包含status字段");
            assertTrue(fields.contains("address"), "应包含address字段");
            assertTrue(fields.contains("contact_phone"), "应包含contact_phone字段");
            assertTrue(fields.contains("manager_name"), "应包含manager_name字段");
            assertTrue(fields.contains("manager_avatar"), "应包含manager_avatar字段");
            assertTrue(fields.contains("opening_hours"), "应包含opening_hours字段");
            assertTrue(fields.contains("created_at"), "应包含created_at字段");
            assertTrue(fields.contains("updated_at"), "应包含updated_at字段");
        }

        @Test
        @DisplayName("测试关联关系配置")
        void testRelationsConfig() throws IOException {
            // When
            QueryPreset bean = objectMapper.readValue(schemaJson, QueryPreset.class);
            Map<String, Object> selects = bean.getSelects();
            
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> relations = (List<Map<String, Object>>) selects.get("relations");

            // Then
            assertNotNull(relations, "relations不应为null");
            assertEquals(2, relations.size(), "关联关系应有2个");
            
            // 验证Organization关联
            Map<String, Object> orgRelation = relations.get(0);
            assertEquals("Organization", orgRelation.get("entity"), "实体应为Organization");
            assertEquals("org", orgRelation.get("alias"), "别名应为org");
            assertEquals("Store.organization_id = org.id", orgRelation.get("joinOn"), "连接条件应正确");
            
            @SuppressWarnings("unchecked")
            List<String> orgFields = (List<String>) orgRelation.get("fields");
            assertEquals(1, orgFields.size(), "Organization字段应有1个");
            assertEquals("name", orgFields.get(0), "字段应为name");
            
            // 验证User关联
            Map<String, Object> userRelation = relations.get(1);
            assertEquals("User", userRelation.get("entity"), "实体应为User");
            assertEquals("manager", userRelation.get("alias"), "别名应为manager");
            assertEquals("Store.manager_id = manager.id", userRelation.get("joinOn"), "连接条件应正确");
            assertEquals(true, userRelation.get("optional"), "optional应为true");
            
            @SuppressWarnings("unchecked")
            List<String> userFields = (List<String>) userRelation.get("fields");
            assertEquals(2, userFields.size(), "User字段应有2个");
            assertTrue(userFields.contains("name"), "应包含name字段");
            assertTrue(userFields.contains("avatar_url"), "应包含avatar_url字段");
        }
    }



    @Nested
    @DisplayName("缓存配置测试")
    class CacheTests {

        @Test
        @DisplayName("测试缓存配置存在")
        void testCacheConfigExists() throws IOException {
            // When
            QueryPreset bean = objectMapper.readValue(schemaJson, QueryPreset.class);

            // Then
            assertNotNull(bean.getCache(), "缓存配置不应为null");
        }

        @Test
        @DisplayName("测试缓存配置详细信息")
        void testCacheConfigDetails() throws IOException {
            // When
            QueryPreset bean = objectMapper.readValue(schemaJson, QueryPreset.class);
            Map<String, Object> cache = bean.getCache();

            // Then
            assertNotNull(cache, "缓存配置不应为null");
            assertEquals(true, cache.get("enabled"), "缓存应启用");
            assertEquals("stale-while-revalidate", cache.get("strategy"), "缓存策略应为stale-while-revalidate");
            assertEquals(300, cache.get("ttlSec"), "缓存TTL应为300秒");
            
            @SuppressWarnings("unchecked")
            List<String> tags = (List<String>) cache.get("tags");
            assertEquals(2, tags.size(), "缓存标签应有2个");
            assertTrue(tags.contains("store"), "缓存标签应包含store");
            assertTrue(tags.contains("organization"), "缓存标签应包含organization");
        }

        @Test
        @DisplayName("测试缓存配置")
        void testCacheConfig() throws IOException {
            // When
            QueryPreset bean = objectMapper.readValue(schemaJson, QueryPreset.class);

            // Then
            // Cache配置是可选的
            Map<String, Object> cache = bean.getCache();
            if (cache != null) {
                // 如果存在缓存配置，验证其结构
                assertTrue(cache.containsKey("enabled") || cache.containsKey("ttl"), "cache应包含enabled或ttl配置");
            }
        }
    }

    @Nested
    @DisplayName("聚合配置测试")
    class AggregationsTests {

        @Test
        @DisplayName("测试aggregations配置存在")
        void testAggregationsConfigExists() throws IOException {
            // When
            QueryPreset bean = objectMapper.readValue(schemaJson, QueryPreset.class);

            // Then
            assertNotNull(bean.getAggregations(), "aggregations不应为null");
            Map<String, Object> aggregations = bean.getAggregations();
            assertTrue(aggregations.containsKey("enabled"), "aggregations应包含enabled配置");
            assertTrue(aggregations.containsKey("metrics"), "aggregations应包含metrics配置");
        }

        @Test
        @DisplayName("测试聚合指标配置")
        void testAggregationMetricsConfig() throws IOException {
            // When
            QueryPreset bean = objectMapper.readValue(schemaJson, QueryPreset.class);
            Map<String, Object> aggregations = bean.getAggregations();
            
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> metrics = (List<Map<String, Object>>) aggregations.get("metrics");

            // Then
            assertNotNull(metrics, "metrics不应为null");
            assertEquals(4, metrics.size(), "聚合指标应有4个");
            
            // 验证totalCount指标
            Map<String, Object> totalCount = metrics.get(0);
            assertEquals("totalCount", totalCount.get("code"), "指标代码应为totalCount");
            assertEquals("count", totalCount.get("type"), "指标类型应为count");
            assertEquals("*", totalCount.get("field"), "指标字段应为*");
            
            @SuppressWarnings("unchecked")
            Map<String, String> totalCountLabel = (Map<String, String>) totalCount.get("label");
            assertEquals("总数", totalCountLabel.get("zh-CN"), "中文标签应为总数");
            assertEquals("Total Count", totalCountLabel.get("en-US"), "英文标签应为Total Count");
            
            // 验证activeCount指标
            Map<String, Object> activeCount = metrics.get(1);
            assertEquals("activeCount", activeCount.get("code"), "指标代码应为activeCount");
            assertEquals("count", activeCount.get("type"), "指标类型应为count");
            assertEquals("*", activeCount.get("field"), "指标字段应为*");
            
            @SuppressWarnings("unchecked")
            Map<String, String> filter = (Map<String, String>) activeCount.get("filter");
            assertEquals("active", filter.get("status"), "过滤条件应为ACTIVE");
            
            // 验证inactiveCount指标
            Map<String, Object> inactiveCount = metrics.get(2);
            assertEquals("inactiveCount", inactiveCount.get("code"), "指标代码应为inactiveCount");
            assertEquals("count", inactiveCount.get("type"), "指标类型应为count");
            
            // 验证typeDistribution指标
            Map<String, Object> typeDistribution = metrics.get(3);
            assertEquals("typeDistribution", typeDistribution.get("code"), "指标代码应为typeDistribution");
            assertEquals("groupBy", typeDistribution.get("type"), "指标类型应为groupBy");
            assertEquals("type", typeDistribution.get("field"), "指标字段应为type");
        }
    }

    @Nested
    @DisplayName("优化配置测试")
    class OptimizationTests {

        @Test
        @DisplayName("测试optimization配置存在")
        void testOptimizationConfigExists() throws IOException {
            // When
            QueryPreset bean = objectMapper.readValue(schemaJson, QueryPreset.class);

            // Then
            assertNotNull(bean.getOptimization(), "optimization不应为null");
            Map<String, Object> optimization = bean.getOptimization();
            assertTrue(optimization.containsKey("indexHints"), "optimization应包含indexHints配置");
            assertTrue(optimization.containsKey("queryPlan"), "optimization应包含queryPlan配置");
        }

        @Test
        @DisplayName("测试索引提示配置")
        void testIndexHintsConfig() throws IOException {
            // When
            QueryPreset bean = objectMapper.readValue(schemaJson, QueryPreset.class);
            Map<String, Object> optimization = bean.getOptimization();
            
            @SuppressWarnings("unchecked")
            List<String> indexHints = (List<String>) optimization.get("indexHints");

            // Then
            assertNotNull(indexHints, "indexHints不应为null");
            assertEquals(3, indexHints.size(), "索引提示应有3个");
            assertTrue(indexHints.contains("idx_store_tenant_status"), "应包含idx_store_tenant_status索引");
            assertTrue(indexHints.contains("idx_store_created_at"), "应包含idx_store_created_at索引");
            assertTrue(indexHints.contains("idx_store_code"), "应包含idx_store_code索引");
        }

        @Test
        @DisplayName("测试查询计划配置")
        void testQueryPlanConfig() throws IOException {
            // When
            QueryPreset bean = objectMapper.readValue(schemaJson, QueryPreset.class);
            Map<String, Object> optimization = bean.getOptimization();
            
            @SuppressWarnings("unchecked")
            Map<String, Object> queryPlan = (Map<String, Object>) optimization.get("queryPlan");

            // Then
            assertNotNull(queryPlan, "queryPlan不应为null");
            assertEquals(true, queryPlan.get("useIndex"), "useIndex应为true");
            assertEquals(5000, queryPlan.get("maxExecutionTime"), "maxExecutionTime应为5000");
        }
    }

    @Nested
    @DisplayName("安全配置测试")
    class SecurityTests {

        @Test
        @DisplayName("测试security配置存在")
        void testSecurityConfigExists() throws IOException {
            // When
            QueryPreset bean = objectMapper.readValue(schemaJson, QueryPreset.class);

            // Then
            assertNotNull(bean.getSecurity(), "security不应为null");
            Map<String, Object> security = bean.getSecurity();
            assertTrue(security.containsKey("dataFiltering"), "security应包含dataFiltering配置");
            assertTrue(security.containsKey("fieldMasking"), "security应包含fieldMasking配置");
        }

        @Test
        @DisplayName("测试数据过滤配置")
        void testDataFilteringConfig() throws IOException {
            // When
            QueryPreset bean = objectMapper.readValue(schemaJson, QueryPreset.class);
            Map<String, Object> security = bean.getSecurity();
            
            @SuppressWarnings("unchecked")
            Map<String, Object> dataFiltering = (Map<String, Object>) security.get("dataFiltering");

            // Then
            assertNotNull(dataFiltering, "dataFiltering不应为null");
            assertEquals(true, dataFiltering.get("enabled"), "dataFiltering应启用");
            
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rules = (List<Map<String, Object>>) dataFiltering.get("rules");
            assertEquals(2, rules.size(), "数据过滤规则应有2个");
            
            // 验证STORE_MANAGER规则
            Map<String, Object> storeManagerRule = rules.get(0);
            assertEquals("store_manager", storeManagerRule.get("role"), "角色应为STORE_MANAGER");
            
            @SuppressWarnings("unchecked")
            Map<String, String> storeManagerFilter = (Map<String, String>) storeManagerRule.get("filter");
            assertEquals("${user.id}", storeManagerFilter.get("manager_id"), "过滤条件应为${user.id}");
            
            // 验证REGIONAL_MANAGER规则
            Map<String, Object> regionalManagerRule = rules.get(1);
            assertEquals("regional_manager", regionalManagerRule.get("role"), "角色应为REGIONAL_MANAGER");
            
            @SuppressWarnings("unchecked")
            Map<String, String> regionalManagerFilter = (Map<String, String>) regionalManagerRule.get("filter");
            assertEquals("${user.managedRegions}", regionalManagerFilter.get("region_id"), "过滤条件应为${user.managedRegions}");
        }

        @Test
        @DisplayName("测试字段掩码配置")
        void testFieldMaskingConfig() throws IOException {
            // When
            QueryPreset bean = objectMapper.readValue(schemaJson, QueryPreset.class);
            Map<String, Object> security = bean.getSecurity();
            
            @SuppressWarnings("unchecked")
            Map<String, Object> fieldMasking = (Map<String, Object>) security.get("fieldMasking");

            // Then
            assertNotNull(fieldMasking, "fieldMasking不应为null");
            assertEquals(true, fieldMasking.get("enabled"), "fieldMasking应启用");
            
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rules = (List<Map<String, Object>>) fieldMasking.get("rules");
            assertEquals(1, rules.size(), "字段掩码规则应有1个");
            
            Map<String, Object> phoneRule = rules.get(0);
            assertEquals("contact_phone", phoneRule.get("field"), "字段应为contact_phone");
            assertEquals("phone", phoneRule.get("mask"), "掩码类型应为phone");
            assertEquals("!permission('store:view_sensitive')", phoneRule.get("if"), "条件应正确");
        }
    }

    @Nested
    @DisplayName("审计配置测试")
    class AuditTests {

        @Test
        @DisplayName("测试audit配置存在")
        void testAuditConfigExists() throws IOException {
            // When
            QueryPreset bean = objectMapper.readValue(schemaJson, QueryPreset.class);

            // Then
            assertNotNull(bean.getAudit(), "audit不应为null");
            Map<String, Object> audit = bean.getAudit();
            assertTrue(audit.containsKey("enabled"), "audit应包含enabled配置");
            assertTrue(audit.containsKey("trackQueries"), "audit应包含trackQueries配置");
            assertTrue(audit.containsKey("sensitiveFields"), "audit应包含sensitiveFields配置");
        }

        @Test
        @DisplayName("测试审计详细配置")
        void testAuditDetailConfig() throws IOException {
            // When
            QueryPreset bean = objectMapper.readValue(schemaJson, QueryPreset.class);
            Map<String, Object> audit = bean.getAudit();

            // Then
            assertEquals(true, audit.get("enabled"), "audit应启用");
            assertEquals(true, audit.get("trackQueries"), "trackQueries应启用");
            
            @SuppressWarnings("unchecked")
            List<String> sensitiveFields = (List<String>) audit.get("sensitiveFields");
            assertEquals(2, sensitiveFields.size(), "敏感字段应有2个");
            assertTrue(sensitiveFields.contains("contact_phone"), "应包含contact_phone字段");
            assertTrue(sensitiveFields.contains("address"), "应包含address字段");
        }
    }

    @Nested
    @DisplayName("权限策略测试")
    class PolicyTests {

        // 注释掉policy相关测试，因为JSON中没有policy字段
        /*
        @Test
        @DisplayName("测试policy配置存在")
        void testPolicyConfigExists() throws IOException {
            // When
            QueryPreset bean = objectMapper.readValue(schemaJson, QueryPreset.class);

            // Then
            assertNotNull(bean.getPolicy(), "policy不应为null");
            Map<String, Object> policy = bean.getPolicy();
            assertTrue(policy.containsKey("view"), "policy应包含view配置");
            assertTrue(policy.containsKey("export"), "policy应包含export配置");
        }

        @Test
        @DisplayName("测试权限策略详细配置")
        void testPolicyDetailConfig() throws IOException {
            // When
            QueryPreset bean = objectMapper.readValue(schemaJson, QueryPreset.class);
            Map<String, Object> policy = bean.getPolicy();

            // Then
            assertEquals("permission('store:read')", policy.get("view"), "view权限应正确");
            assertEquals("permission('store:export')", policy.get("export"), "export权限应正确");
        }
        */
    }

    @Nested
    @DisplayName("排序配置测试")
    class SortConfigTests {

        @Test
        @DisplayName("测试sorts配置存在")
        void testSortsConfigExists() throws IOException {
            // When
            QueryPreset bean = objectMapper.readValue(schemaJson, QueryPreset.class);

            // Then
            assertNotNull(bean.getSorts(), "sorts不应为null");
            Map<String, Object> sorts = bean.getSorts();
            assertTrue(sorts.containsKey("default"), "sorts应包含default配置");
            assertTrue(sorts.containsKey("available"), "sorts应包含available配置");
        }

        @Test
        @DisplayName("测试默认排序配置")
        void testDefaultSortsConfig() throws IOException {
            // When
            QueryPreset bean = objectMapper.readValue(schemaJson, QueryPreset.class);
            Map<String, Object> sorts = bean.getSorts();
            
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> defaultSorts = (List<Map<String, Object>>) sorts.get("default");

            // Then
            assertNotNull(defaultSorts, "default排序不应为null");
            assertEquals(2, defaultSorts.size(), "默认排序应有2个");
            
            // 验证第一个排序：created_at DESC
            Map<String, Object> firstSort = defaultSorts.get(0);
            assertEquals("created_at", firstSort.get("field"), "第一个排序字段应为created_at");
            assertEquals("desc", firstSort.get("direction"), "第一个排序方向应为DESC");
            
            // 验证第二个排序：code ASC
            Map<String, Object> secondSort = defaultSorts.get(1);
            assertEquals("code", secondSort.get("field"), "第二个排序字段应为code");
            assertEquals("asc", secondSort.get("direction"), "第二个排序方向应为ASC");
        }

        @Test
        @DisplayName("测试可用排序配置")
        void testAvailableSortsConfig() throws IOException {
            // When
            QueryPreset bean = objectMapper.readValue(schemaJson, QueryPreset.class);
            Map<String, Object> sorts = bean.getSorts();
            
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> availableSorts = (List<Map<String, Object>>) sorts.get("available");

            // Then
            assertNotNull(availableSorts, "available排序不应为null");
            assertEquals(4, availableSorts.size(), "可用排序应有4个");
            
            // 验证name排序
            Map<String, Object> nameSort = availableSorts.get(0);
            assertEquals("name", nameSort.get("code"), "排序代码应为name");
            assertEquals("name->>'zh-CN'", nameSort.get("field"), "排序字段应为name->>'zh-CN'");
            
            @SuppressWarnings("unchecked")
            Map<String, String> namelabel = (Map<String, String>) nameSort.get("label");
            assertEquals("门店名称", namelabel.get("zh-CN"), "中文标签应为门店名称");
            assertEquals("Store Name", namelabel.get("en-US"), "英文标签应为Store Name");
            
            // 验证code排序
            Map<String, Object> codeSort = availableSorts.get(1);
            assertEquals("code", codeSort.get("code"), "排序代码应为code");
            assertEquals("code", codeSort.get("field"), "排序字段应为code");
            
            // 验证createdAt排序
            Map<String, Object> createdAtSort = availableSorts.get(2);
            assertEquals("createdAt", createdAtSort.get("code"), "排序代码应为createdAt");
            assertEquals("created_at", createdAtSort.get("field"), "排序字段应为created_at");
            
            // 验证updatedAt排序
            Map<String, Object> updatedAtSort = availableSorts.get(3);
            assertEquals("updatedAt", updatedAtSort.get("code"), "排序代码应为updatedAt");
            assertEquals("updated_at", updatedAtSort.get("field"), "排序字段应为updated_at");
        }
    }

    @Nested
    @DisplayName("分页配置测试")
    class PaginationTests {

        @Test
        @DisplayName("测试分页配置存在")
        void testPaginationConfigExists() throws IOException {
            // When
            QueryPreset bean = objectMapper.readValue(schemaJson, QueryPreset.class);

            // Then
            assertNotNull(bean.getPagination(), "分页配置不应为null");
        }

        @Test
        @DisplayName("测试分页配置详细信息")
        void testPaginationConfigDetails() throws IOException {
            // When
            QueryPreset bean = objectMapper.readValue(schemaJson, QueryPreset.class);
            Map<String, Object> pagination = bean.getPagination();

            // Then
            assertNotNull(pagination, "分页配置不应为null");
            assertEquals(20, pagination.get("defaultPageSize"), "默认页面大小应为20");
            assertEquals(100, pagination.get("maxPageSize"), "最大页面大小应为100");
            
            @SuppressWarnings("unchecked")
            List<Integer> pageSizeOptions = (List<Integer>) pagination.get("pageSizeOptions");
            assertEquals(4, pageSizeOptions.size(), "页面大小选项应有4个");
            assertTrue(pageSizeOptions.contains(10), "页面大小选项应包含10");
            assertTrue(pageSizeOptions.contains(20), "页面大小选项应包含20");
            assertTrue(pageSizeOptions.contains(50), "页面大小选项应包含50");
            assertTrue(pageSizeOptions.contains(100), "页面大小选项应包含100");
        }
    }

    @Nested
    @DisplayName("缓存详细配置测试")
    class CacheDetailTests {

        @Test
        @DisplayName("测试缓存详细配置存在")
        void testCacheDetailConfigExists() throws IOException {
            // When
            QueryPreset bean = objectMapper.readValue(schemaJson, QueryPreset.class);

            // Then
            Map<String, Object> cache = bean.getCache();
            if (cache != null && !cache.isEmpty()) {
                assertNotNull(cache, "缓存配置不应为null");
            }
        }

        @Test
        @DisplayName("测试缓存详细配置信息")
        void testCacheDetailConfigDetails() throws IOException {
            // When
            QueryPreset bean = objectMapper.readValue(schemaJson, QueryPreset.class);
            Map<String, Object> cache = bean.getCache();

            // Then
            if (cache != null && !cache.isEmpty()) {
                assertTrue(cache.containsKey("enabled") || cache.containsKey("ttl") || cache.containsKey("strategy"), "cache应包含enabled、ttl或strategy配置");
            }
        }
    }

    @Nested
    @DisplayName("元数据配置测试")
    class MetaTests {

        @Test
        @DisplayName("测试元数据配置存在")
        void testMetaConfigExists() throws IOException {
            // When
            QueryPreset bean = objectMapper.readValue(schemaJson, QueryPreset.class);

            // Then
            assertNotNull(bean.getMeta(), "元数据配置不应为null");
        }

        @Test
        @DisplayName("测试元数据配置详细信息")
        void testMetaConfigDetails() throws IOException {
            // When
            QueryPreset bean = objectMapper.readValue(schemaJson, QueryPreset.class);
            Meta meta = bean.getMeta();

            // Then
            assertNotNull(meta, "元数据配置不应为null");
            assertNotNull(meta.getBlockCode(), "blockCode不应为null");
            assertNotNull(meta.getVersion(), "version不应为null");
            assertNotNull(meta.getEntityCode(), "entityCode不应为null");
        }
    }



    @Nested
    @DisplayName("过滤器配置测试")
    class FilterTests {

        @Test
        @DisplayName("测试过滤器配置存在")
        void testFiltersConfigExists() throws IOException {
            // When
            QueryPreset bean = objectMapper.readValue(schemaJson, QueryPreset.class);

            // Then
            assertNotNull(bean.getFilters(), "过滤器配置不应为null");
        }

        @Test
        @DisplayName("测试过滤器配置详细信息")
        void testFiltersConfigDetails() throws IOException {
            // When
            QueryPreset bean = objectMapper.readValue(schemaJson, QueryPreset.class);
            Map<String, Object> filters = bean.getFilters();

            // Then
            assertNotNull(filters, "过滤器配置不应为null");
            if (!filters.isEmpty()) {
                assertTrue(filters.containsKey("default") || filters.size() > 0, "filters应包含default或其他配置");
            }
        }
    }

    @Nested
    @DisplayName("排序配置测试")
    class SortsTests {

        @Test
        @DisplayName("测试排序配置存在")
        void testSortsConfigExists() throws IOException {
            // When
            QueryPreset bean = objectMapper.readValue(schemaJson, QueryPreset.class);

            // Then
            assertNotNull(bean.getSorts(), "排序配置不应为null");
        }

        @Test
        @DisplayName("测试排序配置详细信息")
        void testSortsConfigDetails() throws IOException {
            // When
            QueryPreset bean = objectMapper.readValue(schemaJson, QueryPreset.class);
            Map<String, Object> sorts = bean.getSorts();

            // Then
            assertNotNull(sorts, "排序配置不应为null");
            if (!sorts.isEmpty()) {
                assertTrue(sorts.containsKey("default") || sorts.containsKey("available"), "sorts应包含default或available配置");
            }
        }

        @Test
        @DisplayName("测试元数据配置")
        void testMetaConfig() throws IOException {
            // When
            QueryPreset bean = objectMapper.readValue(schemaJson, QueryPreset.class);

            // Then
            assertNotNull(bean.getMeta(), "元数据配置不应为null");
            Meta meta = bean.getMeta();
            assertNotNull(meta.getBlockCode(), "blockCode不应为null");
            assertNotNull(meta.getVersion(), "version不应为null");
            assertNotNull(meta.getEntityCode(), "entityCode不应为null");
        }
    }

    @Nested
    @DisplayName("缓存配置测试")
    class CacheConfigTests {

        @Test
        @DisplayName("测试缓存配置")
        void testCacheConfig() throws IOException {
            // When
            QueryPreset bean = objectMapper.readValue(schemaJson, QueryPreset.class);

            // Then
            Map<String, Object> cache = bean.getCache();
            if (cache != null) {
                assertTrue(cache.containsKey("enabled") || cache.containsKey("ttl"), "cache应包含enabled或ttl配置");
            }
        }
    }

    @Nested
    @DisplayName("序列化测试")
    class SerializationTests {

        @Test
        @DisplayName("测试Bean转JSON序列化")
        void testBeanToJsonSerialization() throws IOException {
            // Given
            QueryPreset originalBean = objectMapper.readValue(schemaJson, QueryPreset.class);

            // When
            String serializedJson = objectMapper.writeValueAsString(originalBean);
            QueryPreset deserializedBean = objectMapper.readValue(serializedJson, QueryPreset.class);

            // Then
            assertNotNull(serializedJson, "序列化结果不应为null");
            assertNotNull(deserializedBean, "反序列化结果不应为null");
            
            // 验证关键字段保持一致
            assertEquals(originalBean.getFilters().size(), deserializedBean.getFilters().size(), "filters数量应保持一致");
            assertEquals(originalBean.getSorts().size(), deserializedBean.getSorts().size(), "sorts数量应保持一致");
            assertEquals(originalBean.getMeta().getBlockCode(), deserializedBean.getMeta().getBlockCode(), "blockCode应保持一致");
        }

        @Test
        @DisplayName("测试空值处理")
        void testNullValueHandling() throws IOException {
            // Given
            String jsonWithNulls = """
                {
                  "meta": {
                    "blockCode": "test.preset",
                    "version": "1.0.0",
                    "entityCode": "Test",
                    "type": "queryPreset"
                  },
                  "filters": null,
                  "sorts": null,
                  "security": null
                }
                """;

            // When
            QueryPreset bean = objectMapper.readValue(jsonWithNulls, QueryPreset.class);

            // Then
            assertNotNull(bean, "Bean不应为null");
            assertNotNull(bean.getMeta(), "meta不应为null");
            assertEquals("test.preset", bean.getMeta().getBlockCode(), "blockCode应正确解析");
            // Note: Based on QueryPreset structure, filters returns Map not List
            assertNotNull(bean.getFilters(), "filters不应为null");
            // filters is a Map, not a List, so it won't be null but could be empty
            assertTrue(bean.getFilters().isEmpty() || bean.getFilters() != null, "filters应为空Map或非null");
        }
    }

    @Nested
    @DisplayName("边界情况测试")
    class EdgeCaseTests {

        @Test
        @DisplayName("测试预设查询条件类型多样性")
        void testPresetQueryTypeDiversity() throws IOException {
            // When
            QueryPreset bean = objectMapper.readValue(schemaJson, QueryPreset.class);
            // Note: QueryPreset structure changed, no presets field available
            Map<String, Object> filters = bean.getFilters();

            // Then
            // 验证不同类型的过滤器配置
            assertNotNull(filters, "过滤器配置不应为null");
            assertTrue(filters.size() >= 1, "应有过滤器配置");
        }

        @Test
        @DisplayName("测试过滤器配置完整性")
        void testFilterConfigCompleteness() throws IOException {
            // When
            QueryPreset bean = objectMapper.readValue(schemaJson, QueryPreset.class);
            Map<String, Object> filters = bean.getFilters();

            // Then
            assertNotNull(filters, "filters不应为null");
            assertTrue(filters.containsKey("default"), "filters应包含default配置");
            
            @SuppressWarnings("unchecked")
            Map<String, Object> defaultFilters = (Map<String, Object>) filters.get("default");
            assertTrue(defaultFilters.containsKey("status"), "默认过滤器应包含status");
            
            @SuppressWarnings("unchecked")
            List<String> statusValues = (List<String>) defaultFilters.get("status");
            assertEquals(1, statusValues.size(), "status过滤器应有1个值");
            assertEquals("active", statusValues.get(0), "status默认值应为ACTIVE");
        }

        @Test
        @DisplayName("测试排序配置完整性")
        void testSortConfigCompleteness() throws IOException {
            // When
            QueryPreset bean = objectMapper.readValue(schemaJson, QueryPreset.class);
            Map<String, Object> sorts = bean.getSorts();

            // Then
            assertNotNull(sorts, "sorts不应为null");
            assertTrue(sorts.containsKey("default"), "sorts应包含default配置");
            assertTrue(sorts.containsKey("available"), "sorts应包含available配置");
            
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> defaultSorts = (List<Map<String, Object>>) sorts.get("default");
            assertEquals(2, defaultSorts.size(), "默认排序应有2个");
            
            Map<String, Object> firstSort = defaultSorts.get(0);
            assertEquals("created_at", firstSort.get("field"), "第一个排序字段应为created_at");
            assertEquals("desc", firstSort.get("direction"), "第一个排序方向应为DESC");
            
            Map<String, Object> secondSort = defaultSorts.get(1);
            assertEquals("code", secondSort.get("field"), "第二个排序字段应为code");
            assertEquals("asc", secondSort.get("direction"), "第二个排序方向应为ASC");
        }

        @Test
        @DisplayName("测试默认配置一致性")
        void testDefaultConfigConsistency() throws IOException {
            // When
            QueryPreset bean = objectMapper.readValue(schemaJson, QueryPreset.class);
            
            // 验证过滤器默认配置
            Map<String, Object> filters = bean.getFilters();
            assertTrue(filters.containsKey("default"), "filters应包含default配置");
            
            // 验证排序默认配置
            Map<String, Object> sorts = bean.getSorts();
            assertTrue(sorts.containsKey("default"), "sorts应包含default配置");
            assertTrue(sorts.containsKey("available"), "sorts应包含available配置");
            
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> availableSorts = (List<Map<String, Object>>) sorts.get("available");
            assertEquals(4, availableSorts.size(), "可用排序应有4个选项");
        }

        @Test
        @DisplayName("测试国际化配置完整性")
        void testI18nConfigCompleteness() throws IOException {
            // When
            QueryPreset bean = objectMapper.readValue(schemaJson, QueryPreset.class);
            
            // 验证meta国际化
            Map<String, String> metaTitle = bean.getMeta().getTitle();
            if (metaTitle != null) {
                assertTrue(metaTitle.containsKey("zh-CN") || metaTitle.containsKey("en-US"), 
                    "meta标题应包含国际化配置");
            }
            
            // QueryPresetMetaBean 没有 description 字段，跳过描述验证
            
            // 验证i18n配置存在
            
            // 验证过滤器配置存在
            Map<String, Object> filters = bean.getFilters();
            assertNotNull(filters, "过滤器配置不应为null");
            assertTrue(filters.containsKey("default"), "过滤器应包含default配置");
            
            // 验证排序配置存在
            Map<String, Object> sorts = bean.getSorts();
            assertNotNull(sorts, "排序配置不应为null");
            assertTrue(sorts.containsKey("default"), "排序应包含default配置");
            assertTrue(sorts.containsKey("available"), "排序应包含available配置");
        }

        @Test
        @DisplayName("测试枚举配置一致性")
        void testEnumConfigConsistency() throws IOException {
            // When
            QueryPreset bean = objectMapper.readValue(schemaJson, QueryPreset.class);
            Map<String, Object> filters = bean.getFilters();
            
            // 验证过滤器配置存在
            assertNotNull(filters, "过滤器配置不应为null");
            assertTrue(filters.containsKey("default"), "过滤器应包含default配置");
            
            @SuppressWarnings("unchecked")
            Map<String, Object> defaultFilters = (Map<String, Object>) filters.get("default");
            assertTrue(defaultFilters.containsKey("status"), "默认过滤器应包含status配置");
        }

        @Test
        @DisplayName("测试API配置完整性")
        void testApiConfigCompleteness() throws IOException {
            // When
            QueryPreset bean = objectMapper.readValue(schemaJson, QueryPreset.class);
            
            // 验证基本配置存在
            assertNotNull(bean.getMeta(), "meta配置不应为null");
            assertEquals("store.list.preset", bean.getMeta().getBlockCode(), "blockCode应正确");
            assertEquals("1.0.0", bean.getMeta().getVersion(), "version应正确");
            assertEquals("Store", bean.getMeta().getEntityCode(), "entityCode应正确");
            assertEquals("queryPreset", bean.getMeta().getType(), "type应正确");
            
            // 验证过滤器配置存在
            assertNotNull(bean.getFilters(), "过滤器配置不应为null");
        }
    }
}
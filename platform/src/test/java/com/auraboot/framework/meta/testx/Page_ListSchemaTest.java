package com.auraboot.framework.meta.testx;

import com.auraboot.framework.meta.view.schema.common.Endpoint;
import com.auraboot.framework.meta.view.schema.common.Meta;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;
import com.auraboot.framework.meta.view.schema.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 门店列表Schema JSON转换测试类
 * 测试 1st-version-store-list-facade.json 转换为 PageFacade 对象的功能
 */
@DisplayName("门店列表Schema JSON转换测试")
class Page_ListSchemaTest {

    private ObjectMapper objectMapper;
    private String schemaJson;

    @BeforeEach
    void setUp() throws IOException {
        objectMapper = new ObjectMapper();
        // 读取实际的schema文件
        schemaJson = Files.readString(Paths.get("src/main/resources/schemas/1st-version-store-list-facade.json"));
    }

    @Nested
    @DisplayName("基础转换测试")
    class BasicConversionTests {

        @Test
        @DisplayName("测试JSON转换为PageSchemaBean")
        void testJsonToPageSchemaBean() throws IOException {
            // When
            PageFacade bean = objectMapper.readValue(schemaJson, PageFacade.class);

            // Then
            assertNotNull(bean, "PageSchemaBean不应为null");
            assertNotNull(bean.getMeta(), "meta字段不应为null");
            assertNotNull(bean.getEndpoint(), "endpoint字段不应为null");
            assertNotNull(bean.getRegions(), "regions字段不应为null");
        }

        @Test
        @DisplayName("测试meta字段映射")
        void testMetaFieldMapping() throws IOException {
            // When
            PageFacade bean = objectMapper.readValue(schemaJson, PageFacade.class);
            Meta meta = bean.getMeta();

            // Then
            assertNotNull(meta, "meta不应为null");
            assertEquals("Store", meta.getEntityCode(), "entityCode应为Store");
            assertEquals("1.0.0", meta.getDslVersion(), "dslVersion应为1.0.0");
            assertEquals("2.0.0", meta.getVersion(), "version应为2.0.0");

            // 验证国际化标题
            assertNotNull(meta.getTitle(), "title不应为null");
            assertEquals("门店列表", meta.getTitle().get("zh-CN"), "中文标题应为门店列表");
            assertEquals("Store List", meta.getTitle().get("en-US"), "英文标题应为Store List");
        }

        @Test
        @DisplayName("测试endpoint字段映射")
        void testEndpointFieldMapping() throws IOException {
            // When
            PageFacade bean = objectMapper.readValue(schemaJson, PageFacade.class);
            Endpoint endpoint = bean.getEndpoint();

            // Then
            assertNotNull(endpoint, "endpoint不应为null");
            assertEquals("/api/dynamic/Store/list", endpoint.getUrl(), "url应为/api/dynamic/Store/list");
            assertEquals("get", endpoint.getMethod(), "方法应为GET");
            assertEquals("store:read", endpoint.getPermission(), "权限应为store:read");
            
            // 验证transform配置
            assertNotNull(endpoint.getTransform(), "transform不应为null");
            assertNotNull(endpoint.getTransform().get("request"), "request transform不应为null");
            assertNotNull(endpoint.getTransform().get("response"), "response transform不应为null");
        }
    }

    @Nested
    @DisplayName("区域配置测试")
    class RegionConfigTests {

        @Test
        @DisplayName("测试regions数量和基本结构")
        void testRegionsBasicStructure() throws IOException {
            // When
            PageFacade bean = objectMapper.readValue(schemaJson, PageFacade.class);

            // Then
            assertEquals(5, bean.getRegions().size(), "应有5个区域");

            // 验证第一个区域 - filters
            AbstractPageRegion filtersRegion = bean.getRegions().get(0);
            assertEquals("filters", filtersRegion.getType(), "第一个区域类型应为filters");

            // 验证第二个区域 - preset
            AbstractPageRegion presetRegion = bean.getRegions().get(1);
            assertEquals("preset", presetRegion.getType(), "第二个区域类型应为preset");

            // 验证第三个区域 - action
            AbstractPageRegion actionRegion = bean.getRegions().get(2);
            assertEquals("action", actionRegion.getType(), "第三个区域类型应为action");

            // 验证第四个区域 - table
            AbstractPageRegion tableRegion = bean.getRegions().get(3);
            assertEquals("table", tableRegion.getType(), "第四个区域类型应为table");

            // 验证第五个区域 - DrawerFormRegion
            AbstractPageRegion drawerRegion = bean.getRegions().get(4);
            assertEquals("form", drawerRegion.getType(), "第五个区域类型应为DrawerFormRegion");
        }

        @Test
        @DisplayName("测试filters区域详细配置")
        void testFiltersRegionDetails() throws IOException {
            // When
            PageFacade bean = objectMapper.readValue(schemaJson, PageFacade.class);
            AbstractPageRegion filtersRegion = bean.getRegions().get(0);

            // Then
            assertEquals("filters", filtersRegion.getType(), "区域类型应为filters");
            
            // 验证ref配置
            assertNotNull(filtersRegion.getRef(), "filters区域应有ref配置");
            assertEquals("store.list.filter@^1", 
                filtersRegion.getRef().get("blockCode"), 
                "ref blockCode应为store.list.filter@^1");
        }

        @Test
        @DisplayName("测试preset区域详细配置")
        void testPresetRegionDetails() throws IOException {
            // When
            PageFacade bean = objectMapper.readValue(schemaJson, PageFacade.class);
            AbstractPageRegion presetRegion = bean.getRegions().get(1);

            // Then
            assertEquals("preset", presetRegion.getType(), "区域类型应为preset");
            
            // 验证ref配置
            assertNotNull(presetRegion.getRef(), "preset区域应有ref配置");
            assertEquals("store.list.preset@^1", 
                presetRegion.getRef().get("blockCode"), 
                "ref blockCode应为store.list.preset@^1");
        }

        @Test
        @DisplayName("测试action区域详细配置")
        void testActionRegionDetails() throws IOException {
            // When
            PageFacade bean = objectMapper.readValue(schemaJson, PageFacade.class);
            AbstractPageRegion actionRegion = bean.getRegions().get(2);

            // Then
            assertEquals("action", actionRegion.getType(), "区域类型应为action");
            
            // 验证ref配置
            assertNotNull(actionRegion.getRef(), "action区域应有ref配置");
            assertEquals("store.list.action@^1", 
                actionRegion.getRef().get("blockCode"), 
                "ref blockCode应为store.list.action@^1");
        }

        @Test
        @DisplayName("测试table区域详细配置")
        void testtableDetails() throws IOException {
            // When
            PageFacade bean = objectMapper.readValue(schemaJson, PageFacade.class);
            AbstractPageRegion tableRegion = bean.getRegions().get(3);

            // Then
            assertEquals("table", tableRegion.getType(), "区域类型应为table");
            
            // 验证ref配置
            assertNotNull(tableRegion.getRef(), "table应有ref配置");
            assertEquals("store.list.column@^3", 
                tableRegion.getRef().get("blockCode"), 
                "ref blockCode应为store.list.column@^3");

            // 验证table的props配置
            assertNotNull(tableRegion.getProps(), "table应有props配置");
            Map<String, Object> tableProps = tableRegion.getProps();
            
            // 验证rowSelection配置
            @SuppressWarnings("unchecked")
            Map<String, Object> rowSelection = (Map<String, Object>) tableProps.get("rowSelection");
            assertNotNull(rowSelection, "应有rowSelection配置");
            assertEquals("checkbox", rowSelection.get("type"), "rowSelection类型应为checkbox");
            
            assertEquals("id", tableProps.get("rowKey"), "rowKey应为id");
            
            // 验证scroll配置
            @SuppressWarnings("unchecked")
            Map<String, Object> scroll = (Map<String, Object>) tableProps.get("scroll");
            assertNotNull(scroll, "应有scroll配置");
            assertEquals(1200, scroll.get("x"), "scroll x应为1200");
        }

        @Test
        @DisplayName("测试DrawerFormRegion区域详细配置")
        void testDrawerFormRegionDetails() throws IOException {
            // When
            PageFacade bean = objectMapper.readValue(schemaJson, PageFacade.class);
            AbstractPageRegion drawerRegion = bean.getRegions().get(4);

            // Then
            assertEquals("form", drawerRegion.getType(), "区域类型应为DrawerFormRegion");
            
            // 验证ref配置
            assertNotNull(drawerRegion.getRef(), "DrawerFormRegion应有ref配置");
            assertEquals("store.form.edit@^2", 
                drawerRegion.getRef().get("blockCode"), 
                "ref blockCode应为store.form.edit@^2");

            // 验证props配置
            assertNotNull(drawerRegion.getProps(), "DrawerFormRegion应有props配置");
            assertEquals(720, drawerRegion.getProps().get("width"), "width应为720");
            assertTrue((Boolean) drawerRegion.getProps().get("destroyOnClose"), "destroyOnClose应为true");
        }
    }





    @Nested
    @DisplayName("序列化测试")
    class SerializationTests {

        @Test
        @DisplayName("测试Bean转JSON序列化")
        void testBeanToJsonSerialization() throws IOException {
            // Given
            PageFacade originalBean = objectMapper.readValue(schemaJson, PageFacade.class);

            // When
            String serializedJson = objectMapper.writeValueAsString(originalBean);
            PageFacade deserializedBean = objectMapper.readValue(serializedJson, PageFacade.class);

            // Then
            assertNotNull(serializedJson, "序列化结果不应为null");
            assertNotNull(deserializedBean, "反序列化结果不应为null");
            
            // 验证关键字段保持一致
            assertEquals(originalBean.getRegions().size(), deserializedBean.getRegions().size(), "regions数量应保持一致");
        }

        @Test
        @DisplayName("测试空值处理")
        void testNullValueHandling() throws IOException {
            // Given
            String jsonWithNulls = """
                {
                  "meta": {
                    "entityCode": "test",
                    "title": null,
                    "version": "1.0.0"
                  },
                  "endpoint": null,
                  "regions": [],
                  "events": []
                }
                """;

            // When
            PageFacade bean = objectMapper.readValue(jsonWithNulls, PageFacade.class);

            // Then
            assertNotNull(bean, "Bean不应为null");
            assertNotNull(bean.getMeta(), "meta不应为null");
            assertNull(bean.getMeta().getTitle(), "title应为null");
            assertNull(bean.getEndpoint(), "endpoint应为null");
            assertNotNull(bean.getRegions(), "regions不应为null");
            assertTrue(bean.getRegions().isEmpty(), "regions应为空列表");
            assertNotNull(bean.getEvents(), "effects不应为null");
            assertTrue(bean.getEvents().isEmpty(), "effects应为空列表");
        }
    }

    @Nested
    @DisplayName("边界情况测试")
    class EdgeCaseTests {

        @Test
        @DisplayName("测试大型JSON处理")
        void testLargeJsonHandling() throws IOException {
            // Given - 使用实际的schema文件，它已经是一个相对复杂的JSON
            
            // When
            long startTime = System.currentTimeMillis();
            PageFacade bean = objectMapper.readValue(schemaJson, PageFacade.class);
            long endTime = System.currentTimeMillis();

            // Then
            assertNotNull(bean, "大型JSON应能正确解析");
            assertTrue(endTime - startTime < 1000, "解析时间应在1秒内"); // 性能测试
        }

        @Test
        @DisplayName("测试字段类型兼容性")
        void testFieldTypeCompatibility() throws IOException {
            // Given
            String jsonWithDifferentTypes = """
                {
                  "meta": {
                    "entityCode": "test",
                    "version": "1.0.0"
                  },
                  "regions": [
                    {
                      "type": "filters",
                      "ref": {
                        "blockCode": "test.filter@^1"
                      }
                    }
                  ],
                  "events": []
                }
                """;

            // When & Then - 应该能够处理字符串形式的数字和布尔值
            assertDoesNotThrow(() -> {
                PageFacade bean = objectMapper.readValue(jsonWithDifferentTypes, PageFacade.class);
                assertNotNull(bean);
            }, "应能处理不同类型的字段值");
        }
    }
}
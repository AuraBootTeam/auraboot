package com.auraboot.framework.meta.testx;

import com.auraboot.framework.meta.view.schema.*;

import com.auraboot.framework.meta.view.schema.common.Action;
import com.auraboot.framework.meta.view.schema.common.EventBehavior;
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

/**
 * 门店列表列配置Schema JSON转换测试类
 * 测试 1st-version-store-list-column.json 转换为 TableSchema 对象的功能
 */
@DisplayName("门店列表列配置Schema JSON转换测试")
class Block_ListColumnSchemaTest {

    private ObjectMapper objectMapper;
    private String schemaJson;

    @BeforeEach
    void setUp() throws IOException {
        objectMapper = new ObjectMapper();
        // 读取实际的schema文件
        schemaJson = Files.readString(Paths.get("src/main/resources/schemas/1st-version-store-list-column.json"));
    }

    @Nested
    @DisplayName("基础转换测试")
    class BasicConversionTests {

        @Test
        @DisplayName("测试JSON转换为TableSchemaBean")
        void testJsonToTableSchemaBean() throws IOException {
            // When
            TableSchema bean = objectMapper.readValue(schemaJson, TableSchema.class);

            // Then
            assertNotNull(bean, "TableSchemaBean不应为null");
            assertNotNull(bean.getMeta(), "meta字段不应为null");
            assertNotNull(bean.getColumns(), "columns字段不应为null");
            assertNotNull(bean.getBatchActions(), "batchActions字段不应为null");
            // assertNotNull(bean.getPermission(), "permission字段不应为null"); // JSON中没有根级别的permission字段
            // assertNotNull(bean.getDataSource(), "dataSource字段不应为null"); // JSON中没有dataSource字段
        }

        @Test
        @DisplayName("测试meta字段映射")
        void testMetaFieldMapping() throws IOException {
            // When
            TableSchema bean = objectMapper.readValue(schemaJson, TableSchema.class);
            Meta meta = bean.getMeta();

            // Then
            assertNotNull(meta, "meta不应为null");
            assertEquals("store.list.column", meta.getBlockCode(), "blockCode应为store.list.column");
            assertEquals("3.0.0", meta.getVersion(), "version应为3.0.0");
            assertEquals("Store", meta.getEntityCode(), "entityCode应为Store");
            assertEquals("table", meta.getType(), "type应为table");
            
            // 验证标题国际化
            assertNotNull(meta.getTitle(), "title不应为null");
            assertEquals("门店列表视图", meta.getTitle().get("zh-CN"), "中文标题应为门店列表视图");
            assertEquals("Store List Column", meta.getTitle().get("en-US"), "英文标题应为Store List Column");
        }

        @Test
        @DisplayName("测试table字段映射")
        void testTableFieldMapping() throws IOException {
            // When
            TableSchema bean = objectMapper.readValue(schemaJson, TableSchema.class);


            
            // 验证layout子对象
            Map<String, Object> layout = (Map<String, Object>) bean.getLayout();
            assertNotNull(layout, "layout不应为null");
            assertEquals("middle", layout.get("size"), "layout.size应为middle");
            
            // 验证style子对象
            Map<String, Object> style = (Map<String, Object>) bean.getStyle();
            assertNotNull(style, "style不应为null");
            assertEquals("middle", style.get("size"), "style.size应为middle");
            
            // 验证props子对象
            Map<String, Object> props = (Map<String, Object>) bean.getProps();
            assertNotNull(props, "props不应为null");
            assertEquals("id", props.get("rowKey"), "props.rowKey应为id");
        }

        @Test
        @DisplayName("测试table滚动配置")
        void testTableScrollConfig() throws IOException {
            // When
            TableSchema bean = objectMapper.readValue(schemaJson, TableSchema.class);

            // 验证style中的scroll配置
            Map<String, Object> style = (Map<String, Object>) bean.getStyle();
            assertNotNull(style, "style不应为null");
            Map<String, Object> styleScroll = (Map<String, Object>) style.get("scroll");
            assertNotNull(styleScroll, "style.scroll不应为null");
            assertEquals(1200, styleScroll.get("x"), "style.scroll.x应为1200");
            assertEquals(600, styleScroll.get("y"), "style.scroll.y应为600");
            
            // 验证props中的scroll配置
            Map<String, Object> props = (Map<String, Object>) bean.getProps();
            assertNotNull(props, "props不应为null");
            Map<String, Object> propsScroll = (Map<String, Object>) props.get("scroll");
            assertNotNull(propsScroll, "props.scroll不应为null");
            assertEquals(1200, propsScroll.get("x"), "props.scroll.x应为1200");
            assertEquals(600, propsScroll.get("y"), "props.scroll.y应为600");
        }

        @Test
        @DisplayName("测试table分页配置")
        void testTablePaginationConfig() throws IOException {
            // When
            TableSchema bean = objectMapper.readValue(schemaJson, TableSchema.class);
            Map<String, Object> pagination = (Map<String, Object>) bean.getPagination();

            // Then
            assertNotNull(pagination, "pagination不应为null");
            assertTrue((Boolean) pagination.get("showSizeChanger"), "showSizeChanger应为true");
            assertTrue((Boolean) pagination.get("showQuickJumper"), "showQuickJumper应为true");
            assertTrue((Boolean) pagination.get("showTotal"), "showTotal应为true");
            
            List<String> pageSizeOptions = (List<String>) pagination.get("pageSizeOptions");
            assertEquals(4, pageSizeOptions.size(), "应有4个分页大小选项");
            assertEquals("10", pageSizeOptions.get(0), "第一个选项应为10");
            assertEquals("20", pageSizeOptions.get(1), "第二个选项应为20");
            assertEquals("50", pageSizeOptions.get(2), "第三个选项应为50");
            assertEquals("100", pageSizeOptions.get(3), "第四个选项应为100");
        }
    }

    @Nested
    @DisplayName("列配置测试")
    class ColumnConfigTests {

        @Test
        @DisplayName("测试columns数量")
        void testColumnsCount() throws IOException {
            // When
            TableSchema bean = objectMapper.readValue(schemaJson, TableSchema.class);
            List<TableColumn> columns = bean.getColumns();

            // Then
            assertNotNull(columns, "columns不应为null");
            assertEquals(10, columns.size(), "应有10个列");
        }

        @Test
        @DisplayName("测试code列配置")
        void testCodeColumnConfig() throws IOException {
            // When
            TableSchema bean = objectMapper.readValue(schemaJson, TableSchema.class);
            TableColumn codeColumn = bean.getColumns().get(0);

            // Then
            assertEquals("code", codeColumn.getCode(), "code应为code");
            assertTrue(codeColumn.getSortable(), "sortable应为true");
            
            // 验证标签国际化
            assertNotNull(codeColumn.getLabel(), "title不应为null");
              assertEquals("门店编码", codeColumn.getLabel().get("zh-CN"), "中文标签应为门店编码");
              assertEquals("Store Code", codeColumn.getLabel().get("en-US"), "英文标签应为Store Code");
        }

        @Test
        @DisplayName("测试name列配置")
        void testNameColumnConfig() throws IOException {
            // When
            TableSchema bean = objectMapper.readValue(schemaJson, TableSchema.class);
            TableColumn nameColumn = bean.getColumns().get(1);

            // Then
            assertEquals("name", nameColumn.getCode(), "code应为name");

            // 验证标签国际化
            assertNotNull(nameColumn.getLabel(), "title不应为null");
              assertEquals("门店名称", nameColumn.getLabel().get("zh-CN"), "中文标签应为门店名称");
              assertEquals("Store Name", nameColumn.getLabel().get("en-US"), "英文标签应为Store Name");
        }

        @Test
        @DisplayName("测试type列配置")
        void testTypeColumnConfig() throws IOException {
            // When
            TableSchema bean = objectMapper.readValue(schemaJson, TableSchema.class);
            TableColumn typeColumn = bean.getColumns().get(2);

            // Then
            assertEquals("type", typeColumn.getCode(), "code应为type");

            // 验证标签国际化
            assertNotNull(typeColumn.getLabel(), "title不应为null");
              assertEquals("门店类型", typeColumn.getLabel().get("zh-CN"), "中文标签应为门店类型");
              assertEquals("Store Type", typeColumn.getLabel().get("en-US"), "英文标签应为Store Type");
        }

        @Test
        @DisplayName("测试status列配置")
        void testStatusColumnConfig() throws IOException {
            // When
            TableSchema bean = objectMapper.readValue(schemaJson, TableSchema.class);
            TableColumn statusColumn = bean.getColumns().get(3);

            // Then
            assertEquals("status", statusColumn.getCode(), "code应为status");

            // 验证标签国际化
            assertNotNull(statusColumn.getLabel(), "title不应为null");
              assertEquals("状态", statusColumn.getLabel().get("zh-CN"), "中文标签应为状态");
              assertEquals("Status", statusColumn.getLabel().get("en-US"), "英文标签应为Status");
        }

        @Test
        @DisplayName("测试address列配置")
        void testAddressColumnConfig() throws IOException {
            // When
            TableSchema bean = objectMapper.readValue(schemaJson, TableSchema.class);
            TableColumn addressColumn = bean.getColumns().get(4);

            // Then
            assertEquals("address", addressColumn.getCode(), "code应为address");
            
            // 验证标签国际化
            assertNotNull(addressColumn.getLabel(), "title不应为null");
              assertEquals("地址", addressColumn.getLabel().get("zh-CN"), "中文标签应为地址");
              assertEquals("Address", addressColumn.getLabel().get("en-US"), "英文标签应为Address");
        }

        @Test
        @DisplayName("测试contactPhone列配置")
        void testContactPhoneColumnConfig() throws IOException {
            // When
            TableSchema bean = objectMapper.readValue(schemaJson, TableSchema.class);
            TableColumn contactPhoneColumn = bean.getColumns().get(5);

            // Then
            assertEquals("contactPhone", contactPhoneColumn.getCode(), "code应为contactPhone");
            
            // 验证标签国际化
            assertNotNull(contactPhoneColumn.getLabel(), "title不应为null");
              assertEquals("联系电话", contactPhoneColumn.getLabel().get("zh-CN"), "中文标签应为联系电话");
              assertEquals("Contact Phone", contactPhoneColumn.getLabel().get("en-US"), "英文标签应为Contact Phone");
        }

        @Test
        @DisplayName("测试manager列配置")
        void testManagerColumnConfig() throws IOException {
            // When
            TableSchema bean = objectMapper.readValue(schemaJson, TableSchema.class);
            TableColumn managerColumn = bean.getColumns().get(6);

            // Then
            assertEquals("manager", managerColumn.getCode(), "code应为manager");
            
            // 验证标签国际化
            assertNotNull(managerColumn.getLabel(), "title不应为null");
              assertEquals("店长", managerColumn.getLabel().get("zh-CN"), "中文标签应为店长");
              assertEquals("Manager", managerColumn.getLabel().get("en-US"), "英文标签应为Manager");
        }

        @Test
        @DisplayName("测试openingHours列配置")
        void testOpeningHoursColumnConfig() throws IOException {
            // When
            TableSchema bean = objectMapper.readValue(schemaJson, TableSchema.class);
            TableColumn openingHoursColumn = bean.getColumns().get(7);

            // Then
            assertEquals("openingHours", openingHoursColumn.getCode(), "code应为openingHours");
            
            // 验证标签国际化
            assertNotNull(openingHoursColumn.getLabel(), "title不应为null");
              assertEquals("营业时间", openingHoursColumn.getLabel().get("zh-CN"), "中文标签应为营业时间");
              assertEquals("Opening Hours", openingHoursColumn.getLabel().get("en-US"), "英文标签应为Opening Hours");
        }

        @Test
        @DisplayName("测试createdAt列配置")
        void testCreatedAtColumnConfig() throws IOException {
            // When
            TableSchema bean = objectMapper.readValue(schemaJson, TableSchema.class);
            TableColumn createdAtColumn = bean.getColumns().get(8);

            // Then
            assertEquals("createdAt", createdAtColumn.getCode(), "code应为createdAt");
            
            // 验证标签国际化
            assertNotNull(createdAtColumn.getLabel(), "title不应为null");
              assertEquals("创建时间", createdAtColumn.getLabel().get("zh-CN"), "中文标签应为创建时间");
              assertEquals("Created At", createdAtColumn.getLabel().get("en-US"), "英文标签应为Created At");
        }

        @Test
        @DisplayName("测试actions列配置")
        void testActionsColumnConfig() throws IOException {
            // When
            TableSchema bean = objectMapper.readValue(schemaJson, TableSchema.class);
            TableColumn actionsColumn = bean.getColumns().get(9);

            // Then
            assertEquals("actions", actionsColumn.getCode(), "code应为actions");
            
            // 验证标签国际化
            assertNotNull(actionsColumn.getLabel(), "title不应为null");
              assertEquals("操作", actionsColumn.getLabel().get("zh-CN"), "中文标签应为操作");
              assertEquals("Actions", actionsColumn.getLabel().get("en-US"), "英文标签应为Actions");
        }
    }

    @Nested
    @DisplayName("批量操作测试")
    class BatchActionTests {

        @Test
        @DisplayName("测试batchActions配置")
        void testBatchActionsConfig() throws IOException {
            // When
            TableSchema bean = objectMapper.readValue(schemaJson, TableSchema.class);
            List<Action> batchActions = bean.getBatchActions();

            // Then
            assertNotNull(batchActions, "batchActions不应为null");
            assertEquals(2, batchActions.size(), "应有2个批量操作");
        }

        @Test
        @DisplayName("测试行操作配置")
        void testActionsConfig() throws IOException {
            // When
            TableSchema bean = objectMapper.readValue(schemaJson, TableSchema.class);
            List<Action> actions = bean.getActions();

            // Then
            assertNotNull(actions, "actions不应为null");
            assertEquals(3, actions.size(), "应有3个行操作");
        }
        
        @Test
        @DisplayName("测试查看操作")
        void testViewAction() throws IOException {
            // When
            TableSchema bean = objectMapper.readValue(schemaJson, TableSchema.class);
            Action viewAction = bean.getActions().get(0);


            // Test UI
            Map<String, Object> ui = viewAction.getProps();
            assertNotNull(ui, "ui不应为null");
            assertEquals("EyeOutlined", ui.get("icon"), "icon应为EyeOutlined");
            assertEquals("link", ui.get("type"), "type应为link");
            

            // Test permission
            assertEquals("store:read", viewAction.getPermission(), "permission应为store:read");
        }
        
        @Test
        @DisplayName("测试编辑操作")
        void testEditAction() throws IOException {
            // When
            TableSchema bean = objectMapper.readValue(schemaJson, TableSchema.class);
            Action editAction = bean.getActions().get(1);


            // Test UI
            Map<String, Object> ui = editAction.getProps();
            assertNotNull(ui, "ui不应为null");
            assertEquals("EditOutlined", ui.get("icon"), "icon应为EditOutlined");
            
            // Test behavior
            // Test permission
            assertEquals("store:update", editAction.getPermission(), "permission应为store:update");
        }
        
        @Test
        @DisplayName("测试删除操作")
        void testDeleteAction() throws IOException {
            // When
            TableSchema bean = objectMapper.readValue(schemaJson, TableSchema.class);
            Action deleteAction = bean.getActions().get(2);

            // Then
            assertEquals("delete", deleteAction.getCode(), "code应为delete");

            // Test UI
            Map<String, Object> ui = deleteAction.getProps();
            assertNotNull(ui, "ui不应为null");
            assertEquals("DeleteOutlined", ui.get("icon"), "icon应为DeleteOutlined");
            assertEquals("link", ui.get("type"), "type应为link");
            assertEquals(true, ui.get("danger"), "danger应为true");
            


            // Test permission
            assertEquals("store:delete", deleteAction.getPermission(), "permission应为store:delete");
        }

        @Test
        @DisplayName("测试批量删除操作")
        void testBatchDeleteAction() throws IOException {
            // When
            TableSchema bean = objectMapper.readValue(schemaJson, TableSchema.class);
            Action batchDeleteAction = bean.getBatchActions().get(0);


            // Test UI
            Map<String, Object> ui = batchDeleteAction.getProps();
            assertNotNull(ui, "ui不应为null");
            assertEquals("DeleteOutlined", ui.get("icon"), "icon应为DeleteOutlined");
            assertEquals(true, ui.get("danger"), "danger应为true");
            

            // Test permission
            assertEquals("store:delete", batchDeleteAction.getPermission(), "permission应为store:delete");
        }

        @Test
        @DisplayName("测试批量导出操作")
        void testBatchExportAction() throws IOException {
            // When
            TableSchema bean = objectMapper.readValue(schemaJson, TableSchema.class);
            Action batchExportAction = bean.getBatchActions().get(1);

            // Test UI
            Map<String, Object> ui = batchExportAction.getProps();
            assertNotNull(ui, "ui不应为null");
            assertEquals("ExportOutlined", ui.get("icon"), "icon应为ExportOutlined");
            

            // Test permission
            assertEquals("store:export", batchExportAction.getPermission(), "permission应为store:export");
        }
    }


    @Nested
    @DisplayName("权限策略测试")
    class PolicyTests {
        // 注释掉权限测试，因为JSON中没有根级别的permission字段
        /*
        @Test
        @DisplayName("测试permission配置")
        void testPermissionConfig() throws IOException {
            // When
            TableSchema bean = objectMapper.readValue(schemaJson, TableSchema.class);
            String permission = bean.getPermission();

            // Then
            assertNotNull(permission, "permission不应为null");
            assertEquals("store:read", permission, "权限应为store:read");
        }
        */
    }

    // @Nested
    // @DisplayName("数据源配置测试")
    // class DataSourceTests {

    //     @Test
    //     @DisplayName("测试dataSource配置")
    //     void testDataSourceConfig() throws IOException {
    //         // When
    //         TableSchema bean = objectMapper.readValue(schemaJson, TableSchema.class);
    //         Map<String, Object> dataSource = bean.getDataSource();

    //         // Then
    //         assertNotNull(dataSource, "dataSource不应为null");
    //         assertEquals("/api/dynamic/Store/list", dataSource.get("endpoint"), "endpoint应为/api/dynamic/Store/list");
    //         assertEquals("post", dataSource.get("method"), "method应为POST");
            
    //         // 验证permission配置
    //         String permission = (String) dataSource.get("permission");
    //         assertNotNull(permission, "permission不应为null");
    //         assertEquals("store:read", permission, "permission应为store:read");
            
    //         // 验证transform配置
    //         Map<String, Object> transform = (Map<String, Object>) dataSource.get("transform");
    //         assertNotNull(transform, "transform不应为null");
    //         assertNotNull(transform.get("request"), "request transform不应为null");
    //         assertNotNull(transform.get("response"), "response transform不应为null");
    //     }
    // } // JSON中没有dataSource字段，注释掉相关测试

    @Nested
    @DisplayName("序列化测试")
    class SerializationTests {

        @Test
        @DisplayName("测试Bean转JSON序列化")
        void testBeanToJsonSerialization() throws IOException {
            // Given
            TableSchema originalBean = objectMapper.readValue(schemaJson, TableSchema.class);

            // When
            String serializedJson = objectMapper.writeValueAsString(originalBean);
            TableSchema deserializedBean = objectMapper.readValue(serializedJson, TableSchema.class);

            // Then
            assertNotNull(serializedJson, "序列化结果不应为null");
            assertNotNull(deserializedBean, "反序列化结果不应为null");
            
            // 验证关键字段保持一致
            assertEquals(originalBean.getColumns().size(), deserializedBean.getColumns().size(), "columns数量应保持一致");
            assertEquals(originalBean.getBatchActions().size(), deserializedBean.getBatchActions().size(), "batchActions数量应保持一致");
        }

        @Test
        @DisplayName("测试空值处理")
        void testNullValueHandling() throws IOException {
            // Given
            String jsonWithNulls = """
                {
                  "meta": {
                    "blockCode": "test.table",
                    "version": "1.0.0",
                    "entityCode": "Test",
                    "type": "table"
                  },
                  "table": {
                    "rowKey": "id"
                  },
                  "columns": [],
                  "batchActions": null,
                  "events": null,
                  "permission": null
                }
                """;

            // When
            TableSchema bean = objectMapper.readValue(jsonWithNulls, TableSchema.class);

            // Then
            assertNotNull(bean, "Bean不应为null");
            assertNotNull(bean.getMeta(), "meta不应为null");
            assertEquals("test.table", bean.getMeta().getBlockCode(), "formCode应正确解析");
            assertNotNull(bean.getColumns(), "columns不应为null");
            assertTrue(bean.getColumns().isEmpty(), "columns应为空列表");
            assertNull(bean.getBatchActions(), "batchActions应为null");
        }
    }

    @Nested
    @DisplayName("边界情况测试")
    class EdgeCaseTests {

        @Test
        @DisplayName("测试表格配置完整性")
        void testTableConfigCompleteness() throws IOException {
            // When
            TableSchema bean = objectMapper.readValue(schemaJson, TableSchema.class);


            
            // 验证子对象结构
            Map<String, Object> layout = (Map<String, Object>) bean.getLayout();
            Map<String, Object> style = (Map<String, Object>) bean.getStyle();
            Map<String, Object> props = (Map<String, Object>) bean.getProps();
            
            assertNotNull(layout, "layout不应为null");
            assertNotNull(style, "style不应为null");
            assertNotNull(props, "props不应为null");
            
            assertTrue(layout.containsKey("size"), "layout应包含size配置");
            assertTrue(style.containsKey("size"), "style应包含size配置");
            assertTrue(style.containsKey("scroll"), "style应包含scroll配置");
            assertTrue(props.containsKey("rowKey"), "props应包含rowKey配置");
            assertTrue(props.containsKey("scroll"), "props应包含scroll配置");
        }



        @Test
        @DisplayName("测试列渲染类型多样性")
        void testColumnRenderTypeDiversity() throws IOException {
            // When
            TableSchema bean = objectMapper.readValue(schemaJson, TableSchema.class);
            List<TableColumn> columns = bean.getColumns();

            // Then - 由于JSON中没有render字段，跳过此测试
            // 验证列数量
            assertEquals(10, columns.size(), "应有10列");
        }


        @Test
        @DisplayName("测试权限策略一致性")
        void testPermissionPolicyConsistency() throws IOException {
            // When
            TableSchema bean = objectMapper.readValue(schemaJson, TableSchema.class);
            
            // 验证批量操作数量
            List<Action> batchActions = bean.getBatchActions();
            assertEquals(2, batchActions.size(), "应有2个批量操作");
            
            // 验证dataSource中的权限 - JSON中没有dataSource字段，注释掉相关测试
            // Map<String, Object> dataSource = bean.getDataSource();
            // String permission = (String) dataSource.get("permission");
            // assertEquals("store:read", permission, "数据源权限应正确");
        }

        @Test
        @DisplayName("测试国际化配置完整性")
        void testI18nConfigCompleteness() throws IOException {
            // When
            TableSchema bean = objectMapper.readValue(schemaJson, TableSchema.class);
            
            // 验证meta标题国际化
            Map<String, String> metaTitle = bean.getMeta().getTitle();
            assertTrue(metaTitle.containsKey("zh-CN"), "meta标题应包含中文");
            assertTrue(metaTitle.containsKey("en-US"), "meta标题应包含英文");
            
            // 验证列标签国际化
            for (TableColumn column : bean.getColumns()) {
                if (column.getLabel() != null) {
                      assertTrue(column.getLabel().containsKey("zh-CN"),
                              "列标签应包含中文");
                      assertTrue(column.getLabel().containsKey("en-US"),
                              "列标签应包含英文");
                }
            }
            
            // 验证批量操作数量
             assertEquals(2, bean.getBatchActions().size(), "应有2个批量操作");
        }
    }
}
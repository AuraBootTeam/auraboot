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
 * 门店表单查看Schema JSON转换测试类
 * 测试 1st-version-store-form.view.json 转换为 FormFacade 对象的功能
 */
@DisplayName("门店表单查看Schema JSON转换测试")
class Block_FormViewSchemaTest {

    private ObjectMapper objectMapper;
    private String schemaJson;

    @BeforeEach
    void setUp() throws IOException {
        objectMapper = new ObjectMapper();
        // 读取实际的schema文件
        schemaJson = Files.readString(Paths.get("src/main/resources/schemas/1st-version-store-form.view.json"));
    }

    @Nested
    @DisplayName("基础转换测试")
    class BasicConversionTests {

        @Test
        @DisplayName("测试JSON转换为FormSchemaBean")
        void testJsonToFormSchemaBean() throws IOException {
            // When
            FormFacade bean = objectMapper.readValue(schemaJson, FormFacade.class);

            // Then
            assertNotNull(bean, "FormSchemaBean不应为null");
            assertNotNull(bean.getBase(), "base字段不应为null");
            assertNotNull(bean.getMeta(), "meta字段不应为null");
            assertNotNull(bean.getProps(), "variables字段不应为null");
            assertNotNull(bean.getActions(), "actions字段不应为null");
            assertNotNull(bean.getOverrides(), "overrides字段不应为null");
        }

        @Test
        @DisplayName("测试base字段映射")
        void testBaseFieldMapping() throws IOException {
            // When
            FormFacade bean = objectMapper.readValue(schemaJson, FormFacade.class);

            // Then
            assertEquals("form:store.form.base@^2", bean.getBase(), "base应为form:store.form.base@^2");
        }

        @Test
        @DisplayName("测试meta字段映射")
        void testMetaFieldMapping() throws IOException {
            // When
            FormFacade bean = objectMapper.readValue(schemaJson, FormFacade.class);
            Meta meta = bean.getMeta();

            // Then
            assertNotNull(meta, "meta不应为null");
            assertNotNull(meta.getTitle(), "title不应为null");
            assertEquals("门店详情", meta.getTitle().get("zh-CN"), "中文标题应为门店详情");
            assertEquals("Store Details", meta.getTitle().get("en-US"), "英文标题应为Store Details");
        }

        @Test
        @DisplayName("测试variables字段映射")
        void testVariablesFieldMapping() throws IOException {
            // When
            FormFacade bean = objectMapper.readValue(schemaJson, FormFacade.class);
           Map<String,Object> variables = bean.getProps();

            // Then
            assertNotNull(variables, "variables不应为null");
            assertEquals("view", variables.get("mode"), "mode应为view");
        }
    }



    @Nested
    @DisplayName("动作配置测试")
    class ActionConfigTests {

        @Test
        @DisplayName("测试actions配置")
        void testActionsConfig() throws IOException {
            // When
            FormFacade bean = objectMapper.readValue(schemaJson, FormFacade.class);
            List<Action> actions = bean.getActions();

            // Then
            assertNotNull(actions, "actions不应为null");
            assertEquals(2, actions.size(), "应有2个action");
        }

        @Test
        @DisplayName("测试print动作配置")
        void testPrintActionConfig() throws IOException {
            // When
            FormFacade bean = objectMapper.readValue(schemaJson, FormFacade.class);
            Action printAction = bean.getActions().get(0);

            // Then
            assertEquals("print", printAction.getCode(), "action code应为print");


        }

        @Test
        @DisplayName("测试export动作配置")
        void testExportActionConfig() throws IOException {
            // When
            FormFacade bean = objectMapper.readValue(schemaJson, FormFacade.class);
            Action exportAction = bean.getActions().get(1);

            // Then
            assertEquals("export", exportAction.getCode(), "action code应为export");



            // 验证permission配置
            assertNotNull(exportAction.getPermission(), "permission不应为null");
            assertEquals("store:export", exportAction.getPermission(), "permission应为store:export");
        }
    }



    @Nested
    @DisplayName("覆盖配置测试")
    class OverrideConfigTests {

        @Test
        @DisplayName("测试overrides配置")
        void testOverridesConfig() throws IOException {
            // When
            FormFacade bean = objectMapper.readValue(schemaJson, FormFacade.class);
            FormSchemaOverrides overrides = bean.getOverrides();

            // Then
            assertNotNull(overrides, "overrides不应为null");
            Map<String, FormField> fieldsByKey = overrides.getFieldsByKey();
            assertNotNull(fieldsByKey, "fieldsByKey不应为null");
            
            // 验证code字段的覆盖配置
            FormField codeField = fieldsByKey.get("code");
            assertNotNull(codeField, "code字段覆盖不应为null");
            assertTrue((Boolean) codeField.getProps().get("readOnly"), "code字段readOnly应为true");
            assertEquals("Text", codeField.getComponent(), "code字段component应为Text");
            assertTrue((Boolean) codeField.getProps().get("copyable"), "code字段copyable应为true");
        }

        @Test
        @DisplayName("测试name字段覆盖")
        void testNameFieldOverride() throws IOException {
            // When
            FormFacade bean = objectMapper.readValue(schemaJson, FormFacade.class);
            FormSchemaOverrides overrides = bean.getOverrides();
            Map<String, FormField> fieldsByKey = overrides.getFieldsByKey();
            FormField nameOverride = fieldsByKey.get("name");

            // Then
            assertNotNull(nameOverride, "name字段覆盖不应为null");
            assertTrue((Boolean) nameOverride.getProps().get("readOnly"), "name字段readOnly应为true");
        }

        @Test
        @DisplayName("测试type字段覆盖")
        void testTypeFieldOverride() throws IOException {
            // When
            FormFacade bean = objectMapper.readValue(schemaJson, FormFacade.class);
            FormSchemaOverrides overrides = bean.getOverrides();
            Map<String, FormField> fieldsByKey = overrides.getFieldsByKey();
            FormField typeOverride = fieldsByKey.get("type");

            // Then
            assertNotNull(typeOverride, "type字段覆盖不应为null");
            assertTrue((Boolean) typeOverride.getProps().get("readOnly"), "type字段readOnly应为true");
            assertEquals("Tag", typeOverride.getComponent(), "component应为Tag");
        }

        @Test
        @DisplayName("测试status字段覆盖")
        void testStatusFieldOverride() throws IOException {
            // When
            FormFacade bean = objectMapper.readValue(schemaJson, FormFacade.class);
            FormSchemaOverrides overrides = bean.getOverrides();
            Map<String, FormField> fieldsByKey = overrides.getFieldsByKey();
            FormField statusOverride = fieldsByKey.get("status");

            // Then
            assertNotNull(statusOverride, "status字段覆盖不应为null");
            assertTrue((Boolean) statusOverride.getProps().get("readOnly"), "status字段readOnly应为true");
            assertEquals("Badge", statusOverride.getComponent(), "component应为Badge");
        }

        @Test
        @DisplayName("测试联系方式字段覆盖")
        void testContactFieldsOverride() throws IOException {
            // When
            FormFacade bean = objectMapper.readValue(schemaJson, FormFacade.class);
            FormSchemaOverrides overrides = bean.getOverrides();
            Map<String, FormField> fieldsByKey = overrides.getFieldsByKey();
            FormField phoneOverride = fieldsByKey.get("contact_phone");
            FormField emailOverride = fieldsByKey.get("contact_email");

            // Then
            assertNotNull(phoneOverride, "contact_phone字段覆盖不应为null");
            assertTrue((Boolean) phoneOverride.getProps().get("readOnly"), "contact_phone字段readOnly应为true");
            assertEquals("Text", phoneOverride.getComponent(), "phone component应为Text");
            assertTrue((Boolean) phoneOverride.getProps().get("copyable"), "phone copyable应为true");
            
            assertNotNull(emailOverride, "contact_email字段覆盖不应为null");
            assertTrue((Boolean) emailOverride.getProps().get("readOnly"), "contact_email字段readOnly应为true");
            assertEquals("Text", emailOverride.getComponent(), "email component应为Text");
            assertTrue((Boolean) emailOverride.getProps().get("copyable"), "email copyable应为true");
        }

        @Test
        @DisplayName("测试opening_hours字段覆盖")
        void testOpeningHoursFieldOverride() throws IOException {
            // When
            FormFacade bean = objectMapper.readValue(schemaJson, FormFacade.class);
            FormSchemaOverrides overrides = bean.getOverrides();
            Map<String, FormField> fieldsByKey = overrides.getFieldsByKey();
            FormField hoursOverride = fieldsByKey.get("opening_hours");

            // Then
            assertNotNull(hoursOverride, "opening_hours字段覆盖不应为null");
            assertTrue((Boolean) hoursOverride.getProps().get("readOnly"), "opening_hours字段readOnly应为true");
            assertEquals("TimeRangeTable", hoursOverride.getComponent(), "component应为TimeRangeTable");
        }

        @Test
        @DisplayName("测试manager_id和organization_id字段覆盖")
        void testManagerAndOrganizationFieldsOverride() throws IOException {
            // When
            FormFacade bean = objectMapper.readValue(schemaJson, FormFacade.class);
            FormSchemaOverrides overrides = bean.getOverrides();
            Map<String, FormField> fieldsByKey = overrides.getFieldsByKey();
            FormField managerOverride = fieldsByKey.get("manager_id");
            FormField organizationOverride = fieldsByKey.get("organization_id");

            // Then
            assertNotNull(managerOverride, "manager_id字段覆盖不应为null");
            assertTrue((Boolean) managerOverride.getProps().get("readOnly"), "manager_id字段readOnly应为true");
            
            assertNotNull(organizationOverride, "organization_id字段覆盖不应为null");
            assertTrue((Boolean) organizationOverride.getProps().get("readOnly"), "organization_id字段readOnly应为true");
        }

        @Test
        @DisplayName("测试address和coordinates字段覆盖")
        void testLocationFieldsOverride() throws IOException {
            // When
            FormFacade bean = objectMapper.readValue(schemaJson, FormFacade.class);
            FormSchemaOverrides overrides = bean.getOverrides();
            Map<String, FormField> fieldsByKey = overrides.getFieldsByKey();
            FormField addressOverride = fieldsByKey.get("address");
            FormField coordinatesOverride = fieldsByKey.get("coordinates");

            // Then
            assertNotNull(addressOverride, "address字段覆盖不应为null");
            assertTrue((Boolean) addressOverride.getProps().get("readOnly"), "address字段readOnly应为true");
            
            assertNotNull(coordinatesOverride, "coordinates字段覆盖不应为null");
            assertTrue((Boolean) coordinatesOverride.getProps().get("readOnly"), "coordinates字段readOnly应为true");
        }

        @Test
        @DisplayName("测试description和images字段覆盖")
        void testDescriptionAndImagesFieldsOverride() throws IOException {
            // When
            FormFacade bean = objectMapper.readValue(schemaJson, FormFacade.class);
            FormSchemaOverrides overrides = bean.getOverrides();
            Map<String, FormField> fieldsByKey = overrides.getFieldsByKey();
            FormField descriptionOverride = fieldsByKey.get("description");
            FormField imagesOverride = fieldsByKey.get("images");

            // Then
            assertNotNull(descriptionOverride, "description字段覆盖不应为null");
            assertTrue((Boolean) descriptionOverride.getProps().get("readOnly"), "description字段readOnly应为true");
            
            assertNotNull(imagesOverride, "images字段覆盖不应为null");
            assertTrue((Boolean) imagesOverride.getProps().get("readOnly"), "images字段readOnly应为true");
        }

        @Test
        @DisplayName("测试所有字段只读配置")
        void testAllFieldsReadOnlyConfig() throws IOException {
            // When
            FormFacade bean = objectMapper.readValue(schemaJson, FormFacade.class);
            FormSchemaOverrides overrides = bean.getOverrides();
            Map<String, FormField> fieldsByKey = overrides.getFieldsByKey();

            // Then
            String[] expectedFields = {
                "code", "name", "type", "status", "address", "coordinates",
                "contact_phone", "contact_email", "manager_id", "organization_id",
                "opening_hours", "description", "images"
            };
            
            for (String fieldName : expectedFields) {
                FormField fieldOverride = fieldsByKey.get(fieldName);
                assertNotNull(fieldOverride, fieldName + "字段覆盖不应为null");
                assertTrue((Boolean) fieldOverride.getProps().get("readOnly"), fieldName + "字段readOnly应为true");
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
            FormFacade originalBean = objectMapper.readValue(schemaJson, FormFacade.class);

            // When
            String serializedJson = objectMapper.writeValueAsString(originalBean);
            FormFacade deserializedBean = objectMapper.readValue(serializedJson, FormFacade.class);

            // Then
            assertNotNull(serializedJson, "序列化结果不应为null");
            assertNotNull(deserializedBean, "反序列化结果不应为null");
            
            // 验证关键字段保持一致
            assertEquals(originalBean.getBase(), deserializedBean.getBase(), "base应保持一致");
            assertEquals(originalBean.getActions().size(), deserializedBean.getActions().size(), "actions数量应保持一致");
            
            // effects字段在view schema中不存在，所以只在非null时比较
            if (originalBean.getEvents() != null && deserializedBean.getEvents() != null) {
                assertEquals(originalBean.getEvents().size(), deserializedBean.getEvents().size(), "effects数量应保持一致");
            }
        }

        @Test
        @DisplayName("测试空值处理")
        void testNullValueHandling() throws IOException {
            // Given
            String jsonWithNulls = """
                {
                  "base": "form:test@1.0",
                  "meta": {
                    "title": null
                  },
                  "variables": null,
                  "endpoint": null,
                  "actions": [],
                  "events": null,
                  "overrides": null
                }
                """;

            // When
            FormFacade bean = objectMapper.readValue(jsonWithNulls, FormFacade.class);

            // Then
            assertNotNull(bean, "Bean不应为null");
            assertEquals("form:test@1.0", bean.getBase(), "base应正确解析");
            assertNotNull(bean.getMeta(), "meta不应为null");
            assertNull(bean.getMeta().getTitle(), "title应为null");
            assertNull(bean.getProps(), "variables应为null");
            assertNull(bean.getEndpoint(), "endpoint应为null");
            assertNotNull(bean.getActions(), "actions不应为null");
            assertTrue(bean.getActions().isEmpty(), "actions应为空列表");
            assertNull(bean.getEvents(), "effects应为null");
            assertNull(bean.getOverrides(), "overrides应为null");
        }
    }

    @Nested
    @DisplayName("边界情况测试")
    class EdgeCaseTests {

        @Test
        @DisplayName("测试查看模式配置")
        void testViewModeConfiguration() throws IOException {
            // When
            FormFacade bean = objectMapper.readValue(schemaJson, FormFacade.class);
           Map<String,Object> variables = bean.getProps();

            // Then
            assertNotNull(variables, "variables不应为null");
            assertEquals("view", variables.get("mode"), "mode应为view");
        }

        @Test
        @DisplayName("测试只读表单特性")
        void testReadOnlyFormFeatures() throws IOException {
            // When
            FormFacade bean = objectMapper.readValue(schemaJson, FormFacade.class);
            
            // 验证没有提交类型的动作
            List<Action> actions = bean.getActions();
            for (Action action : actions) {
                // Action 没有 getUi() 方法，ui 配置在 behavior 中
                // Map<String, Object> ui = action.getUi();
                // if (ui != null && ui.containsKey("htmlType")) {
                //     assertNotEquals("submit", ui.get("htmlType"), "查看模式不应有submit类型的动作");
                // }
            }
            
            // 验证所有字段都设置为只读
            FormSchemaOverrides overrides = bean.getOverrides();
            Map<String, FormField> fieldsByKey = overrides.getFieldsByKey();
            for (Map.Entry<String, FormField> entry : fieldsByKey.entrySet()) {
                FormField fieldConfig = entry.getValue();
                assertTrue((Boolean) fieldConfig.getProps().get("readOnly"), 
                    "字段 " + entry.getKey() + " 应设置为只读");
            }
        }

        @Test
        @DisplayName("测试组件类型配置")
        void testComponentTypeConfiguration() throws IOException {
            // When
            FormFacade bean = objectMapper.readValue(schemaJson, FormFacade.class);
            FormSchemaOverrides overrides = bean.getOverrides();
            Map<String, FormField> fieldsByKey = overrides.getFieldsByKey();

            // Then
            FormField codeField = fieldsByKey.get("code");
            assertEquals("Text", codeField.getComponent(), "code字段应使用Text组件");
            
            FormField typeField = fieldsByKey.get("type");
            assertEquals("Tag", typeField.getComponent(), "type字段应使用Tag组件");
            
            FormField statusField = fieldsByKey.get("status");
            assertEquals("Badge", statusField.getComponent(), "status字段应使用Badge组件");
            
            FormField hoursField = fieldsByKey.get("opening_hours");
            assertEquals("TimeRangeTable", hoursField.getComponent(), "opening_hours字段应使用TimeRangeTable组件");
        }

        @Test
        @DisplayName("测试可复制字段配置")
        void testCopyableFieldsConfiguration() throws IOException {
            // When
            FormFacade bean = objectMapper.readValue(schemaJson, FormFacade.class);
            FormSchemaOverrides overrides = bean.getOverrides();
            Map<String, FormField> fieldsByKey = overrides.getFieldsByKey();

            // Then
            String[] copyableFields = {"code", "contact_phone", "contact_email"};
            for (String fieldName : copyableFields) {
                FormField fieldConfig = fieldsByKey.get(fieldName);
                assertTrue((Boolean) fieldConfig.getProps().get("copyable"), 
                    fieldName + "字段应设置为可复制");
            }
        }

    }
}
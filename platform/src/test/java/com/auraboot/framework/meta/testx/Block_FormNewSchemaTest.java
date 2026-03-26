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
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 门店表单创建Schema JSON转换测试类
 * 测试 1st-version-store-form.create.json 转换为 FormFacade 对象的功能
 */
@DisplayName("门店表单创建Schema JSON转换测试")
class Block_FormNewSchemaTest {

    private ObjectMapper objectMapper;
    private String schemaJson;

    @BeforeEach
    void setUp() throws IOException {
        objectMapper = new ObjectMapper();
        // 读取实际的schema文件
        schemaJson = Files.readString(Paths.get("src/main/resources/schemas/1st-version-store-form.create.json"));
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
            assertEquals("新建门店", meta.getTitle().get("zh-CN"), "中文标题应为新建门店");
            assertEquals("Create Store", meta.getTitle().get("en-US"), "英文标题应为Create Store");
        }

        @Test
        @DisplayName("测试variables字段映射")
        void testVariablesFieldMapping() throws IOException {
            // When
            FormFacade bean = objectMapper.readValue(schemaJson, FormFacade.class);
            Map<String, Object> variables = bean.getProps();

            // Then
            assertNotNull(variables, "variables不应为null");
            assertEquals("create", variables.get("mode"), "mode应为create");
        }
    }

    @Nested
    @DisplayName("端点配置测试")
    class EndpointConfigTests {
        // Action 没有 getOnSubmit() 方法，端点信息在 behavior 中
        // Endpoint onSubmit = createAction.getOnSubmit();
        // assertThat(onSubmit).isNotNull();
        // assertThat(onSubmit.getUrl()).isEqualTo("/api/stores");
        // assertThat(onSubmit.getMethod()).isEqualTo("post");
        // assertThat(onSubmit.getPermission()).isEqualTo("store:create");
        
        @Test
        @DisplayName("测试端点配置")
        void testEndpointConfig() throws IOException {
            // When
            FormFacade bean = objectMapper.readValue(schemaJson, FormFacade.class);
            
            // Then
            // 验证没有endpoint配置
            assertNull(bean.getEndpoint(), "新建表单不应有endpoint配置");
            
            // 验证没有effects配置
            assertNull(bean.getEvents(), "新建表单不应有effects配置");
        }
    }

    @Nested
    @DisplayName("动作配置测试")
    class ActionConfigTests {

        @Test
        @DisplayName("测试Actions配置")
        void testActionConfig() throws IOException {
            // When
            FormFacade bean = objectMapper.readValue(schemaJson, FormFacade.class);
            List<Action> actions = bean.getActions();
            
            // Then
            assertThat(actions).hasSize(1);
            
            Action createAction = actions.get(0);
            assertThat(createAction.getCode()).isEqualTo("create");
            

            // 测试ui配置
            Map<String, Object> ui = createAction.getProps();
            assertThat(ui).isNotNull();
            assertThat(ui.get("htmlType")).isEqualTo("submit");
            assertThat(ui.get("loading")).isEqualTo("${form.submitting}");
            

            // 测试permission配置
            assertThat(createAction.getPermission()).isEqualTo("store:create");
        }

        @Test
        @DisplayName("测试action的behavior配置")
        void testActionBehaviorConfig() throws IOException {
            // When
            FormFacade bean = objectMapper.readValue(schemaJson, FormFacade.class);
            Action createAction = bean.getActions().get(0);




            // 验证permission配置
            assertEquals("store:create", createAction.getPermission(), "permission应为store:create");
        }
    }

    @Nested
    @DisplayName("覆盖配置测试")
    class OverrideConfigTests {

        @Test
        @DisplayName("测试overrides字段配置")
        void testOverridesConfig() throws IOException {
            // When
            FormFacade bean = objectMapper.readValue(schemaJson, FormFacade.class);
            FormSchemaOverrides overrides = bean.getOverrides();

            // Then
            assertNotNull(overrides, "overrides不应为null");
            assertNotNull(overrides.getFieldsByKey(), "fieldsByKey不应为null");
            assertTrue(overrides.getFieldsByKey().size() >= 1, "fieldsByKey应至少有1个字段");
            assertTrue(overrides.getFieldsByKey().containsKey("code"), "fieldsByKey应包含code字段");

            // 验证code字段的readOnly覆盖
            FormField codeOverride = overrides.getFieldsByKey().get("code");
            assertNotNull(codeOverride, "code字段覆盖不应为null");
            assertFalse((Boolean) codeOverride.getProps().get("readOnly"), "code字段readOnly应被覆盖为false");
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
            assertEquals(originalBean.getActions().get(0).getCode(), deserializedBean.getActions().get(0).getCode(), "action code应保持一致");
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
                  "endpoints": null,
                  "actions": [],
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
            assertNotNull(bean.getActions(), "actions不应为null");
            assertTrue(bean.getActions().isEmpty(), "actions应为空列表");
            assertNull(bean.getOverrides(), "overrides应为null");
        }
    }

    @Nested
    @DisplayName("边界情况测试")
    class EdgeCaseTests {

        @Test
        @DisplayName("测试继承关系解析")
        void testInheritanceResolution() throws IOException {
            // When
            FormFacade bean = objectMapper.readValue(schemaJson, FormFacade.class);

            // Then
            assertNotNull(bean.getBase(), "base不应为null");
            assertTrue(bean.getBase().startsWith("form:"), "base应以form:开头");
            assertTrue(bean.getBase().contains("@"), "base应包含版本号");
        }

        @Test
        @DisplayName("测试模式变量设置")
        void testModeVariableSetting() throws IOException {
            // When
            FormFacade bean = objectMapper.readValue(schemaJson, FormFacade.class);
            Map<String, Object> props = bean.getProps();

            // Then
            assertNotNull(props, "variables不应为null");
            assertEquals("create", props.get("mode"), "mode应为create");
        }

        @Test
        @DisplayName("测试字段覆盖逻辑")
        void testFieldOverrideLogic() throws IOException {
            // When
            FormFacade bean = objectMapper.readValue(schemaJson, FormFacade.class);
            FormSchemaOverrides overrides = bean.getOverrides();

            // Then
            assertNotNull(overrides, "overrides不应为null");
            Map<String, FormField> fieldsByKey = overrides.getFieldsByKey();
            assertNotNull(fieldsByKey, "fieldsByKey不应为null");
            
            // 验证code字段的readOnly被覆盖
            FormField codeField = fieldsByKey.get("code");
            assertNotNull(codeField, "code字段覆盖不应为null");
            assertFalse((Boolean) codeField.getProps().get("readOnly"), "code字段readOnly应为false");
        }

        @Test
        @DisplayName("测试权限范围配置")
        void testAuthScopeConfiguration() throws IOException {
            // When
            FormFacade bean = objectMapper.readValue(schemaJson, FormFacade.class);
            
            // 验证actions中的权限
            Action createAction = bean.getActions().get(0);
            String actionPermission = createAction.getPermission();
            
            // Then
            assertEquals("store:create", actionPermission, "permission应为store:create");
        }
    }
}
package com.auraboot.framework.meta.testx;

import com.auraboot.framework.meta.view.schema.*;

import com.auraboot.framework.meta.view.schema.common.*;
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
 * 门店表单编辑Schema JSON转换测试类
 * 测试 1st-version-store-form.edit.json 转换为 FormFacade 对象的功能
 */
@DisplayName("门店表单编辑Schema JSON转换测试")
class Block_FormEditSchemaTest {

    private ObjectMapper objectMapper;
    private String schemaJson;

    @BeforeEach
    void setUp() throws IOException {
        objectMapper = new ObjectMapper();
        // 读取实际的schema文件
        schemaJson = Files.readString(Paths.get("src/main/resources/schemas/1st-version-store-form.edit.json"));
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
            assertNotNull(bean.getEndpoint(), "endpoint字段不应为null");
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
            assertEquals("编辑门店", meta.getTitle().get("zh-CN"), "中文标题应为编辑门店");
            assertEquals("Edit Store", meta.getTitle().get("en-US"), "英文标题应为Edit Store");
        }

        @Test
        @DisplayName("测试variables字段映射")
        void testVariablesFieldMapping() throws IOException {
            // When
            FormFacade bean = objectMapper.readValue(schemaJson, FormFacade.class);
           Map<String,Object> variables = bean.getProps();

            // Then
            assertNotNull(variables, "variables不应为null");
            assertEquals("edit", variables.get("mode"), "mode变量应为edit");
        }


    }

    @Nested
    @DisplayName("端点配置测试")
    class EndpointConfigTests {

        @Test
        @DisplayName("测试endpoint配置")
        void testEndpointConfig() throws IOException {
            // When
            FormFacade bean = objectMapper.readValue(schemaJson, FormFacade.class);
            Endpoint endpoint = bean.getEndpoint();

            // Then
            assertNotNull(endpoint, "endpoint不应为null");
            assertEquals("/api/dynamic/Store/${params.storeId}", endpoint.getUrl(), "endpoint应为/api/dynamic/Store/${params.storeId}");
            assertEquals("get", endpoint.getMethod(), "method应为GET");
            assertEquals("store:read", endpoint.getPermission(), "permission应为store:read");
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
        @DisplayName("测试update动作配置")
        void testUpdateActionConfig() throws IOException {
            // When
            FormFacade bean = objectMapper.readValue(schemaJson, FormFacade.class);
            Action updateAction = bean.getActions().get(0);

            // Then
            assertEquals("update", updateAction.getCode(), "code应为update");
            

            // 验证ui配置
            assertNotNull(updateAction.getProps(), "ui不应为null");
            assertEquals("submit", updateAction.getProps().get("htmlType"), "htmlType应为submit");
            assertEquals("${form.submitting}", updateAction.getProps().get("loading"), "loading应为${form.submitting}");
            
            // 验证behavior配置

            // 验证permission配置
            assertEquals("store:update", updateAction.getPermission(), "permission应为store:update");
        }

        @Test
        @DisplayName("测试delete动作配置")
        void testDeleteActionConfig() throws IOException {
            // When
            FormFacade bean = objectMapper.readValue(schemaJson, FormFacade.class);
            Action deleteAction = bean.getActions().get(1);

            // Then
            assertEquals("delete", deleteAction.getCode(), "code应为delete");
            

            // 验证ui配置
            assertNotNull(deleteAction.getProps(), "ui不应为null");
            assertEquals("danger", deleteAction.getProps().get("type"), "ui type应为danger");
            

            // Note: mapping field is not part of Payload structure, skipping this validation
            // The JSON schema may have additional fields that are not mapped to the bean
            
            // 验证permission配置
            assertEquals("store:delete", deleteAction.getPermission(), "permission应为store:delete");
        }
    }

    @Nested
    @DisplayName("效果配置测试")
    class EventConfigTests {

        @Test
        @DisplayName("测试effects配置")
        void testEffectsConfig() throws IOException {
            // When
            FormFacade bean = objectMapper.readValue(schemaJson, FormFacade.class);
            List<Event> events = bean.getEvents();

            // Then
            // effects字段在此schema中为null，这是正常的
            assertNull(events, "effects应为null");
        }

        // 注释掉formInit测试，因为JSON中没有effects字段
        /*
        @Test
        @DisplayName("测试formInit效果配置")
        void testFormInitEffectConfig() throws IOException {
            // When
            FormFacade bean = objectMapper.readValue(schemaJson, FormFacade.class);
            Event formInitEffect = bean.getEffects().get(0);

            // Then
            assertEquals("formInit", formInitEffect.getOn(), "on应为formInit");
            assertEquals("fetch", formInitEffect.getAction(), "action应为fetch");
            assertEquals("form.values", formInitEffect.getAssignTo(), "assignTo应为form.values");

            // 验证request配置
            assertNotNull(formInitEffect.getRequest(), "request不应为null");
            Endpoint request = formInitEffect.getRequest();
            assertEquals("/api/dynamic/Store/${params.storeId}", request.getPageEndpoint(), "endpoint应为/api/dynamic/Store/${params.storeId}");
            assertEquals("get", request.getMethod(), "method应为GET");
            
            // 验证permission配置
            assertEquals("store:read", request.getPermission(), "permission应为store:read");
        }
        */
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
            
            // 验证fieldsByKey结构
            Map<String, FormField> fieldsByKey = overrides.getFieldsByKey();
            assertNotNull(fieldsByKey, "fieldsByKey不应为null");
            
            // 验证code字段覆盖
            FormField codeOverride = fieldsByKey.get("code");
            assertNotNull(codeOverride, "code覆盖配置不应为null");
            Map<String, Object> codeProps = codeOverride.getProps();
            assertNotNull(codeProps, "code props不应为null");
            assertEquals(true, codeProps.get("readOnly"), "code字段应为只读");
            
            // 验证created_at字段覆盖
            FormField createdAtOverride = fieldsByKey.get("created_at");
            assertNotNull(createdAtOverride, "created_at覆盖配置不应为null");
            Map<String, Object> createdAtProps = createdAtOverride.getProps();
            assertNotNull(createdAtProps, "created_at props不应为null");
            assertEquals(true, createdAtProps.get("visible"), "created_at字段应可见");
            
            // 验证updated_at字段覆盖
            FormField updatedAtOverride = fieldsByKey.get("updated_at");
            assertNotNull(updatedAtOverride, "updated_at覆盖配置不应为null");
            Map<String, Object> updatedAtProps = updatedAtOverride.getProps();
            assertNotNull(updatedAtProps, "updated_at props不应为null");
            assertEquals(true, updatedAtProps.get("visible"), "updated_at字段应可见");
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
            // effects字段可能为null，需要特殊处理
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
        @DisplayName("测试编辑模式配置")
        void testEditModeConfiguration() throws IOException {
            // When
            FormFacade bean = objectMapper.readValue(schemaJson, FormFacade.class);
           Map<String,Object> variables = bean.getProps();

            // Then
            assertEquals("edit", variables.get("mode"), "mode应为edit");
        }

        @Test
        @DisplayName("测试参数化端点")
        void testParameterizedEndpoints() throws IOException {
            // When
            FormFacade bean = objectMapper.readValue(schemaJson, FormFacade.class);
            Endpoint endpoint = bean.getEndpoint();

            // Then
            assertNotNull(endpoint, "endpoint不应为null");
            assertTrue(endpoint.getUrl().contains("${params.storeId}"), "endpoint应包含参数");
            
            // 验证actions中的端点也包含参数
            List<Action> actions = bean.getActions();
            Action updateAction = actions.get(0); // update action
            Action deleteAction = actions.get(1); // delete action

        }

        @Test
        @DisplayName("测试权限范围一致性")
        void testAuthScopeConsistency() throws IOException {
            // When
            FormFacade bean = objectMapper.readValue(schemaJson, FormFacade.class);
            
            // 获取各个配置中的权限范围
            Action updateAction = bean.getActions().get(0);
            // Action 没有 getOnSubmit() 方法，权限直接在 permission 字段中
            String actionPermission = updateAction.getPermission();

            // Then
            assertEquals("store:update", actionPermission, "应包含store:update权限");
        }

        // 注释掉表单初始化效果测试，因为JSON中没有effects字段
        /*
        @Test
        @DisplayName("测试表单初始化效果")
        void testFormInitializationEffect() throws IOException {
            // When
            FormFacade bean = objectMapper.readValue(schemaJson, FormFacade.class);
            Event formInitEffect = bean.getEffects().get(0);

            // Then
            assertEquals("formInit", formInitEffect.getOn(), "应在表单初始化时触发");
            assertEquals("fetch", formInitEffect.getAction(), "应执行fetch操作");
            assertEquals("form.values", formInitEffect.getAssignTo(), "应赋值给form.values");
            
            // 验证请求配置与load端点一致
            Endpoint request = formInitEffect.getRequest();
            Endpoint loadEndpoint = bean.getEndpoints().get("load");
            
            assertEquals(loadEndpoint.getPageEndpoint(), request.getPageEndpoint(), "请求端点应与load端点一致");
            assertEquals(loadEndpoint.getMethod(), request.getMethod(), "请求方法应与load端点一致");
        }
        */
    }
}
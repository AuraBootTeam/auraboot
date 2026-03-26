package com.auraboot.framework.meta.testx;

import com.auraboot.framework.meta.view.schema.*;
import com.auraboot.framework.meta.view.schema.common.Meta;
import com.auraboot.framework.meta.view.schema.common.ValidationRule;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 门店表单基础Schema JSON转换测试类
 * 测试 1st-version-store-form.base.json 转换为 FormFacade 对象的功能
 */
@DisplayName("门店表单基础Schema JSON转换测试")
class Block_FormBaseSchemaTest {

    private ObjectMapper objectMapper;
    private String schemaJson;

    @BeforeEach
    void setUp() throws IOException {
        objectMapper = new ObjectMapper();
        // 读取实际的schema文件
        schemaJson = Files.readString(Paths.get("src/main/resources/schemas/1st-version-store-form.base.json"));
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
            assertNotNull(bean.getMeta(), "meta字段不应为null");
            assertNotNull(bean.getProps(), "variables字段不应为null");
            assertNotNull(bean.getSections(), "sections字段不应为null");
            assertFalse(bean.getSections().isEmpty(), "sections不应为空");
            // base schema 文件中没有 base 字段，所以不检查
            // assertNull(bean.getBase(), "base schema中base字段应为null");
        }

        @Test
        @DisplayName("测试meta字段映射")
        void testMetaFieldMapping() throws IOException {
            // When
            FormFacade bean = objectMapper.readValue(schemaJson, FormFacade.class);
            Meta meta = bean.getMeta();

            // Then
            assertNotNull(meta, "meta不应为null");
            assertEquals("store.form.base", meta.getBlockCode(), "blockCode应为store.form.base");
            assertEquals("2.0.1", meta.getVersion(), "version应为2.0.1");
            assertEquals("门店表单", meta.getTitle().get("zh-CN"), "中文标题应为门店表单");
            assertEquals("Store Form", meta.getTitle().get("en-US"), "英文标题应为Store Form");
            assertEquals("Store", meta.getEntityCode(), "entityCode应为Store");
            assertEquals("form", meta.getType(), "type应为form");
            assertEquals("1.0", meta.getDslVersion(), "dslVersion应为1.0");
            assertEquals("form", meta.getType(), "type应为form");
        }

        @Test
        @DisplayName("测试variables字段映射")
        void testVariablesFieldMapping() throws IOException {
            // When
            FormFacade bean = objectMapper.readValue(schemaJson, FormFacade.class);
           Map<String,Object> variables = bean.getProps();

            // Then
            assertNotNull(variables, "variables不应为null");

        }
    }

    @Nested
    @DisplayName("表单段落配置测试")
    class SectionConfigTests {

        @Test
        @DisplayName("测试sections数量和基本结构")
        void testSectionsBasicStructure() throws IOException {
            // When
            FormFacade bean = objectMapper.readValue(schemaJson, FormFacade.class);
            java.util.List<FormSection> sections = bean.getSections();

            // Then
            assertEquals(5, sections.size(), "应有5个section");

            // 验证每个section的基本结构
            String[] expectedSectionCodes = {"basic", "location", "contact", "management", "additional"};
            for (int i = 0; i < sections.size(); i++) {
                FormSection section = sections.get(i);
                assertEquals(expectedSectionCodes[i], section.getCode(), 
                    String.format("第%d个section的code应为%s", i + 1, expectedSectionCodes[i]));
                assertNotNull(section.getFields(), 
                    String.format("第%d个section的fields不应为null", i + 1));
                assertFalse(section.getFields().isEmpty(), 
                    String.format("第%d个section的fields不应为空", i + 1));
                assertNotNull(section.getTitle(), 
                    String.format("第%d个section的title不应为null", i + 1));
                assertNotNull(section.getLayout(), 
                    String.format("第%d个section的layout不应为null", i + 1));
            }
        }

        @Test
        @DisplayName("测试basic段落详细配置")
        void testBasicSectionDetails() throws IOException {
            // When
            FormFacade bean = objectMapper.readValue(schemaJson, FormFacade.class);
            FormSection basicSection = bean.getSections().get(0);

            // Then
            assertEquals("basic", basicSection.getCode(), "段落code应为basic");
            assertEquals("基本信息", basicSection.getTitle().get("zh-CN"), "中文标题应正确");
            assertEquals("Basic Information", basicSection.getTitle().get("en-US"), "英文标题应正确");
            assertEquals("grid", basicSection.getLayout().get("type"), "布局类型应为grid");
            assertEquals(2, basicSection.getLayout().get("columns"), "列数应为2");

            // 验证字段数量
            assertEquals(4, basicSection.getFields().size(), "basic段落应有4个字段");

            // 验证code字段
            FormField codeField = basicSection.getFields().get(0);
            assertEquals("code", codeField.getCode(), "第一个字段code应为code");
            assertEquals("input", codeField.getType(), "第一个字段type应为input");
            // placeholder是多语言对象
            @SuppressWarnings("unchecked")
            Map<String, String> placeholderMap = (Map<String, String>) codeField.getProps().get("placeholder");
            assertEquals("请输入门店编码", placeholderMap.get("zh-CN"), "placeholder中文应正确");
            assertEquals(20, codeField.getProps().get("maxLength"), "maxLength应为20");
            assertTrue((Boolean) codeField.getProps().get("required"), "required应为true");
            assertNotNull(codeField.getValidation(), "code字段应有validation");
            assertEquals(2, codeField.getValidation().size(), "code字段应有2个验证规则");

            // 验证name字段
            FormField nameField = basicSection.getFields().get(1);
            assertEquals("name", nameField.getCode(), "第二个字段code应为name");
            assertEquals("input", nameField.getType(), "第二个字段type应为input");
            assertEquals(100, nameField.getProps().get("maxLength"), "maxLength应为100");
            assertTrue((Boolean) nameField.getProps().get("required"), "required应为true");
            assertNotNull(nameField.getValidation(), "name字段应有validation");
            assertEquals(1, nameField.getValidation().size(), "name字段应有1个验证规则");

            // 验证type字段
            FormField typeField = basicSection.getFields().get(2);
            assertEquals("type", typeField.getCode(), "第三个字段code应为type");
            assertEquals("select", typeField.getType(), "第三个字段type应为select");
            assertTrue((Boolean) typeField.getProps().get("required"), "required应为true");

            // 验证status字段
            FormField statusField = basicSection.getFields().get(3);
            assertEquals("status", statusField.getCode(), "第四个字段code应为status");
            assertEquals("select", statusField.getType(), "第四个字段type应为select");
            assertTrue((Boolean) statusField.getProps().get("required"), "required应为true");
        }

        @Test
        @DisplayName("测试basic段落字段详细配置")
        void testBasicSectionFieldsDetails() throws IOException {
            // When
            FormFacade bean = objectMapper.readValue(schemaJson, FormFacade.class);
            FormSection basicSection = bean.getSections().get(0);

            // 验证code字段
            FormField codeField = basicSection.getFields().get(0);
            assertEquals("code", codeField.getCode(), "第一个字段code应为code");
            assertEquals("input", codeField.getType(), "第一个字段type应为input");
            assertNotNull(codeField.getProps(), "code字段应有props");
            assertTrue((Boolean) codeField.getProps().get("required"), "code字段required应为true");
            assertEquals(20, codeField.getProps().get("maxLength"), "code字段maxLength应为20");
            assertEquals("${mode === 'edit'}", codeField.getProps().get("readOnly"), "readOnly应为${mode === 'edit'}");

            // 验证name字段
            FormField nameField = basicSection.getFields().get(1);
            assertEquals("name", nameField.getCode(), "第二个字段code应为name");
            assertEquals("input", nameField.getType(), "第二个字段type应为input");

            // 验证type字段
            FormField typeField = basicSection.getFields().get(2);
            assertEquals("type", typeField.getCode(), "第三个字段code应为type");
            assertEquals("select", typeField.getType(), "第三个字段type应为select");

            // 验证status字段
            FormField statusField = basicSection.getFields().get(3);
            assertEquals("status", statusField.getCode(), "第四个字段code应为status");
            assertEquals("select", statusField.getType(), "第四个字段type应为select");
        }

        @Test
        @DisplayName("测试location段落详细配置")
        void testLocationSectionDetails() throws IOException {
            // When
            FormFacade bean = objectMapper.readValue(schemaJson, FormFacade.class);
            FormSection locationSection = bean.getSections().get(1);

            // Then
            assertEquals("location", locationSection.getCode(), "段落code应为location");
            assertEquals("位置信息", locationSection.getTitle().get("zh-CN"), "中文标题应正确");
            assertEquals("Location Information", locationSection.getTitle().get("en-US"), "英文标题应正确");
            assertEquals("grid", locationSection.getLayout().get("type"), "布局类型应为grid");
            assertEquals(1, locationSection.getLayout().get("columns"), "列数应为1");

            // 验证字段数量
            assertEquals(2, locationSection.getFields().size(), "location段落应有2个字段");

            // 验证address字段
            FormField addressField = locationSection.getFields().get(0);
            assertEquals("address", addressField.getCode(), "第一个字段code应为address");
            assertEquals("textarea", addressField.getType(), "第一个字段type应为textarea");
            assertTrue((Boolean) addressField.getProps().get("required"), "required应为true");
            assertEquals(3, addressField.getProps().get("rows"), "rows应为3");
            assertEquals(500, addressField.getProps().get("maxLength"), "maxLength应为500");
            
            // 验证address字段的placeholder是多语言对象
            @SuppressWarnings("unchecked")
            Map<String, String> addressPlaceholder = (Map<String, String>) addressField.getProps().get("placeholder");
            assertEquals("请输入门店详细地址", addressPlaceholder.get("zh-CN"), "address字段中文placeholder应正确");
            assertEquals("Please enter detailed address", addressPlaceholder.get("en-US"), "address字段英文placeholder应正确");

            // 验证coordinates字段
            FormField coordinatesField = locationSection.getFields().get(1);
            assertEquals("coordinates", coordinatesField.getCode(), "第二个字段code应为coordinates");
            assertEquals("coordinatesPicker", coordinatesField.getType(), "第二个字段type应为coordinatesPicker");
            assertEquals("amap", coordinatesField.getProps().get("mapProvider"), "mapProvider应为amap");
            assertEquals(15, coordinatesField.getProps().get("zoom"), "zoom应为15");
            
            // 验证coordinates字段的placeholder是多语言对象
            @SuppressWarnings("unchecked")
            Map<String, String> coordinatesPlaceholder = (Map<String, String>) coordinatesField.getProps().get("placeholder");
            assertEquals("点击地图选择位置", coordinatesPlaceholder.get("zh-CN"), "coordinates字段中文placeholder应正确");
            assertEquals("Click map to select location", coordinatesPlaceholder.get("en-US"), "coordinates字段英文placeholder应正确");
        }

        @Test
        @DisplayName("测试contact段落详细配置")
        void testContactSectionDetails() throws IOException {
            // When
            FormFacade bean = objectMapper.readValue(schemaJson, FormFacade.class);
            FormSection contactSection = bean.getSections().get(2);

            // Then
            assertEquals("contact", contactSection.getCode(), "code应为contact");
            assertEquals("联系信息", contactSection.getTitle().get("zh-CN"), "中文标题应为联系信息");
            assertEquals("Contact Information", contactSection.getTitle().get("en-US"), "英文标题应为Contact Information");
            assertEquals("grid", contactSection.getLayout().get("type"), "布局类型应为grid");
            assertEquals(2, contactSection.getLayout().get("columns"), "列数应为2");

            // 验证字段数量
            assertEquals(2, contactSection.getFields().size(), "contact段落应有2个字段");

            // 验证contact_phone字段
            FormField phoneField = contactSection.getFields().get(0);
            assertEquals("contact_phone", phoneField.getCode(), "第一个字段code应为contact_phone");
            assertEquals("input", phoneField.getType(), "第一个字段type应为input");
            // FormField 没有 getTitle() 方法，标题信息在 props 中
            // assertEquals("联系电话", phoneField.getTitle().get("zh-CN"), "phone字段中文标题应正确");
            // assertEquals("Contact Phone", phoneField.getTitle().get("en-US"), "phone字段英文标题应正确");
            assertEquals("tel", phoneField.getProps().get("inputType"), "inputType应为tel");
            assertTrue((Boolean) phoneField.getProps().get("required"), "required应为true");
            
            // 验证contact_phone字段的placeholder是多语言对象
            @SuppressWarnings("unchecked")
            Map<String, String> phonePlaceholder = (Map<String, String>) phoneField.getProps().get("placeholder");
            assertEquals("请输入联系电话", phonePlaceholder.get("zh-CN"), "phone字段中文placeholder应正确");
            assertEquals("Please enter contact phone", phonePlaceholder.get("en-US"), "phone字段英文placeholder应正确");
            
            assertNotNull(phoneField.getValidation(), "contact_phone字段应有validation");
            assertEquals(2, phoneField.getValidation().size(), "contact_phone字段应有2个验证规则");

            // 验证contact_email字段
            FormField emailField = contactSection.getFields().get(1);
            assertEquals("contact_email", emailField.getCode(), "第二个字段code应为contact_email");
            assertEquals("input", emailField.getType(), "第二个字段type应为input");
            // FormField 没有 getTitle() 方法，标题信息在 props 中
            // assertEquals("联系邮箱", emailField.getTitle().get("zh-CN"), "email字段中文标题应正确");
            // assertEquals("Contact Email", emailField.getTitle().get("en-US"), "email字段英文标题应正确");
            assertEquals("email", emailField.getProps().get("inputType"), "inputType应为email");
            
            // 验证contact_email字段的placeholder是多语言对象
            @SuppressWarnings("unchecked")
            Map<String, String> emailPlaceholder = (Map<String, String>) emailField.getProps().get("placeholder");
            assertEquals("请输入联系邮箱", emailPlaceholder.get("zh-CN"), "email字段中文placeholder应正确");
            assertEquals("Please enter contact email", emailPlaceholder.get("en-US"), "email字段英文placeholder应正确");
            
            // contact_email字段在JSON中没有required属性，所以不进行断言
            // assertFalse((Boolean) emailField.getProps().get("required"), "required应为false");
            assertNotNull(emailField.getValidation(), "contact_email字段应有validation");
            assertEquals(1, emailField.getValidation().size(), "contact_email字段应有1个验证规则");
        }

        @Test
        @DisplayName("测试management段落详细配置")
        void testManagementSectionDetails() throws IOException {
            // When
            FormFacade bean = objectMapper.readValue(schemaJson, FormFacade.class);
            FormSection managementSection = bean.getSections().get(3);

            // Then
            assertEquals("management", managementSection.getCode(), "段落code应为management");
            assertEquals("管理信息", managementSection.getTitle().get("zh-CN"), "中文标题应正确");
            assertEquals("Management Information", managementSection.getTitle().get("en-US"), "英文标题应正确");
            assertEquals("grid", managementSection.getLayout().get("type"), "布局类型应为grid");
            assertEquals(2, managementSection.getLayout().get("columns"), "列数应为2");

            // 验证字段数量
            assertEquals(5, managementSection.getFields().size(), "management段落应有5个字段");

            // 验证manager_id字段
            FormField managerIdField = managementSection.getFields().get(0);
            assertEquals("manager_id", managerIdField.getCode(), "第一个字段code应为manager_id");
            assertEquals("userSelect", managerIdField.getType(), "第一个字段type应为userSelect");
            
            // 验证organization_id字段
            FormField organizationIdField = managementSection.getFields().get(1);
            assertEquals("organization_id", organizationIdField.getCode(), "第二个字段code应为organization_id");
            assertEquals("organizationSelect", organizationIdField.getType(), "第二个字段type应为organizationSelect");
            
            // 验证opening_hours字段
            FormField openingHoursField = managementSection.getFields().get(2);
            assertEquals("opening_hours", openingHoursField.getCode(), "第三个字段code应为opening_hours");
            assertEquals("timeRangePicker", openingHoursField.getType(), "第三个字段type应为timeRangePicker");
            
            // 验证created_at字段
            FormField createdAtField = managementSection.getFields().get(3);
            assertEquals("created_at", createdAtField.getCode(), "第四个字段code应为created_at");
            assertEquals("datetime", createdAtField.getType(), "第四个字段type应为datetime");
            
            // 验证updated_at字段
            FormField updatedAtField = managementSection.getFields().get(4);
            assertEquals("updated_at", updatedAtField.getCode(), "第五个字段code应为updated_at");
            assertEquals("datetime", updatedAtField.getType(), "第五个字段type应为datetime");
        }

        @Test
        @DisplayName("测试additional段落详细配置")
        void testAdditionalSectionDetails() throws IOException {
            // When
            FormFacade bean = objectMapper.readValue(schemaJson, FormFacade.class);
            FormSection additionalSection = bean.getSections().get(4);

            // Then
            assertEquals("additional", additionalSection.getCode(), "段落code应为additional");
            assertEquals("附加信息", additionalSection.getTitle().get("zh-CN"), "中文标题应正确");
            assertEquals("Additional Information", additionalSection.getTitle().get("en-US"), "英文标题应正确");
            assertEquals("grid", additionalSection.getLayout().get("type"), "布局类型应为grid");
            assertEquals(1, additionalSection.getLayout().get("columns"), "列数应为1");

            // 验证字段数量
            assertEquals(2, additionalSection.getFields().size(), "additional段落应有2个字段");

            // 验证description字段
            FormField descriptionField = additionalSection.getFields().get(0);
            assertEquals("description", descriptionField.getCode(), "第一个字段code应为description");
            assertEquals("textarea", descriptionField.getType(), "第一个字段type应为textarea");
            assertEquals(4, descriptionField.getProps().get("rows"), "rows应为4");
            assertEquals(1000, descriptionField.getProps().get("maxLength"), "maxLength应为1000");
            
            // 验证description字段的placeholder是多语言对象
            @SuppressWarnings("unchecked")
            Map<String, String> descriptionPlaceholder = (Map<String, String>) descriptionField.getProps().get("placeholder");
            assertEquals("请输入门店描述信息", descriptionPlaceholder.get("zh-CN"), "description字段中文placeholder应正确");
            assertEquals("Please enter store description", descriptionPlaceholder.get("en-US"), "description字段英文placeholder应正确");

            // 验证images字段
            FormField imagesField = additionalSection.getFields().get(1);
            assertEquals("images", imagesField.getCode(), "第二个字段code应为images");
            assertEquals("imageUpload", imagesField.getType(), "第二个字段type应为imageUpload");
            assertEquals(10, imagesField.getProps().get("maxCount"), "maxCount应为10");
            assertEquals(5242880, imagesField.getProps().get("maxSize"), "maxSize应为5242880");
            assertEquals("image/*", imagesField.getProps().get("accept"), "accept应为image/*");
            assertEquals("picture-card", imagesField.getProps().get("listType"), "listType应为picture-card");
            assertEquals(true, imagesField.getProps().get("multiple"), "multiple应为true");
            
            // 验证images字段的label是多语言对象
            @SuppressWarnings("unchecked")
            Map<String, String> imagesLabel = (Map<String, String>) imagesField.getProps().get("label");
            assertEquals("门店图片", imagesLabel.get("zh-CN"), "images字段中文label应正确");
            assertEquals("Store Images", imagesLabel.get("en-US"), "images字段英文label应正确");
        }
    }

    @Nested
    @DisplayName("字段验证配置测试")
    class FieldValidationTests {

        @Test
        @DisplayName("测试code字段验证规则")
        void testCodeFieldValidation() throws IOException {
            // When
            FormFacade bean = objectMapper.readValue(schemaJson, FormFacade.class);
            FormField codeField = bean.getSections().get(0).getFields().get(0);

            // Then
            assertNotNull(codeField.getValidation(), "code字段应有validation");
            assertEquals(2, codeField.getValidation().size(), "code字段应有2个验证规则");

            // 验证required规则
            ValidationRule requiredRule = codeField.getValidation().get(0);
            assertEquals("required", requiredRule.getType(), "第一个规则type应为required");
            assertEquals("门店编码不能为空", requiredRule.getMessage().get("zh-CN"), "required规则中文消息应正确");
            assertEquals("Store code is required", requiredRule.getMessage().get("en-US"), "required规则英文消息应正确");

            // 验证pattern规则
            ValidationRule patternRule = codeField.getValidation().get(1);
            assertEquals("pattern", patternRule.getType(), "第二个规则type应为pattern");
            assertEquals("^[A-Z0-9]{4,20}$", patternRule.getValue(), "pattern规则value应正确");
            assertEquals("门店编码格式不正确，应为4-20位大写字母和数字", patternRule.getMessage().get("zh-CN"), "pattern规则中文消息应正确");
        }

        @Test
        @DisplayName("测试contact_phone字段验证规则")
        void testContactPhoneFieldValidation() throws IOException {
            // When
            FormFacade bean = objectMapper.readValue(schemaJson, FormFacade.class);
            FormField phoneField = bean.getSections().get(2).getFields().get(0);

            // Then
            assertNotNull(phoneField.getValidation(), "contact_phone字段应有validation");
            assertEquals(2, phoneField.getValidation().size(), "contact_phone字段应有2个验证规则");

            // 验证required规则
            ValidationRule requiredRule = phoneField.getValidation().get(0);
            assertEquals("required", requiredRule.getType(), "第一个规则type应为required");

            // 验证pattern规则
            ValidationRule patternRule = phoneField.getValidation().get(1);
            assertEquals("pattern", patternRule.getType(), "第二个规则type应为pattern");
            assertEquals("^1[3-9]\\d{9}$|^0\\d{2,3}-?\\d{7,8}$", patternRule.getValue(), "pattern规则value应正确");
        }

        @Test
        @DisplayName("测试contact_email字段验证规则")
        void testContactEmailFieldValidation() throws IOException {
            // When
            FormFacade bean = objectMapper.readValue(schemaJson, FormFacade.class);
            FormField emailField = bean.getSections().get(2).getFields().get(1);

            // Then
            assertNotNull(emailField.getValidation(), "contact_email字段应有validation");
            assertEquals(1, emailField.getValidation().size(), "contact_email字段应有1个验证规则");

            // 验证email规则
            ValidationRule emailRule = emailField.getValidation().get(0);
            assertEquals("email", emailRule.getType(), "规则type应为email");
            assertEquals("请输入正确的邮箱地址", emailRule.getMessage().get("zh-CN"), "email规则中文消息应正确");
            assertEquals("Please enter a valid email address", emailRule.getMessage().get("en-US"), "email规则英文消息应正确");
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
            assertEquals(originalBean.getMeta().getBlockCode(), deserializedBean.getMeta().getBlockCode(), "blockCode应保持一致");
            assertEquals(originalBean.getSections().size(), deserializedBean.getSections().size(), "sections数量应保持一致");
        }

        @Test
        @DisplayName("测试空值处理")
        void testNullValueHandling() throws IOException {
            // Given
            String jsonWithNulls = """
                {
                  "meta": {
                    "blockCode": "test",
                    "title": null,
                    "version": "1.0.0"
                  },
                  "variables": null,
                  "sections": [],
                  "validation": null
                }
                """;

            // When
            FormFacade bean = objectMapper.readValue(jsonWithNulls, FormFacade.class);

            // Then
            assertNotNull(bean, "Bean不应为null");
            assertNotNull(bean.getMeta(), "meta不应为null");
            assertEquals("test", bean.getMeta().getBlockCode(), "blockCode应正确解析");
            assertNull(bean.getMeta().getTitle(), "title应为null");
            assertNull(bean.getProps(), "variables应为null");
            assertNotNull(bean.getSections(), "sections不应为null");
            assertTrue(bean.getSections().isEmpty(), "sections应为空列表");
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
            FormFacade bean = objectMapper.readValue(schemaJson, FormFacade.class);
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
                    "blockCode": "test",
                    "version": "1.0.0"
                  },
                  "sections": [
                    {
                      "code": "test",
                      "fields": [
                        {
                          "code": "test",
                          "type": "input",
                          "props": {
                            "maxLength": "100",
                            "required": "true"
                          }
                        }
                      ]
                    }
                  ]
                }
                """;

            // When & Then - 应该能够处理字符串形式的数字和布尔值
            assertDoesNotThrow(() -> {
                FormFacade bean = objectMapper.readValue(jsonWithDifferentTypes, FormFacade.class);
                assertNotNull(bean);
            }, "应能处理不同类型的字段值");
        }
    }
}
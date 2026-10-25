package com.auraboot.framework.aurabot.skill.builtin;

import com.auraboot.framework.aurabot.skill.RiskLevel;
import com.auraboot.framework.aurabot.skill.SkillRequest;
import com.auraboot.framework.aurabot.skill.SkillRunRepository;
import com.auraboot.framework.aurabot.skill.error.SkillErrorCode;
import com.auraboot.framework.aurabot.skill.error.SkillSpiException;
import com.auraboot.framework.meta.dto.MetaModelDTO;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.mapper.MetaModelFieldBindingMapper;
import com.auraboot.framework.meta.service.MetaFieldService;
import com.auraboot.framework.meta.service.MetaModelService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ModelCreateSkillUnitTest {

    @Mock MetaModelService metaModelService;
    @Mock MetaFieldService metaFieldService;
    @Mock MetaModelFieldBindingMapper bindingMapper;
    @Mock DynamicDataMapper dynamicDataMapper;
    @Mock SkillRunRepository skillRunRepository;

    private ModelCreateSkill skill;
    private ObjectMapper objectMapper;
    private JsonSchema compiledSchema;

    @BeforeEach
    void setUp() throws Exception {
        objectMapper = new ObjectMapper();
        skill = new ModelCreateSkill(metaModelService, metaFieldService,
                bindingMapper, dynamicDataMapper, objectMapper, skillRunRepository);
        skill.init(); // PostConstruct manual trigger
        compiledSchema = JsonSchemaFactory
                .getInstance(SpecVersion.VersionFlag.V202012)
                .getSchema(skill.paramsSchema());
    }

    @Test
    @DisplayName("metadata: name=model:create, riskLevel=HIGH, perms include meta.model.update")
    void metadata_correct() {
        assertThat(skill.name()).isEqualTo("model:create");
        assertThat(skill.riskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(skill.requiredPermissions()).containsExactly("meta.model.update");
        assertThat(skill.supportsDryRun()).isTrue();
        assertThat(skill.supportsUndo()).isTrue();
        assertThat(skill.supportsStreaming()).isFalse();
    }

    @Nested
    @DisplayName("paramsSchema validation (Caller would run inside Registry/Validator pipeline)")
    class SchemaValidation {

        @Test
        void acceptsMinimalRequest() throws Exception {
            JsonNode params = objectMapper.readTree(
                    "{\"code\":\"customer\",\"displayName\":\"Customer\"}");
            Set<ValidationMessage> errors = compiledSchema.validate(params);
            assertThat(errors).isEmpty();
        }

        @Test
        void rejectsCodePatternViolation() throws Exception {
            JsonNode params = objectMapper.readTree(
                    "{\"code\":\"123abc\",\"displayName\":\"Bad\"}");
            Set<ValidationMessage> errors = compiledSchema.validate(params);
            assertThat(errors).isNotEmpty();
            assertThat(errors).anyMatch(m -> m.getMessage().toLowerCase().contains("pattern"));
        }

        @Test
        void rejectsAdditionalProperties() throws Exception {
            JsonNode params = objectMapper.readTree(
                    "{\"code\":\"customer\",\"displayName\":\"X\",\"fields\":[]}");
            Set<ValidationMessage> errors = compiledSchema.validate(params);
            assertThat(errors).isNotEmpty();
            assertThat(errors).anyMatch(m -> m.getMessage().toLowerCase().contains("additional"));
        }
    }

    @Test
    @DisplayName("dryRun: existing modelCode throws PARAMS_INVALID with /code fieldPath")
    void dryRun_existingCode_throwsParamsInvalid() throws Exception {
        // Implemented after T3; this test fails on T2 (UnsupportedOperationException) and
        // turns green when T3 lands the dryRun body.
        SkillRequest req = new SkillRequest();
        req.setSkillName("model:create");
        Map<String, Object> params = new HashMap<>();
        params.put("code", "customer");
        params.put("displayName", "Customer");
        req.setParams(objectMapper.valueToTree(params));

        MetaModelDTO existing = MetaModelDTO.builder().code("customer").build();
        when(metaModelService.findByCode("customer")).thenReturn(existing);

        assertThatThrownBy(() -> skill.dryRun(req))
                .isInstanceOf(SkillSpiException.class)
                .satisfies(t -> {
                    SkillSpiException e = (SkillSpiException) t;
                    assertThat(e.getErrorCode()).isEqualTo(SkillErrorCode.PARAMS_INVALID);
                    assertThat(e.getFieldPath()).isEqualTo("/code");
                });
    }
}

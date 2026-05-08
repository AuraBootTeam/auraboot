package com.auraboot.framework.aurabot.skill.builtin;

import com.auraboot.framework.aurabot.skill.RiskLevel;
import com.auraboot.framework.aurabot.skill.SkillRequest;
import com.auraboot.framework.aurabot.skill.SkillRunRepository;
import com.auraboot.framework.aurabot.skill.error.SkillErrorCode;
import com.auraboot.framework.aurabot.skill.error.SkillSpiException;
import com.auraboot.framework.exception.ValidationException;
import com.auraboot.framework.common.constant.ResponseCode;
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

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link FieldAddSkill} (Plan §Task 6 / §Task 7).
 *
 * <p>Six cases:
 * <ul>
 *   <li>{@link #metadata_correct()} — name / category / risk / permissions /
 *   undo + dryRun support flags.</li>
 *   <li>{@link SchemaValidation#acceptsMinimal} — minimum required keys
 *   pass.</li>
 *   <li>{@link SchemaValidation#rejectsCodePattern} — bad code pattern
 *   rejected.</li>
 *   <li>{@link SchemaValidation#rejectsAdditional} — extra keys rejected
 *   (additionalProperties:false).</li>
 *   <li>{@link SchemaValidation#rejectsInvalidDataType} — out-of-enum type
 *   rejected.</li>
 *   <li>{@link #dryRun_unknownModel_throwsParamsInvalid()} — RED until T7;
 *   dryRun must surface PARAMS_INVALID at /modelCode when MetaModelService
 *   says model doesn't exist.</li>
 * </ul>
 *
 * <p>No Spring context — pure Mockito + ObjectMapper.
 */
@DisplayName("FieldAddSkill — unit (no Spring)")
class FieldAddSkillUnitTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private MetaFieldService metaFieldService;
    private MetaModelService metaModelService;
    private SkillRunRepository skillRunRepository;
    private FieldAddSkill skill;

    @BeforeEach
    void setUp() throws Exception {
        metaFieldService = mock(MetaFieldService.class);
        metaModelService = mock(MetaModelService.class);
        skillRunRepository = mock(SkillRunRepository.class);
        skill = new FieldAddSkill(metaFieldService, metaModelService, MAPPER, skillRunRepository);
        // @PostConstruct is private package-level — invoke directly.
        java.lang.reflect.Method init = FieldAddSkill.class.getDeclaredMethod("init");
        init.setAccessible(true);
        init.invoke(skill);
    }

    @Test
    @DisplayName("metadata: name=field:add, MEDIUM, undo+dryRun, perms MODEL.UPDATE+FIELD.CREATE")
    void metadata_correct() {
        assertThat(skill.name()).isEqualTo("field:add");
        assertThat(skill.displayName()).isEqualTo("aurabot.skill.field.add.displayName");
        assertThat(skill.category()).isEqualTo("meta");
        assertThat(skill.riskLevel()).isEqualTo(RiskLevel.MEDIUM);
        assertThat(skill.supportsDryRun()).isTrue();
        assertThat(skill.supportsUndo()).isTrue();
        assertThat(skill.supportsStreaming()).isFalse();
        assertThat(skill.requiredPermissions())
                .containsExactlyInAnyOrder("MODEL.UPDATE", "FIELD.CREATE");
        assertThat(skill.paramsSchema()).isNotNull();
        assertThat(skill.paramsSchema().get("required"))
                .isNotNull();
        assertThat(skill.paramsSchema().get("additionalProperties").asBoolean())
                .isFalse();
    }

    @Nested
    @DisplayName("paramsSchema validation")
    class SchemaValidation {

        private JsonSchema compiled;

        @BeforeEach
        void compile() {
            compiled = JsonSchemaFactory
                    .getInstance(SpecVersion.VersionFlag.V202012)
                    .getSchema(skill.paramsSchema());
        }

        @Test
        @DisplayName("accepts minimal {modelCode, code, dataType}")
        void acceptsMinimal() throws Exception {
            JsonNode params = MAPPER.readTree(
                    "{\"modelCode\":\"customer\",\"code\":\"phone\",\"dataType\":\"string\"}");
            Set<ValidationMessage> errors = compiled.validate(params);
            assertThat(errors).isEmpty();
        }

        @Test
        @DisplayName("rejects code violating ^[a-z][a-z0-9_]{0,62}$ pattern")
        void rejectsCodePattern() throws Exception {
            JsonNode params = MAPPER.readTree(
                    "{\"modelCode\":\"customer\",\"code\":\"PhoneNumber\",\"dataType\":\"string\"}");
            Set<ValidationMessage> errors = compiled.validate(params);
            assertThat(errors).isNotEmpty();
            assertThat(errors.toString()).contains("code");
        }

        @Test
        @DisplayName("rejects unknown additional property (additionalProperties:false)")
        void rejectsAdditional() throws Exception {
            JsonNode params = MAPPER.readTree(
                    "{\"modelCode\":\"customer\",\"code\":\"phone\",\"dataType\":\"string\","
                            + "\"unexpected\":\"oops\"}");
            Set<ValidationMessage> errors = compiled.validate(params);
            assertThat(errors).isNotEmpty();
            assertThat(errors.toString()).contains("unexpected");
        }

        @Test
        @DisplayName("rejects dataType outside the 9-type whitelist")
        void rejectsInvalidDataType() throws Exception {
            JsonNode params = MAPPER.readTree(
                    "{\"modelCode\":\"customer\",\"code\":\"phone\",\"dataType\":\"uuid\"}");
            Set<ValidationMessage> errors = compiled.validate(params);
            assertThat(errors).isNotEmpty();
            assertThat(errors.toString()).contains("dataType");
        }
    }

    @Test
    @DisplayName("dryRun: unknown model → SkillSpiException(PARAMS_INVALID, /modelCode)")
    void dryRun_unknownModel_throwsParamsInvalid() throws Exception {
        when(metaModelService.findByCode("ghost"))
                .thenThrow(new ValidationException(
                        ResponseCode.CommonValidationFailed,
                        "模型不存在: ghost"));

        JsonNode params = MAPPER.readTree(
                "{\"modelCode\":\"ghost\",\"code\":\"phone\",\"dataType\":\"string\"}");
        SkillRequest req = SkillRequest.builder()
                .skillName("field:add")
                .params(params)
                .build();

        assertThatThrownBy(() -> skill.dryRun(req))
                .isInstanceOf(SkillSpiException.class)
                .satisfies(t -> {
                    SkillSpiException sse = (SkillSpiException) t;
                    assertThat(sse.getErrorCode()).isEqualTo(SkillErrorCode.PARAMS_INVALID);
                    assertThat(sse.getFieldPath()).isEqualTo("/modelCode");
                });
    }
}

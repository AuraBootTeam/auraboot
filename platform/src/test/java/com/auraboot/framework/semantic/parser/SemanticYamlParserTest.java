package com.auraboot.framework.semantic.parser;

import com.auraboot.framework.semantic.dto.SemanticModelDTO;
import com.auraboot.framework.semantic.exception.SemanticValidationException;
import com.auraboot.framework.semantic.exception.SemanticYamlInvalidException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SemanticYamlParser + SemanticYamlValidator tests against the 9 fixtures in
 * {@code test/resources/semantic/}.
 *
 * <p>Layer split mirrors the fixture layout:
 * <ul>
 *   <li>{@code valid/} → parser succeeds, validator succeeds</li>
 *   <li>{@code invalid/schema/} → parser throws {@link SemanticYamlInvalidException}</li>
 *   <li>{@code invalid/validator/} → parser succeeds, validator throws {@link SemanticValidationException}</li>
 * </ul>
 */
class SemanticYamlParserTest {

    private static SemanticYamlParser parser;
    private static SemanticYamlValidator validator;

    @BeforeAll
    static void setup() {
        parser = new SemanticYamlParser();
        validator = new SemanticYamlValidator();
    }

    private static String loadFixture(String relativePath) throws IOException {
        try (var in = new ClassPathResource("semantic/" + relativePath).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    // -- valid fixtures: full parse + validate succeeds --------------------

    @ParameterizedTest(name = "valid/{0}.semantic.yml parses + validates")
    @ValueSource(strings = {"sales", "inventory", "crm"})
    void validFixturesParseAndValidate(String name) throws IOException {
        String yaml = loadFixture("valid/" + name + ".semantic.yml");
        SemanticModelDTO dto = parser.parse(yaml);

        assertThat(dto).isNotNull();
        assertThat(dto.getVersion()).isEqualTo("0.1");
        assertThat(dto.getSemanticModel().getCode()).isEqualTo(name);
        assertThat(dto.getEntities()).isNotEmpty();
        assertThat(dto.getDimensions()).isNotEmpty();
        assertThat(dto.getMeasures()).isNotEmpty();
        assertThat(dto.getMetrics()).isNotEmpty();

        // Validator runs cleanly
        validator.validate(dto);
    }

    @Test
    void sales_has_5_metric_types_covered() throws IOException {
        SemanticModelDTO dto = parser.parse(loadFixture("valid/sales.semantic.yml"));
        validator.validate(dto);
        assertThat(dto.getMetrics())
                .extracting(m -> m.getType())
                .contains("simple", "ratio", "cumulative", "derived");
    }

    @Test
    void crm_has_conversion_metric() throws IOException {
        SemanticModelDTO dto = parser.parse(loadFixture("valid/crm.semantic.yml"));
        validator.validate(dto);
        assertThat(dto.getMetrics())
                .extracting(m -> m.getType())
                .contains("conversion");
    }

    @Test
    void inventory_has_one_primary_time_dimension() throws IOException {
        SemanticModelDTO dto = parser.parse(loadFixture("valid/inventory.semantic.yml"));
        long primaryTimes = dto.getDimensions().stream()
                .filter(d -> Boolean.TRUE.equals(d.getPrimaryTime()))
                .count();
        assertThat(primaryTimes).isEqualTo(1L);
    }

    @Test
    void sales_access_policy_parses_target_dimensions() throws IOException {
        SemanticModelDTO dto = parser.parse(loadFixture("valid/sales.semantic.yml"));
        assertThat(dto.getAccessPolicies()).isNotEmpty();
        assertThat(dto.getAccessPolicies().get(0).getTargetDimensions()).contains("region");
    }

    // -- invalid/schema/: parser throws SemanticYamlInvalidException ---------

    @ParameterizedTest(name = "invalid/schema/{0}.semantic.yml rejected by parser")
    @ValueSource(strings = {
            "missing-version",
            "bad-metric-type",
            "wrong-version",
            "uppercase-code",
            "wrong-conversion-window"
    })
    void schemaInvalidFixturesRejectedByParser(String name) throws IOException {
        String yaml = loadFixture("invalid/schema/" + name + ".semantic.yml");
        assertThatThrownBy(() -> parser.parse(yaml))
                .isInstanceOf(SemanticYamlInvalidException.class)
                .satisfies(ex -> {
                    SemanticYamlInvalidException e = (SemanticYamlInvalidException) ex;
                    assertThat(e.getErrorCode()).isEqualTo("SEMANTIC_YAML_INVALID");
                    assertThat(e.getSchemaErrors()).isNotEmpty();
                });
    }

    // -- invalid/validator/: parser succeeds, validator throws --------------

    @Test
    void sqlInjectionFilterPassesSchemaButRejectedByValidator() throws IOException {
        String yaml = loadFixture("invalid/validator/sql-injection-filter.semantic.yml");
        SemanticModelDTO dto = parser.parse(yaml); // schema OK
        assertThatThrownBy(() -> validator.validate(dto))
                .isInstanceOf(SemanticValidationException.class)
                .satisfies(ex -> {
                    SemanticValidationException e = (SemanticValidationException) ex;
                    assertThat(e.getErrorCode()).isEqualTo("SQL_INJECTION_DETECTED");
                });
    }

    /**
     * A scalar {@code measure.expr} that smuggles a sub-SELECT reading another table
     * contains NO classic denylisted token ({@code ;}, {@code --}, {@code UNION},
     * {@code DROP}) — only a bare {@code SELECT}. The hardened denylist must still
     * reject it; otherwise the compiler inlines it verbatim and any caller can
     * exfiltrate arbitrary columns cross-table / cross-tenant.
     */
    @Test
    void subquerySelectInMeasureExprRejectedByValidator() throws IOException {
        String yaml = loadFixture("invalid/validator/subquery-exfil-measure.semantic.yml");
        SemanticModelDTO dto = parser.parse(yaml); // schema OK
        assertThatThrownBy(() -> validator.validate(dto))
                .isInstanceOf(SemanticValidationException.class)
                .satisfies(ex -> assertThat(((SemanticValidationException) ex).getErrorCode())
                        .isEqualTo("SQL_INJECTION_DETECTED"));
    }

    /**
     * False-positive guard: banning sub-queries must NOT ban {@code FROM}, because
     * {@code EXTRACT(<part> FROM <col>)} is a legitimate scalar expression. This is the
     * mutation-verification counterpart to {@link #subquerySelectInMeasureExprRejectedByValidator()}
     * — if someone "fixes" the denylist by banning {@code \bFROM\b}, this goes red.
     */
    @Test
    void extractFromInMeasureExprIsAllowed() {
        String yaml = ""
                + "version: \"0.1\"\n"
                + "semantic_model: {code: t, model_ref: ab_role, primary_entity: id}\n"
                + "entities: [{name: id, type: primary, field_ref: id}]\n"
                + "dimensions: [{code: y, type: categorical, field_ref: created_at}]\n"
                + "measures: [{code: yr, agg: MAX, expr: \"EXTRACT(YEAR FROM created_at)\"}]\n"
                + "metrics: [{code: m, type: simple, type_params: {measure: yr}}]\n";
        SemanticModelDTO dto = parser.parse(yaml);
        // Must not throw — EXTRACT(... FROM ...) is a valid scalar expression.
        validator.validate(dto);
        assertThat(dto.getMeasures()).extracting(m -> m.getCode()).contains("yr");
    }

    // -- malformed YAML (not a structural error in schema, but parser-level) -

    @Test
    void malformedYamlReportsParseError() {
        String yaml = "version: 0.1\nsemantic_model:\n  code: \"unterminated";
        assertThatThrownBy(() -> parser.parse(yaml))
                .isInstanceOf(SemanticYamlInvalidException.class)
                .hasMessageContaining("Malformed YAML");
    }

    // -- validator-only synthetic cases (no fixture files) -------------------

    @Test
    void duplicateMetricCodeRejected() throws IOException {
        SemanticModelDTO dto = parser.parse(loadFixture("valid/sales.semantic.yml"));
        // Inject duplicate metric
        var dup = dto.getMetrics().get(0);
        dto.getMetrics().add(dup);

        assertThatThrownBy(() -> validator.validate(dto))
                .isInstanceOf(SemanticValidationException.class)
                .satisfies(ex -> assertThat(((SemanticValidationException) ex).getErrorCode())
                        .isEqualTo("DUPLICATE_CODE"));
    }

    @Test
    void simpleMetricWithUnknownMeasureRejected() throws IOException {
        SemanticModelDTO dto = parser.parse(loadFixture("valid/sales.semantic.yml"));
        dto.getMetrics().get(0).getTypeParams().put("measure", "no_such_measure");
        assertThatThrownBy(() -> validator.validate(dto))
                .isInstanceOf(SemanticValidationException.class)
                .satisfies(ex -> assertThat(((SemanticValidationException) ex).getErrorCode())
                        .isEqualTo("MISSING_REFERENCE"));
    }

    @Test
    void derivedMetricWithUnknownPlaceholderRejected() throws IOException {
        SemanticModelDTO dto = parser.parse(loadFixture("valid/sales.semantic.yml"));
        var derived = dto.getMetrics().stream()
                .filter(m -> "derived".equals(m.getType()))
                .findFirst().orElseThrow();
        derived.getTypeParams().put("expr", "{nonexistent_metric} / {order_count}");
        assertThatThrownBy(() -> validator.validate(dto))
                .isInstanceOf(SemanticValidationException.class)
                .satisfies(ex -> assertThat(((SemanticValidationException) ex).getErrorCode())
                        .isEqualTo("MISSING_REFERENCE"));
    }

    @Test
    void multiplePrimaryTimeRejected() throws IOException {
        SemanticModelDTO dto = parser.parse(loadFixture("valid/sales.semantic.yml"));
        // Force a second dimension to claim primary_time
        dto.getDimensions().forEach(d -> d.setPrimaryTime(Boolean.TRUE));
        assertThatThrownBy(() -> validator.validate(dto))
                .isInstanceOf(SemanticValidationException.class)
                .satisfies(ex -> assertThat(((SemanticValidationException) ex).getErrorCode())
                        .isEqualTo("MULTIPLE_PRIMARY_TIME"));
    }

    @Test
    void primaryEntityMissingRejected() throws IOException {
        SemanticModelDTO dto = parser.parse(loadFixture("valid/sales.semantic.yml"));
        dto.getSemanticModel().setPrimaryEntity("not_declared");
        assertThatThrownBy(() -> validator.validate(dto))
                .isInstanceOf(SemanticValidationException.class)
                .satisfies(ex -> assertThat(((SemanticValidationException) ex).getErrorCode())
                        .isEqualTo("MISSING_REFERENCE"));
    }

    @Test
    void accessPolicyForbidsNonUserPlaceholders() throws IOException {
        SemanticModelDTO dto = parser.parse(loadFixture("valid/sales.semantic.yml"));
        dto.getAccessPolicies().get(0)
                .setSqlFilter("region_code = {tenant.code}"); // not user.*
        assertThatThrownBy(() -> validator.validate(dto))
                .isInstanceOf(SemanticValidationException.class)
                .satisfies(ex -> assertThat(((SemanticValidationException) ex).getErrorCode())
                        .isEqualTo("ACCESS_POLICY_INVALID_PLACEHOLDER"));
    }

    @Test
    void accessPolicyValidUserPlaceholderAccepted() throws IOException {
        SemanticModelDTO dto = parser.parse(loadFixture("valid/sales.semantic.yml"));
        // Already uses {user.allowed_regions} — confirm pass
        validator.validate(dto);
    }
}

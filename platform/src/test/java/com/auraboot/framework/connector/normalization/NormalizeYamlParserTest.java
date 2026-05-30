package com.auraboot.framework.connector.normalization;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link NormalizeYamlParser}.
 *
 * <p>Pure JUnit 5 + AssertJ, no Spring context required.
 */
class NormalizeYamlParserTest {

    private final NormalizeYamlParser parser = new NormalizeYamlParser();

    // ---------------------------------------------------------------------------
    // Case 1: happy path — sales.normalize.yml
    // ---------------------------------------------------------------------------

    @Test
    void happyPath_parsesFullFixtureFile() throws Exception {
        try (InputStream in = Objects.requireNonNull(
                getClass().getClassLoader().getResourceAsStream("normalization/sales.normalize.yml"),
                "test fixture not found: normalization/sales.normalize.yml")) {

            NormalizationConfig cfg = parser.parse(in);

            assertThat(cfg.name()).isEqualTo("hubspot-deal-normalize");
            assertThat(cfg.version()).isEqualTo("0.1");
            assertThat(cfg.fields()).hasSize(4);

            NormalizationConfig.FieldRule ts = cfg.fields().get(0);
            assertThat(ts.source()).isEqualTo("hs_lastmodifieddate");
            assertThat(ts.target()).isEqualTo("updated_at");
            assertThat(ts.type()).isEqualTo(NormalizationRuleType.TIMESTAMP);
            assertThat(ts.param("from_format")).isEqualTo("iso8601");
            assertThat(ts.param("to_format")).isEqualTo("epoch_millis");

            NormalizationConfig.FieldRule num = cfg.fields().get(1);
            assertThat(num.type()).isEqualTo(NormalizationRuleType.NUMERIC_UNIT);
            assertThat(num.param("from")).isEqualTo("dollars");
            assertThat(num.param("to")).isEqualTo("cents");

            NormalizationConfig.FieldRule enumMap = cfg.fields().get(2);
            assertThat(enumMap.type()).isEqualTo(NormalizationRuleType.ENUM_MAP);
            assertThat(enumMap.mappingParam()).containsEntry("closedwon", "WON")
                                              .containsEntry("closedlost", "LOST");

            NormalizationConfig.FieldRule rename = cfg.fields().get(3);
            assertThat(rename.type()).isEqualTo(NormalizationRuleType.RENAME);
            assertThat(rename.source()).isEqualTo("hubspot_owner_id");
            assertThat(rename.target()).isEqualTo("owner_id");
        }
    }

    // ---------------------------------------------------------------------------
    // Case 2: missing 'name' field
    // ---------------------------------------------------------------------------

    @Test
    void missingName_throwsMissingFieldException() {
        String yaml = """
                version: "0.1"
                fields: []
                """;
        assertThatThrownBy(() -> parser.parse(toStream(yaml)))
                .isInstanceOf(NormalizationConfigException.class)
                .hasMessageContaining("name")
                .extracting(e -> ((NormalizationConfigException) e).code())
                .isEqualTo("MISSING_FIELD");
    }

    // ---------------------------------------------------------------------------
    // Case 3: missing 'version' field
    // ---------------------------------------------------------------------------

    @Test
    void missingVersion_throwsMissingFieldException() {
        String yaml = """
                name: test-config
                fields: []
                """;
        assertThatThrownBy(() -> parser.parse(toStream(yaml)))
                .isInstanceOf(NormalizationConfigException.class)
                .hasMessageContaining("version")
                .extracting(e -> ((NormalizationConfigException) e).code())
                .isEqualTo("MISSING_FIELD");
    }

    // ---------------------------------------------------------------------------
    // Case 4: missing 'fields' key entirely
    // ---------------------------------------------------------------------------

    @Test
    void missingFields_throwsMissingFieldException() {
        String yaml = """
                name: test-config
                version: "0.1"
                """;
        assertThatThrownBy(() -> parser.parse(toStream(yaml)))
                .isInstanceOf(NormalizationConfigException.class)
                .hasMessageContaining("fields")
                .extracting(e -> ((NormalizationConfigException) e).code())
                .isEqualTo("MISSING_FIELD");
    }

    // ---------------------------------------------------------------------------
    // Case 5: unknown rule type
    // ---------------------------------------------------------------------------

    @Test
    void unknownRuleType_throwsUnknownRuleTypeException() {
        String yaml = """
                name: test-config
                version: "0.1"
                fields:
                  - source: foo
                    target: bar
                    type: DOES_NOT_EXIST
                """;
        assertThatThrownBy(() -> parser.parse(toStream(yaml)))
                .isInstanceOf(NormalizationConfigException.class)
                .hasMessageContaining("DOES_NOT_EXIST")
                .extracting(e -> ((NormalizationConfigException) e).code())
                .isEqualTo("UNKNOWN_RULE_TYPE");
    }

    // ---------------------------------------------------------------------------
    // Case 6: TIMESTAMP rule missing params
    // ---------------------------------------------------------------------------

    @Test
    void timestampMissingParams_throwsMissingRuleParamsException() {
        String yaml = """
                name: test-config
                version: "0.1"
                fields:
                  - source: created_at
                    target: created_ts
                    type: TIMESTAMP
                """;
        assertThatThrownBy(() -> parser.parse(toStream(yaml)))
                .isInstanceOf(NormalizationConfigException.class)
                .extracting(e -> ((NormalizationConfigException) e).code())
                .isEqualTo("MISSING_RULE_PARAMS");
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    private InputStream toStream(String yaml) {
        return new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8));
    }
}

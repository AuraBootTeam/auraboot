package com.auraboot.framework.plugin.rest;

import com.auraboot.framework.exception.ValidationException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

class RestSchemaValidatorTest {

    private final RestSchemaValidator validator = new RestSchemaValidator(new ObjectMapper());

    private static final String SCHEMA = """
            {
              "type": "object",
              "required": ["text"],
              "properties": { "text": { "type": "string", "minLength": 1 } }
            }
            """;

    @Test
    void blankSchemaSkipsValidation() {
        assertThatCode(() -> validator.validate("anything".getBytes(StandardCharsets.UTF_8), null))
                .doesNotThrowAnyException();
        assertThatCode(() -> validator.validate("anything".getBytes(StandardCharsets.UTF_8), "  "))
                .doesNotThrowAnyException();
    }

    @Test
    void validBodyPasses() {
        assertThatCode(() ->
                validator.validate("{\"text\":\"hi\"}".getBytes(StandardCharsets.UTF_8), SCHEMA))
                .doesNotThrowAnyException();
    }

    @Test
    void missingRequiredFieldThrowsValidationWithPointer() {
        assertThatThrownBy(() ->
                validator.validate("{}".getBytes(StandardCharsets.UTF_8), SCHEMA))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("text");
    }

    @Test
    void malformedJsonBodyThrowsValidation() {
        assertThatThrownBy(() ->
                validator.validate("not json".getBytes(StandardCharsets.UTF_8), SCHEMA))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void compiledSchemaIsCachedBySchemaString() {
        // Same schema string validated twice must reuse the compiled schema (no recompile cost/leak).
        validator.validate("{\"text\":\"a\"}".getBytes(StandardCharsets.UTF_8), SCHEMA);
        validator.validate("{\"text\":\"b\"}".getBytes(StandardCharsets.UTF_8), SCHEMA);
        assertThat(validator.cachedSchemaCount()).isEqualTo(1);
    }
}

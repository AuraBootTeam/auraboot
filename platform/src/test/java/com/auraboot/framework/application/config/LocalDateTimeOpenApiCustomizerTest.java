package com.auraboot.framework.application.config;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.type.TypeFactory;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.oas.models.media.StringSchema;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class LocalDateTimeOpenApiCustomizerTest {
    private final LocalDateTimeOpenApiCustomizer customizer = new LocalDateTimeOpenApiCustomizer();

    @Test
    void defaultFormatMatchesTheActualOffsetFreeJacksonWireValue() throws Exception {
        var mapper = new ObjectMapper().registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        var timestamp = LocalDateTime.of(2026, 10, 18, 10, 20, 17);
        assertThat(mapper.writeValueAsString(timestamp)).isEqualTo("\"2026-10-18T10:20:17\"");
        var schema = new StringSchema().format("date-time");
        assertThat(customizer.customize(schema, new AnnotatedType(LocalDateTime.class)).getFormat())
                .isEqualTo("local-date-time");
        assertThat(schema.getType()).isEqualTo("string");
        var jacksonType = new AnnotatedType(TypeFactory.defaultInstance().constructType(LocalDateTime.class));
        assertThat(customizer.customize(new StringSchema().format("date-time"), jacksonType).getFormat())
                .isEqualTo("local-date-time");
    }

    @Test
    void instantAndExplicitFormatContractsArePreserved() throws Exception {
        var instant = new StringSchema().format("date-time");
        assertThat(customizer.customize(instant, new AnnotatedType(Instant.class)).getFormat()).isEqualTo("date-time");
        var explicit = new StringSchema().format("custom-pattern");
        assertThat(customizer.customize(explicit, new AnnotatedType(LocalDateTime.class)).getFormat()).isEqualTo("custom-pattern");
        var annotated = new AnnotatedType(LocalDateTime.class)
                .ctxAnnotations(CustomFormat.class.getDeclaredField("timestamp").getAnnotations());
        assertThat(customizer.customize(new StringSchema().format("date-time"), annotated).getFormat())
                .isEqualTo("date-time");
    }

    static final class CustomFormat {
        @JsonFormat(pattern = "yyyy/MM/dd HH:mm:ss")
        private LocalDateTime timestamp;
    }
}

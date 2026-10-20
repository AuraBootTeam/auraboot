package com.auraboot.framework.application.config;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.oas.models.media.Schema;
import org.springdoc.core.customizers.PropertyCustomizer;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/** Describe offset-free Jackson local datetimes without claiming an RFC 3339 offset. */
@Component
public final class LocalDateTimeOpenApiCustomizer implements PropertyCustomizer {
    @Override
    @SuppressWarnings("rawtypes")
    public Schema customize(Schema schema, AnnotatedType type) {
        if (schema == null || type == null || !isLocalDateTime(type)
                || !"date-time".equals(schema.getFormat())) {
            return schema;
        }
        if (type.getCtxAnnotations() != null) {
            for (var annotation : type.getCtxAnnotations()) {
                // Explicit serializers/formats own their schema instead of inheriting this default.
                if (annotation instanceof JsonSerialize
                        || annotation instanceof JsonFormat format && !format.pattern().isEmpty()) {
                    return schema;
                }
            }
        }
        schema.setFormat("local-date-time");
        return schema;
    }

    private static boolean isLocalDateTime(AnnotatedType type) {
        return LocalDateTime.class.equals(type.getType())
                || type.getType() instanceof JavaType javaType && javaType.hasRawClass(LocalDateTime.class);
    }
}

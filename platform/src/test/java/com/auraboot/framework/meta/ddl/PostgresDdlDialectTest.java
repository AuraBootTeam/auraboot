package com.auraboot.framework.meta.ddl;

import com.auraboot.framework.meta.dto.FieldDefinition;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PostgresDdlDialectTest {

    private final PostgresDdlDialect dialect = new PostgresDdlDialect();

    @Test
    void usesDisplayPrecisionAsScaleWhenScaleIsMissing() {
        FieldDefinition field = FieldDefinition.builder()
                .code("sc_budget")
                .dataType("decimal")
                .precision(2)
                .build();

        assertEquals("DECIMAL(19,2)", dialect.mapDataType(field));
    }

    @Test
    void prefersExplicitPrecisionAndScaleWhenBothArePresent() {
        FieldDefinition field = FieldDefinition.builder()
                .code("sc_budget")
                .dataType("decimal")
                .precision(19)
                .scale(2)
                .build();

        assertEquals("DECIMAL(19,2)", dialect.mapDataType(field));
    }
}

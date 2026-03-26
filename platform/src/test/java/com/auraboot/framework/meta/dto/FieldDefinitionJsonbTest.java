package com.auraboot.framework.meta.dto;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FieldDefinitionJsonbTest {

    @Test
    void jsonbVirtualField_hasCorrectProperties() {
        FieldDefinition field = FieldDefinition.builder()
                .code("crm_call_duration")
                .dataType("integer")
                .columnName("crm_call_duration")
                .jsonbColumn("crm_act_ext")
                .jsonbPath("duration")
                .build();

        assertTrue(field.isJsonbVirtual());
        assertEquals("crm_act_ext", field.getJsonbColumn());
        assertEquals("duration", field.getJsonbPath());
        assertEquals("(crm_act_ext->>'duration')::integer", field.getJsonbSelectExpression());
        assertEquals("(crm_act_ext->>'duration')::integer", field.getJsonbFilterExpression());
    }

    @Test
    void regularField_isNotJsonbVirtual() {
        FieldDefinition field = FieldDefinition.builder()
                .code("crm_act_subject")
                .dataType("string")
                .columnName("crm_act_subject")
                .build();

        assertFalse(field.isJsonbVirtual());
        assertNull(field.getJsonbColumn());
        assertNull(field.getJsonbPath());
        assertNull(field.getJsonbSelectExpression());
    }

    @Test
    void jsonbSelectExpression_handlesAllTypes() {
        // STRING — no cast needed, ->> returns text
        assertEquals("ext->>'name'",
                buildField("string", "ext", "name").getJsonbSelectExpression());
        // TEXT
        assertEquals("ext->>'desc'",
                buildField("text", "ext", "desc").getJsonbSelectExpression());
        // DICT (treated same as STRING)
        assertEquals("ext->>'status'",
                buildField("dict", "ext", "status").getJsonbSelectExpression());
        // INTEGER
        assertEquals("(ext->>'count')::integer",
                buildField("integer", "ext", "count").getJsonbSelectExpression());
        // LONG
        assertEquals("(ext->>'bigCount')::bigint",
                buildField("long", "ext", "bigCount").getJsonbSelectExpression());
        // DECIMAL
        assertEquals("(ext->>'amount')::numeric",
                buildField("decimal", "ext", "amount").getJsonbSelectExpression());
        // BOOLEAN
        assertEquals("(ext->>'active')::boolean",
                buildField("boolean", "ext", "active").getJsonbSelectExpression());
        // DATETIME
        assertEquals("(ext->>'startTime')::timestamp",
                buildField("datetime", "ext", "startTime").getJsonbSelectExpression());
        // DATE
        assertEquals("(ext->>'dueDate')::date",
                buildField("date", "ext", "dueDate").getJsonbSelectExpression());
        // TIME
        assertEquals("(ext->>'checkTime')::time",
                buildField("time", "ext", "checkTime").getJsonbSelectExpression());
    }

    @Test
    void isJsonbVirtual_requiresBothFields() {
        // Only jsonbColumn set — not virtual
        assertFalse(FieldDefinition.builder()
                .code("x").dataType("string").jsonbColumn("ext").build().isJsonbVirtual());
        // Only jsonbPath set — not virtual
        assertFalse(FieldDefinition.builder()
                .code("x").dataType("string").jsonbPath("key").build().isJsonbVirtual());
        // Empty strings — not virtual
        assertFalse(FieldDefinition.builder()
                .code("x").dataType("string").jsonbColumn("").jsonbPath("key").build().isJsonbVirtual());
    }

    private FieldDefinition buildField(String dataType, String jsonbColumn, String jsonbPath) {
        return FieldDefinition.builder()
                .code("test_" + jsonbPath)
                .dataType(dataType)
                .columnName("test_" + jsonbPath)
                .jsonbColumn(jsonbColumn)
                .jsonbPath(jsonbPath)
                .build();
    }
}

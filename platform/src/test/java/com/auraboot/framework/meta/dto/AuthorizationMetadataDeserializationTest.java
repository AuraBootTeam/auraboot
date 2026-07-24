package com.auraboot.framework.meta.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The authorization metadata must survive the trip from a plugin's JSON.
 *
 * <p>Both of these guards are declared in {@code models.json} and reach the platform through
 * Jackson. If the metadata does not survive that trip the guard silently never engages — an inert
 * boundary that still reads as configured, which is the class of bug this line of work exists to
 * remove. These tests pin the JSON contract itself, so a field rename, a constructor shape Jackson
 * cannot use, or a dropped Lombok annotation shows up here rather than in production.</p>
 */
class AuthorizationMetadataDeserializationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("aggregateBinding survives deserialization from a plugin model definition")
    void aggregateBindingDeserializes() throws Exception {
        String json = """
                {
                  "code": "quote_line",
                  "tableName": "mt_quote_line",
                  "aggregateBinding": { "aggregateModel": "quote", "localField": "quote_pid" }
                }
                """;

        ModelDefinition model = mapper.readValue(json, ModelDefinition.class);

        assertTrue(model.getAggregateBinding() != null,
                "a binding declared in models.json must not vanish on the way in");
        assertEquals("quote", model.getAggregateBinding().getAggregateModel());
        assertEquals("quote_pid", model.getAggregateBinding().getLocalField());
    }

    @Test
    @DisplayName("immutableWhen survives deserialization from a plugin field definition")
    void immutableWhenDeserializes() throws Exception {
        String json = """
                {
                  "code": "price",
                  "name": "Price",
                  "immutableWhen": { "field": "status", "in": ["approved", "closed"] }
                }
                """;

        FieldDefinition field = mapper.readValue(json, FieldDefinition.class);

        assertTrue(field.getImmutableWhen() != null,
                "a lock declared in models.json must not vanish on the way in");
        assertEquals("status", field.getImmutableWhen().getField());
        assertEquals(List.of("approved", "closed"), field.getImmutableWhen().getIn());
    }

    @Test
    @DisplayName("the unconditional immutable flag survives deserialization")
    void immutableFlagDeserializes() throws Exception {
        FieldDefinition field = mapper.readValue(
                "{\"code\":\"order_no\",\"immutable\":true}", FieldDefinition.class);

        assertTrue(field.isImmutable());
    }

    @Test
    @DisplayName("a model without the new metadata still deserializes")
    void absentMetadataIsFine() throws Exception {
        ModelDefinition model = mapper.readValue(
                "{\"code\":\"audit_log\",\"tableName\":\"mt_audit_log\"}", ModelDefinition.class);

        assertEquals("audit_log", model.getCode());
        assertTrue(model.getAggregateBinding() == null);
    }
}

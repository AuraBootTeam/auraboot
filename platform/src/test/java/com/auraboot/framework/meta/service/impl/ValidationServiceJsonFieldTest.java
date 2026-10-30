package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.dto.ValidationContext;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.user.service.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.DoubleNode;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** Hermetic validation of strict JSON values; native reference and model checks have separate tests. */
class ValidationServiceJsonFieldTest {
    @Test void unicodeBlankTextCannotSatisfyRequiredPublicationButDraftUpdateRemainsPartial() {
        var field=FieldDefinition.builder().code("material").name("Material").dataType("string").required(true).build();
        var value=new com.fasterxml.jackson.databind.node.TextNode("\u00a0\u3000");
        assertFalse(service.validateJsonField(field,value,ValidationContext.CUSTOM).isValid());
        assertTrue(service.validateJsonField(field,value,ValidationContext.UPDATE).isValid());
        assertEquals("\u00a0\u3000",value.textValue());
    }
    @Test void extremeExponentsCannotOverflowTheDeclaredIntegerDigitCount() {
        var field=FieldDefinition.builder().code("cost").name("Cost").dataType("decimal").precision(20).scale(2).build();
        assertFalse(service.validateJsonField(field,new com.fasterxml.jackson.databind.node.DecimalNode(new BigDecimal("1E+2147483647")),ValidationContext.CUSTOM).isValid());
    }
    private final ObjectMapper json = new ObjectMapper();
    private final ValidationServiceImpl service = new ValidationServiceImpl(
            mock(DynamicDataMapper.class), mock(UserService.class), mock(MetaModelService.class));

    private FieldDefinition field(String type) {
        return FieldDefinition.builder().code("capacity").name("Capacity").dataType(type).build();
    }
    private boolean valid(FieldDefinition field, String value) throws Exception {
        return service.validateJsonField(field, json.readTree(value), ValidationContext.CUSTOM).isValid();
    }

    @Test void rejectsNumericStringsWithoutChangingTheLegacyContract() throws Exception {
        var field = field("integer"); field.setMinValue(1); field.setMaxValue(10);
        assertTrue(service.validateField(field, "99", ValidationContext.CUSTOM).isValid());
        assertFalse(valid(field, "\"99\""));
        assertFalse(valid(field, "99"));
        assertTrue(valid(field, "7"));
        assertTrue(valid(field, "7.000"));
        assertFalse(valid(field, "7.01"));
        assertFalse(valid(field, "true"));
        assertFalse(valid(field, "2147483648"));
    }

    @Test void enforcesLongBoundariesWithoutRounding() throws Exception {
        var field = field("long");
        assertTrue(valid(field, "9223372036854775807"));
        assertTrue(valid(field, "-9223372036854775808"));
        assertFalse(valid(field, "9223372036854775808"));
        assertFalse(valid(field, "-9223372036854775809"));
        assertFalse(valid(field, "1.0001"));
    }

    @Test void decimalUsesExactBoundsPrecisionScaleAndEquivalentTrailingZeros() throws Exception {
        var field = field("decimal"); field.setPrecision(5); field.setScale(2);
        field.setMinValue(new BigDecimal("0.01")); field.setMaxValue(new BigDecimal("999.99"));
        assertTrue(valid(field, "999.9900"));
        assertTrue(valid(field, "0.0100"));
        assertFalse(valid(field, "1000"));
        assertFalse(valid(field, "0.001"));
        assertFalse(valid(field, "-1"));
        assertFalse(valid(field, "\"0.01\""));
        assertFalse(service.validateJsonField(field, DoubleNode.valueOf(Double.NaN), ValidationContext.CUSTOM).isValid());
        assertFalse(service.validateJsonField(field, DoubleNode.valueOf(Double.POSITIVE_INFINITY), ValidationContext.CUSTOM).isValid());
        field.setPrecision(2); field.setScale(3); assertFalse(valid(field, "1"));
        field.setPrecision(0); field.setScale(0); assertFalse(valid(field, "1"));
        field.setPrecision(2); field.setScale(2); field.setMinValue(null); field.setMaxValue(null);
        assertTrue(valid(field, "0.0000")); assertTrue(valid(field, "0.99")); assertFalse(valid(field, "1"));
    }

    @Test void stringsBooleanAndUnknownTypesCannotCoerceOrSilentlyPass() throws Exception {
        var text = field("string"); text.setMinLength(2); text.setMaxLength(3); text.setFormat("[0-9]+");
        assertTrue(valid(text, "\"007\""));
        assertFalse(valid(text, "7"));
        assertFalse(valid(text, "\"7\""));
        assertFalse(valid(text, "\"0007\""));
        assertFalse(valid(text, "\"AB\""));
        assertTrue(valid(field("boolean"), "true"));
        assertFalse(valid(field("boolean"), "\"true\""));
        assertFalse(valid(field("unknown"), "null"));
        assertFalse(valid(field("INTEGER"), "7"));
        assertFalse(service.validateJsonField(null, json.readTree("7"), ValidationContext.CUSTOM).isValid());
        assertFalse(service.validateJsonField(field("string"), json.readTree("\"ok\""), null).isValid());
    }

    @Test void requiredAndDraftPartialContextsRetainNativeSemantics() throws Exception {
        var field = field("string"); field.setRequired(true);
        assertFalse(valid(field, "null")); assertFalse(valid(field, "\"  \""));
        assertFalse(service.validateJsonField(field, null, ValidationContext.CUSTOM).isValid());
        assertTrue(service.validateJsonField(field, null, ValidationContext.UPDATE).isValid());
        field.setRequired(false); assertTrue(valid(field, "null"));
    }

    @Test void temporalValuesAreStrictIsoStrings() throws Exception {
        assertTrue(valid(field("date"), "\"2024-02-29\""));
        assertFalse(valid(field("date"), "\"2023-02-29\""));
        assertFalse(valid(field("date"), "20240229"));
        assertTrue(valid(field("datetime"), "\"2026-10-28T12:30:00Z\""));
        assertFalse(valid(field("datetime"), "\"2026-10-28T12:30:00\""));
        assertTrue(valid(field("time"), "\"23:59:59\""));
        assertFalse(valid(field("time"), "\"25:00:00\""));
    }

    @Test void structuredValuesAndEnumShapesDoNotMutateInputs() throws Exception {
        var input = json.readTree("{\"colors\":[\"red\"],\"enabled\":true}"); var copy = input.deepCopy();
        assertTrue(service.validateJsonField(field("json"), input, ValidationContext.CUSTOM).isValid());
        assertEquals(copy, input);
        assertTrue(valid(field("array"), "[1,2]"));
        assertFalse(valid(field("array"), "{}"));
        assertFalse(valid(field("json"), "\"{}\""));
        assertTrue(valid(field("enum"), "\"red\""));
        assertFalse(valid(field("enum"), "1"));
        assertFalse(valid(field("reference"), "\"unbound\""));
    }
}

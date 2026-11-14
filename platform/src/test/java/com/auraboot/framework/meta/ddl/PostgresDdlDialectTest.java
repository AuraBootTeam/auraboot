package com.auraboot.framework.meta.ddl;

import com.auraboot.framework.meta.dto.FieldDefinition;
import org.junit.jupiter.api.Test;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostgresDdlDialectTest {

    private final PostgresDdlDialect dialect = new PostgresDdlDialect();

    @Test
    void storesMultipleAttachmentsAsJsonbWithoutChangingScalarFileIdentifiers() {
        assertEquals("jsonb", dialect.mapDataType(FieldDefinition.builder().code("delivery_files")
                .dataType("file").extraProps(java.util.Map.of("multiple", true)).build()));
        assertEquals("VARCHAR(255)", dialect.mapDataType(FieldDefinition.builder().code("source_file")
                .dataType("file").extraProps(java.util.Map.of("multiple", false)).build()));
    }

    @Test
    void preservesImageUrlsDuringLegacyPluginSchemaPublication() {
        assertEquals("VARCHAR(2048)", dialect.mapDataType(FieldDefinition.builder()
                .code("class_image").dataType("IMAGE").maxLength(2048).build()));
        assertEquals("VARCHAR(255)", dialect.mapDataType(FieldDefinition.builder()
                .code("class_image").dataType("image").build()));
        assertEquals("'/static/class/owner''s-image.png'",
                dialect.formatDefaultValue("/static/class/owner's-image.png", "IMAGE"));
    }

    @Test
    void preservesExistingPluginFileIdentifiersAsStringColumns() {
        assertEquals("VARCHAR(32)", dialect.mapDataType(FieldDefinition.builder()
                .code("source_file").dataType("file").maxLength(32).build()));
        assertEquals("'01M40R49GX8JJAH5KGNKGP5FJX'",
                dialect.formatDefaultValue("01M40R49GX8JJAH5KGNKGP5FJX", "file"));
    }

    @Test
    void preservesConfiguredLengthForEnumAndReferenceCodes() {
        for (String type : new String[]{"enum", "reference"}) {
            assertEquals("VARCHAR(32)", dialect.mapDataType(FieldDefinition.builder()
                    .code("code").dataType(type).maxLength(32).build()));
        }
    }

    @Test
    void mapsKnownCodeTypesWithoutUnknownTypeWarnings() {
        Logger logger = (Logger) LoggerFactory.getLogger(PostgresDdlDialect.class);
        ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> events = new ListAppender<>();
        events.start();
        logger.addAppender(events);
        try {
            for (String type : new String[]{"enum", "reference"}) {
                assertEquals("VARCHAR(255)", dialect.mapDataType(FieldDefinition.builder()
                        .code("code").dataType(type).build()));
            }
            assertTrue(events.list.stream().noneMatch(event ->
                    event.getFormattedMessage().contains("Unknown data type")));
        } finally {
            logger.detachAppender(events);
            events.stop();
        }
    }

    @Test
    void quotesEnumAndReferenceDefaultsAsStoredStringValues() {
        for (String type : new String[]{"enum", "reference"}) {
            assertEquals("'owner''s-code'", dialect.formatDefaultValue("owner's-code", type));
        }
    }

    @Test
    void preservesNumericLookingDictionaryCodesAsStrings() {
        assertEquals("'0'", dialect.formatDefaultValue(0, "enum"));
    }

    @Test
    void rejectsUnsupportedTypesInsteadOfChangingTheirStorage() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> dialect.mapDataType(FieldDefinition.builder()
                        .code("broken").dataType("unsupported_code").build()));
        assertTrue(error.getMessage().contains("unsupported_code"));
    }

    @Test
    void rejectsMissingTypeBeforeGeneratingDdl() {
        assertThrows(IllegalArgumentException.class,
                () -> dialect.mapDataType(FieldDefinition.builder().code("broken").build()));
    }

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

    // ===== Default-value hardening: date/time literals must be escaped =====

    @Test
    void quotesDateDefaultsAsEscapedLiterals() {
        assertEquals("'2026-01-31'", dialect.formatDefaultValue("2026-01-31", "date"));
        assertEquals("'2026-01-31 09:30:00'", dialect.formatDefaultValue("2026-01-31 09:30:00", "datetime"));
    }

    @Test
    void keepsCurrentTimestampSpecialCaseForDateDefaults() {
        assertEquals("current_timestamp", dialect.formatDefaultValue("NOW()", "datetime"));
        assertEquals("current_timestamp", dialect.formatDefaultValue("current_timestamp", "timestamp"));
    }

    @Test
    void escapesQuoteInjectionInDateDefaults() {
        // A quote in the value must be doubled, so the payload cannot escape
        // the string literal and append arbitrary DDL after the default.
        assertEquals("'2026-01-01''); DROP TABLE ab_users;--'",
                dialect.formatDefaultValue("2026-01-01'); DROP TABLE ab_users;--", "date"));
    }

    @Test
    void quotesTimeDefaultsAsEscapedLiterals() {
        assertEquals("'08:30:00'", dialect.formatDefaultValue("08:30:00", "time"));
        assertEquals("'08''30''00'",
                dialect.formatDefaultValue("08'30'00", "time"));
    }

    // ===== Default-value hardening: numeric defaults must parse strictly =====

    @Test
    void formatsIntegerAndLongDefaultsThroughStrictParsing() {
        assertEquals("42", dialect.formatDefaultValue(42, "integer"));
        assertEquals("42", dialect.formatDefaultValue(" 42 ", "long"));
        assertEquals("-7", dialect.formatDefaultValue("-7", "integer"));
    }

    @Test
    void rejectsNonNumericIntegerDefaultsInsteadOfSplicingThemIntoDdl() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> dialect.formatDefaultValue("42; DROP TABLE ab_users;--", "integer"));
        assertTrue(error.getMessage().contains("Invalid integer default value"));
    }

    @Test
    void formatsDecimalDefaultsAndNormalizesScientificNotation() {
        assertEquals("3.14", dialect.formatDefaultValue("3.14", "decimal"));
        assertEquals("1000", dialect.formatDefaultValue("1e3", "money"));
    }

    @Test
    void rejectsNonNumericDecimalDefaultsInsteadOfSplicingThemIntoDdl() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> dialect.formatDefaultValue("0) ; DROP TABLE ab_users;--", "decimal"));
        assertTrue(error.getMessage().contains("Invalid numeric default value"));
    }

    @Test
    void keepsBooleanDefaultsAsBareLiterals() {
        assertEquals("true", dialect.formatDefaultValue("true", "boolean"));
        assertEquals("false", dialect.formatDefaultValue("anything-unparseable", "boolean"));
    }

    @Test
    void rejectsArrayAndUnknownTypeDefaultsInsteadOfSplicingThemIntoDdl() {
        IllegalArgumentException arrayError = assertThrows(IllegalArgumentException.class,
                () -> dialect.formatDefaultValue("{'a'}; DROP TABLE ab_users;--", "array"));
        assertTrue(arrayError.getMessage().contains("Unsupported default value"));

        IllegalArgumentException unknownError = assertThrows(IllegalArgumentException.class,
                () -> dialect.formatDefaultValue("x", "no_such_type"));
        assertTrue(unknownError.getMessage().contains("Unsupported default value"));
    }

}

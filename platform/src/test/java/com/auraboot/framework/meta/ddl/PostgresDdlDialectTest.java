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

}

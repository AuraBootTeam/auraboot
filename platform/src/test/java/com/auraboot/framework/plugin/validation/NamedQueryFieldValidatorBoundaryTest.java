package com.auraboot.framework.plugin.validation;

import com.auraboot.framework.meta.dto.NamedQueryFieldRequest;
import com.auraboot.framework.plugin.dto.imports.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class NamedQueryFieldValidatorBoundaryTest {
    private final NamedQueryFieldValidator validator = new NamedQueryFieldValidator();

    @ParameterizedTest
    @ValueSource(strings = {"en-US", "tr-TR"})
    void uppercaseTypesAreIndependentOfTheDefaultLocale(String localeTag) {
        var previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag(localeTag));
            var field = field("name", "name", "STRING");
            assertEquals(List.of(), validate(Arrays.asList(null, field)));
        } finally { Locale.setDefault(previous); }
    }

    @Test
    void blankRequiredPropertiesUseFallbackQueryCodeAndExactFieldIndex() {
        var fields = List.of(field(" ", "name", "string"), field("amount", " ", " "));
        var messages = validate(fields);
        assertEquals(List.of("S-NQF-CODE", "S-NQF-EXPR", "S-NQF-TYPE"), messages.stream().map(PluginValidationMessage::getCode).toList());
        assertEquals(List.of("namedQueries[0](unnamed).fields[0]", "namedQueries[0](unnamed).fields[1]", "namedQueries[0](unnamed).fields[1]"),
            messages.stream().map(PluginValidationMessage::getPath).toList());
    }
    private NamedQueryFieldRequest field(String code, String expr, String type) {
        var field = new NamedQueryFieldRequest(); field.setFieldCode(code); field.setColumnExpr(expr); field.setDataType(type); return field;
    }
    private List<PluginValidationMessage> validate(List<NamedQueryFieldRequest> fields) {
        var query = new NamedQueryDefinitionDTO(); query.setFields(fields);
        var manifest = new PluginManifestExtended(); manifest.setNamedQueries(List.of(query));
        return validator.validate(PluginValidationContext.builder().manifest(manifest).build());
    }
}

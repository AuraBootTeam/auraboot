package com.auraboot.framework.meta.converter;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class PageSchemaConverterTitleTest {
    private final ObjectMapper json = new ObjectMapper();
    private PageSchemaConverter converter() {
        var converter = Mappers.getMapper(PageSchemaConverter.class);
        ReflectionTestUtils.setField(converter, "objectMapper", json);
        return converter;
    }
    @Test void retainsGeneratedLocalizedJsonInsteadOfEncodingItAsAnEnglishLiteral() throws Exception {
        var codec = converter();
        String generated = "{\"zh-CN\":\"客户跟进列表\",\"en-US\":\"Customer follow-up list\"}";
        String stored = codec.titleValueToJsonb(generated);
        assertThat(json.readTree(stored)).isEqualTo(json.readTree(generated));
        assertThat(codec.jsonbTitleToString(stored)).isEqualTo("客户跟进列表");
    }
    @Test void preservesMapAndPlainStringTitleContracts() throws Exception {
        var codec = converter();
        assertThat(json.readTree(codec.titleValueToJsonb(Map.of("en", "Customers"))).get("en").asText())
                .isEqualTo("Customers");
        assertThat(json.readTree(codec.titleValueToJsonb("Customers")).get("en").asText())
                .isEqualTo("Customers");
        assertThat(json.readTree(codec.titleValueToJsonb("{literal")).get("en").asText())
                .isEqualTo("{literal");
    }
    @Test void retainsNonLocaleJsonObjectAsLiteralTitle() throws Exception {
        assertThat(json.readTree(converter().titleValueToJsonb("{\"foo\":\"bar\"}")).get("en").asText())
                .isEqualTo("{\"foo\":\"bar\"}");
    }
    @Test void retainsEmptyObjectAsLiteralTitle() throws Exception {
        assertThat(json.readTree(converter().titleValueToJsonb("{}")).get("en").asText()).isEqualTo("{}");
    }

}

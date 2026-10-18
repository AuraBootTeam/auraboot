package com.auraboot.framework.application.config;

import io.swagger.v3.core.util.Json31;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class NullableReferenceOpenApiCustomizerTest {
    private final NullableReferenceOpenApiCustomizer customizer = new NullableReferenceOpenApiCustomizer();

    @Test
    void nullableReferenceBecomesAnActualUnionInsteadOfConjoinedConstraints() {
        var reference = new Schema<>().$ref("#/components/schemas/CacheInfo").nullable(true);
        var result = Json31.mapper().valueToTree(customizer.nullableReference(reference));
        assertThat(result.at("/anyOf/0/$ref").asText()).isEqualTo("#/components/schemas/CacheInfo");
        assertThat(result.at("/anyOf/1/type").asText()).isEqualTo("null");
        assertThat(result.at("/anyOf/0/type").isMissingNode()).isTrue();
        assertThat(result.at("/$ref").isMissingNode()).isTrue();
    }

    @Test
    void alreadySerializedNullableReferenceRetainsOtherConstraintsInsideItsBranch() {
        var reference = new Schema<>().$ref("#/components/schemas/CacheInfo").description("Cache metadata");
        reference.setTypes(Set.of("object", "null"));
        var result = Json31.mapper().valueToTree(customizer.nullableReference(reference));
        assertThat(result.at("/anyOf/0/type").asText()).isEqualTo("object");
        assertThat(result.at("/anyOf/0/description").asText()).isEqualTo("Cache metadata");
        assertThat(result.at("/anyOf/1/type").asText()).isEqualTo("null");
    }

    @Test
    void assembledGraphTransformsSharedNullableReferencesInPropertiesArraysAndMaps() {
        var reference = new Schema<>().$ref("#/components/schemas/CacheInfo");
        reference.setTypes(Set.of("null"));
        var root = new Schema<>().type("object")
                .addProperty("cacheInfo", reference)
                .addProperty("copies", new ArraySchema().items(reference))
                .addProperty("lookup", new Schema<>().additionalProperties(reference));
        var document = new OpenAPI().components(new Components().addSchemas("Result", root).addSchemas("CacheInfo", new Schema<>().type("object")));
        customizer.customise(document);
        var result = Json31.mapper().valueToTree(document);
        for (var pointer : new String[]{"/components/schemas/Result/properties/cacheInfo", "/components/schemas/Result/properties/copies/items", "/components/schemas/Result/properties/lookup/additionalProperties"}) {
            assertThat(result.at(pointer + "/anyOf/0/$ref").asText()).isEqualTo("#/components/schemas/CacheInfo");
            assertThat(result.at(pointer + "/anyOf/1/type").asText()).isEqualTo("null");
        }
    }

    @Test
    void nonnullableReferencesAndNullableScalarsRemainUnchanged() {
        var reference = new Schema<>().$ref("#/components/schemas/CacheInfo");
        var scalar = new StringSchema().nullable(true);
        assertThat(customizer.nullableReference(reference)).isSameAs(reference);
        assertThat(customizer.nullableReference(scalar)).isSameAs(scalar);
    }
}

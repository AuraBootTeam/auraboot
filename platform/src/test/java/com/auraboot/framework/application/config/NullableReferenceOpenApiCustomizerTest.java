package com.auraboot.framework.application.config;

import io.swagger.v3.core.util.Json31;
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
        var result = Json31.mapper().valueToTree(customizer.customize(reference, null));
        assertThat(result.at("/anyOf/0/$ref").asText()).isEqualTo("#/components/schemas/CacheInfo");
        assertThat(result.at("/anyOf/1/type").asText()).isEqualTo("null");
        assertThat(result.at("/anyOf/0/type").isMissingNode()).isTrue();
        assertThat(result.at("/$ref").isMissingNode()).isTrue();
    }

    @Test
    void alreadySerializedNullableReferenceRetainsOtherConstraintsInsideItsBranch() {
        var reference = new Schema<>().$ref("#/components/schemas/CacheInfo").description("Cache metadata");
        reference.setTypes(Set.of("object", "null"));
        var result = Json31.mapper().valueToTree(customizer.customize(reference, null));
        assertThat(result.at("/anyOf/0/type").asText()).isEqualTo("object");
        assertThat(result.at("/anyOf/0/description").asText()).isEqualTo("Cache metadata");
        assertThat(result.at("/anyOf/1/type").asText()).isEqualTo("null");
    }

    @Test
    void nonnullableReferencesAndNullableScalarsRemainUnchanged() {
        var reference = new Schema<>().$ref("#/components/schemas/CacheInfo");
        var scalar = new StringSchema().nullable(true);
        assertThat(customizer.customize(reference, null)).isSameAs(reference);
        assertThat(customizer.customize(scalar, null)).isSameAs(scalar);
    }
}

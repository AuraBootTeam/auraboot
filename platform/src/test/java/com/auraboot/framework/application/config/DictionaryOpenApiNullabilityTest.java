package com.auraboot.framework.application.config;

import com.auraboot.framework.meta.controller.config.DictController;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import java.util.Arrays;
import com.auraboot.framework.meta.dto.DictDTO;
import com.auraboot.framework.meta.dto.DictDataResult;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.core.util.Json31;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DictionaryOpenApiNullabilityTest {
    @Test
    void explicitErrorDeclarationsKeepTheGenericSuccessResponse() {
        var responses = DictController.class.getAnnotation(ApiResponses.class);
        var success = Arrays.stream(responses.value())
                .filter(response -> response.responseCode().equals("200")).findFirst().orElseThrow();
        assertThat(success.useReturnTypeSchema()).isTrue();
    }

    @Test
    void nullableScalarsArraysAndReferencesAreRepresentedAsJsonSchemaNull() throws Exception {
        var schemas = new ModelConverters(true).readAll(DictDTO.class);
        var dict = Json31.mapper().valueToTree(schemas.get("DictDTO"));
        assertThat(dict.at("/properties/description/type").toString()).contains("null");
        assertThat(dict.at("/properties/items/type").toString()).contains("null");
        var data = Json31.mapper().valueToTree(new ModelConverters(true).readAll(DictDataResult.class).get("DictDataResult"));
        assertThat(data.at("/properties/cacheInfo").toString()).contains("null");
    }
}

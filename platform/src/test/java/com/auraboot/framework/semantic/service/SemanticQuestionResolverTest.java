package com.auraboot.framework.semantic.service;

import com.auraboot.framework.semantic.compiler.UserContext;
import com.auraboot.framework.semantic.dto.SemanticMetaResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** Hermetic catalog-resolution contract; permission and SQL execution remain IT. */
class SemanticQuestionResolverTest {
    private SemanticQuestionResolver resolver;
    private final UserContext user = new UserContext(2L, 1L, Map.of());

    @BeforeEach
    void setup() {
        var catalog = mock(SemanticCatalogService.class);
        var response = new SemanticMetaResponse();
        response.setModels(List.of(model("sales", "销售额", "销售城市"),
                model("inventory", "库存额", "库存仓库")));
        when(catalog.listCatalog(1L)).thenReturn(response);
        resolver = new SemanticQuestionResolver(catalog);
    }

    private SemanticMetaResponse.ModelMeta model(String code, String label, String dimensionLabel) {
        var model = new SemanticMetaResponse.ModelMeta();
        model.setCode(code);
        var metric = new SemanticMetaResponse.MetricMeta();
        metric.setCode("total");
        metric.setLabel(Map.of("zh-CN", label));
        model.setMetrics(List.of(metric));
        var dimension = new SemanticMetaResponse.DimensionMeta();
        dimension.setCode("location");
        dimension.setLabel(Map.of("zh-CN", dimensionLabel));
        model.setDimensions(List.of(dimension));
        return model;
    }

    @Test
    void uniqueMetricUsesItsOwnDimensions() {
        var result = resolver.resolve("销售额按销售城市", user).orElseThrow();
        assertThat(result.getMetrics()).containsExactly("sales.total");
        assertThat(result.getDimensions()).containsExactly("sales.location");
    }

    @Test
    void unqualifiedDuplicateMetricCodesAreAmbiguous() {
        assertThat(resolver.resolve("total", user)).isEmpty();
    }

    @Test
    void qualifiedMetricCodeDisambiguatesTheCatalog() {
        assertThat(resolver.resolve("inventory.total", user).orElseThrow().getMetrics())
                .containsExactly("inventory.total");
    }

    @Test
    void aDimensionFromAnotherModelDoesNotProduceAnInvalidQuery() {
        assertThat(resolver.resolve("销售额按库存仓库", user)).isEmpty();
    }

    @Test
    void unknownOrEmptyQuestionsDoNotInventMetrics() {
        assertThat(resolver.resolve("未知指标", user)).isEmpty();
        assertThat(resolver.resolve("", user)).isEmpty();
        assertThat(resolver.resolve(null, user)).isEmpty();
    }
}

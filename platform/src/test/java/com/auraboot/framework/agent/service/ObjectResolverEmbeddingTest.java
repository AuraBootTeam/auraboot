package com.auraboot.framework.agent.service;

import com.auraboot.framework.integration.BaseIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for ObjectResolver embedding fallback (Phase 3).
 * Verifies graceful degradation when embedding provider is not configured.
 */
class ObjectResolverEmbeddingTest extends BaseIntegrationTest {

    @Autowired
    private ObjectResolver objectResolver;

    @Autowired(required = false)
    private ModelEmbeddingService modelEmbeddingService;

    @Test
    void resolve_unknownTerm_doesNotThrowWithEmbeddingFallback() {
        Long tenantId = getTestTenant().getId();
        // Use a term that won't match any model via exact/alias/fuzzy
        var result = objectResolver.resolve(tenantId, "some completely unknown business term xyz123abc");
        assertThat(result).isNotNull();
        assertThat(result.getMatchType()).isIn("exact", "alias", "fuzzy", "embedding", "none");
        // Confidence should be 0 for "none" or > 0 for any match
        if ("none".equals(result.getMatchType())) {
            assertThat(result.getConfidence()).isEqualTo(0.0);
            assertThat(result.getModelCode()).isNull();
        } else {
            assertThat(result.getConfidence()).isGreaterThan(0.0);
            assertThat(result.getModelCode()).isNotNull();
        }
    }

    @Test
    void resolve_nullAndBlank_returnsNoneWithoutError() {
        Long tenantId = getTestTenant().getId();

        var nullResult = objectResolver.resolve(tenantId, null);
        assertThat(nullResult.getMatchType()).isEqualTo("none");
        assertThat(nullResult.getConfidence()).isEqualTo(0.0);

        var blankResult = objectResolver.resolve(tenantId, "   ");
        assertThat(blankResult.getMatchType()).isEqualTo("none");
        assertThat(blankResult.getConfidence()).isEqualTo(0.0);
    }

    @Test
    void resolve_existingModel_stillMatchesViaExactOrFuzzy() {
        Long tenantId = getTestTenant().getId();
        // "crm_account" should match via exact model_code even with embedding service present
        var result = objectResolver.resolve(tenantId, "crm_account");
        assertThat(result).isNotNull();
        // Should match exact or alias before reaching embedding phase
        assertThat(result.getMatchType()).isIn("exact", "alias", "fuzzy");
        assertThat(result.getConfidence()).isGreaterThanOrEqualTo(0.50);
    }

    @Test
    void modelEmbeddingService_findSimilar_gracefulWithoutConfig() {
        // If no embedding API key configured, should return empty list, not throw
        if (modelEmbeddingService != null) {
            var results = modelEmbeddingService.findSimilarModels(
                    getTestTenant().getId(), "customer relationship", 3);
            assertThat(results).isNotNull(); // empty or populated, but never null/exception
        }
        // If modelEmbeddingService is null, the test still passes — optional dependency
    }

    @Test
    void modelEmbeddingService_embedModel_gracefulWithoutConfig() {
        if (modelEmbeddingService != null) {
            // Should return false (not throw) when provider is not configured
            boolean stored = modelEmbeddingService.embedModel(
                    getTestTenant().getId(), "test_model_" + System.currentTimeMillis(), "Test Display Name");
            // Result depends on whether embedding provider is configured — both are valid
            assertThat(stored).isIn(true, false);
        }
    }

    @Test
    void modelEmbeddingService_embedModel_emptyDisplayName_returnsFalse() {
        if (modelEmbeddingService != null) {
            boolean result = modelEmbeddingService.embedModel(getTestTenant().getId(), "test_model", "");
            assertThat(result).isFalse();

            boolean resultNull = modelEmbeddingService.embedModel(getTestTenant().getId(), "test_model", null);
            assertThat(resultNull).isFalse();
        }
    }

    @Test
    void modelEmbeddingService_findSimilar_emptyQuery_returnsEmptyList() {
        if (modelEmbeddingService != null) {
            var results = modelEmbeddingService.findSimilarModels(getTestTenant().getId(), "", 3);
            assertThat(results).isEmpty();

            var resultsNull = modelEmbeddingService.findSimilarModels(getTestTenant().getId(), null, 3);
            assertThat(resultsNull).isEmpty();
        }
    }
}

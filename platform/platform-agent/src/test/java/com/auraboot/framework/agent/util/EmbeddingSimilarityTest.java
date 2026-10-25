package com.auraboot.framework.agent.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link EmbeddingSimilarity}.
 */
class EmbeddingSimilarityTest {

    @Test
    @DisplayName("identical vectors → 1.0")
    void identical() {
        double[] v = {1.0, 2.0, 3.0};
        assertThat(EmbeddingSimilarity.cosine(v, v))
                .isEqualTo(1.0d, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    @DisplayName("orthogonal vectors → 0.0")
    void orthogonal() {
        double[] a = {1.0, 0.0};
        double[] b = {0.0, 1.0};
        assertThat(EmbeddingSimilarity.cosine(a, b)).isZero();
    }

    @Test
    @DisplayName("opposite vectors → -1.0")
    void opposite() {
        double[] a = {1.0, 2.0, 3.0};
        double[] b = {-1.0, -2.0, -3.0};
        assertThat(EmbeddingSimilarity.cosine(a, b))
                .isEqualTo(-1.0d, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    @DisplayName("null / empty / length-mismatch → 0.0")
    void nullsAndMismatches() {
        assertThat(EmbeddingSimilarity.cosine((double[]) null, new double[]{1})).isZero();
        assertThat(EmbeddingSimilarity.cosine(new double[]{1}, (double[]) null)).isZero();
        assertThat(EmbeddingSimilarity.cosine(new double[0], new double[0])).isZero();
        assertThat(EmbeddingSimilarity.cosine(new double[]{1, 2}, new double[]{1})).isZero();
    }

    @Test
    @DisplayName("zero-norm vector → 0.0 (no NaN)")
    void zeroNorm() {
        assertThat(EmbeddingSimilarity.cosine(new double[]{0, 0, 0}, new double[]{1, 2, 3})).isZero();
    }

    @Test
    @DisplayName("float[] overload behaves identically")
    void floatOverload() {
        float[] a = {1.0f, 2.0f, 3.0f};
        float[] b = {1.0f, 2.0f, 3.0f};
        assertThat(EmbeddingSimilarity.cosine(a, b))
                .isEqualTo(1.0d, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(EmbeddingSimilarity.cosine((float[]) null, a)).isZero();
    }
}

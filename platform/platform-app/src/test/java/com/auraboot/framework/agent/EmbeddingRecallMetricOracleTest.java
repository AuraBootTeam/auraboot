package com.auraboot.framework.agent;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class EmbeddingRecallMetricOracleTest {
    @Test
    void rankAndRecallDoNotTreatAnyReturnedNeighborAsRelevant() {
        assertThat(MemoryEmbeddingRecallQualityLiveIT.recall(List.of("wrong", "right"), Set.of("right"), 1)).isZero();
        assertThat(MemoryEmbeddingRecallQualityLiveIT.recall(List.of("wrong", "right"), Set.of("right"), 5)).isEqualTo(1);
        assertThat(MemoryEmbeddingRecallQualityLiveIT.reciprocalRank(List.of("wrong", "right"), Set.of("right"), 5)).isEqualTo(.5);
        assertThat(MemoryEmbeddingRecallQualityLiveIT.recall(List.of("right", "right"), Set.of("right", "other"), 5)).isEqualTo(.5);
        assertThat(MemoryEmbeddingRecallQualityLiveIT.reciprocalRank(List.of("unrelated"), Set.of(), 5)).isZero();
    }
}

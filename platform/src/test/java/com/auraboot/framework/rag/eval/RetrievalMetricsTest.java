package com.auraboot.framework.rag.eval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("RetrievalMetrics — pure function unit tests")
class RetrievalMetricsTest {

    @Test
    @DisplayName("recall throws when expected is empty (use noAnswerRate instead)")
    void recallThrowsOnEmptyExpected() {
        assertThrows(IllegalArgumentException.class,
                () -> RetrievalMetrics.recall(List.of("a"), Set.of()));
    }

    @Test
    @DisplayName("recall = 1.0 when all expected appear in retrieved")
    void recallFull() {
        assertEquals(1.0,
                RetrievalMetrics.recall(List.of("a", "b", "c"), Set.of("a", "b")),
                1e-9);
    }

    @Test
    @DisplayName("recall = 0.5 when half of expected found")
    void recallPartial() {
        assertEquals(0.5,
                RetrievalMetrics.recall(List.of("a", "x"), Set.of("a", "b")),
                1e-9);
    }

    @Test
    @DisplayName("recall = 0.0 when nothing matches")
    void recallZero() {
        assertEquals(0.0,
                RetrievalMetrics.recall(List.of("x", "y"), Set.of("a", "b")),
                1e-9);
    }

    @Test
    @DisplayName("recall handles null retrieved as empty")
    void recallNullRetrieved() {
        assertEquals(0.0,
                RetrievalMetrics.recall(null, Set.of("a")),
                1e-9);
    }

    @Test
    @DisplayName("precision throws when expected is empty")
    void precisionThrowsOnEmptyExpected() {
        assertThrows(IllegalArgumentException.class,
                () -> RetrievalMetrics.precision(List.of("a"), Set.of()));
    }

    @Test
    @DisplayName("precision = 1.0 when every retrieved item is in expected")
    void precisionFull() {
        assertEquals(1.0,
                RetrievalMetrics.precision(List.of("a", "b"), Set.of("a", "b", "c")),
                1e-9);
    }

    @Test
    @DisplayName("precision = 0.5 when half of retrieved are hits")
    void precisionPartial() {
        assertEquals(0.5,
                RetrievalMetrics.precision(List.of("a", "x"), Set.of("a", "b")),
                1e-9);
    }

    @Test
    @DisplayName("precision = 0.0 when retrieved is empty")
    void precisionEmptyRetrieved() {
        assertEquals(0.0,
                RetrievalMetrics.precision(List.of(), Set.of("a")),
                1e-9);
    }

    @Test
    @DisplayName("correctNoAnswer true on empty retrieved")
    void correctNoAnswerEmpty() {
        assertTrue(RetrievalMetrics.correctNoAnswer(List.of()));
        assertTrue(RetrievalMetrics.correctNoAnswer(null));
    }

    @Test
    @DisplayName("correctNoAnswer false on non-empty retrieved (false positive)")
    void correctNoAnswerNonEmpty() {
        assertFalse(RetrievalMetrics.correctNoAnswer(List.of("a")));
    }

    @Test
    @DisplayName("falsePositive is inverse of correctNoAnswer")
    void falsePositiveMirrorsCorrectNoAnswer() {
        assertFalse(RetrievalMetrics.falsePositive(List.of()));
        assertTrue(RetrievalMetrics.falsePositive(List.of("anything")));
    }
}

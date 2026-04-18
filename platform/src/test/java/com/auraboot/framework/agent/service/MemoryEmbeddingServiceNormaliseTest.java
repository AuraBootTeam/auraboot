package com.auraboot.framework.agent.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link MemoryEmbeddingService#normaliseForEmbedding(String)} (PR-74 / N6).
 *
 * <p>Normalisation is a pure static function — no Spring context needed. The
 * goal is to prove that trivially-different wordings reduce to the same
 * form so the downstream cross-user cosine clustering sees them as
 * near-neighbours.
 */
@DisplayName("MemoryEmbeddingService.normaliseForEmbedding (PR-74)")
class MemoryEmbeddingServiceNormaliseTest {

    @Test
    @DisplayName("case + trailing space + trailing period all collapse to one form")
    void englishVariants() {
        String a = MemoryEmbeddingService.normaliseForEmbedding("Prefer Vim");
        String b = MemoryEmbeddingService.normaliseForEmbedding("prefer vim ");
        String c = MemoryEmbeddingService.normaliseForEmbedding("prefer  Vim.");
        String d = MemoryEmbeddingService.normaliseForEmbedding("  PREFER VIM!  ");

        assertThat(a).isEqualTo("prefer vim");
        assertThat(b).isEqualTo("prefer vim");
        assertThat(c).isEqualTo("prefer vim");
        assertThat(d).isEqualTo("prefer vim");
    }

    @Test
    @DisplayName("Chinese content is not corrupted; whitespace trimmed + sentence punct stripped")
    void chineseContent() {
        String s = MemoryEmbeddingService.normaliseForEmbedding(" 月底 28 号结算 ");
        assertThat(s).isEqualTo("月底 28 号结算");

        // Trailing Chinese fullwidth period should be stripped.
        String s2 = MemoryEmbeddingService.normaliseForEmbedding("月底 28 号结算。");
        assertThat(s2).isEqualTo("月底 28 号结算");

        // Surrounding Chinese corner brackets are stripped as quotes.
        String s3 = MemoryEmbeddingService.normaliseForEmbedding("「月底 28 号结算」");
        assertThat(s3).isEqualTo("月底 28 号结算");
    }

    @Test
    @DisplayName("null / empty / whitespace-only inputs are safe")
    void emptyInputs() {
        assertThat(MemoryEmbeddingService.normaliseForEmbedding(null)).isEmpty();
        assertThat(MemoryEmbeddingService.normaliseForEmbedding("")).isEmpty();
        assertThat(MemoryEmbeddingService.normaliseForEmbedding("   ")).isEmpty();
        assertThat(MemoryEmbeddingService.normaliseForEmbedding("\t\n")).isEmpty();
    }

    @Test
    @DisplayName("matched ASCII + smart quotes around content are stripped")
    void quotedContent() {
        assertThat(MemoryEmbeddingService.normaliseForEmbedding("\"hello world\""))
                .isEqualTo("hello world");
        assertThat(MemoryEmbeddingService.normaliseForEmbedding("“hello world”"))
                .isEqualTo("hello world");
        assertThat(MemoryEmbeddingService.normaliseForEmbedding("'hello'"))
                .isEqualTo("hello");
    }

    @Test
    @DisplayName("multiple trailing punctuation are all stripped; interior punctuation preserved")
    void punctuation() {
        assertThat(MemoryEmbeddingService.normaliseForEmbedding("ok?!?"))
                .isEqualTo("ok");
        // Interior comma is semantic — keep it.
        assertThat(MemoryEmbeddingService.normaliseForEmbedding("apples, bananas"))
                .isEqualTo("apples, bananas");
    }
}

package com.auraboot.framework.rag.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * G2: CJK bigram segmentation so PostgreSQL 'simple' tsvector/tsquery gets
 * meaningful Chinese tokens instead of per-character noise.
 */
class CjkBigramSegmenterTest {

    @Test
    void pureChinese_becomesOverlappingBigrams() {
        assertThat(CjkBigramSegmenter.segment("命令执行"))
                .isEqualTo("命令 令执 执行");
    }

    @Test
    void singleCjkChar_keptAsUnigram() {
        assertThat(CjkBigramSegmenter.segment("权")).isEqualTo("权");
    }

    @Test
    void asciiText_passesThroughUnchanged() {
        assertThat(CjkBigramSegmenter.segment("permission denied error"))
                .isEqualTo("permission denied error");
    }

    @Test
    void mixedText_segmentsOnlyCjkRuns() {
        assertThat(CjkBigramSegmenter.segment("BPM审批流程config"))
                .isEqualTo("BPM 审批 批流 流程 config");
    }

    @Test
    void punctuationBreaksCjkRuns() {
        assertThat(CjkBigramSegmenter.segment("权限,菜单"))
                .isEqualTo("权限 , 菜单");
    }

    @Test
    void nullAndBlank_returnEmpty() {
        assertThat(CjkBigramSegmenter.segment(null)).isEmpty();
        assertThat(CjkBigramSegmenter.segment("   ")).isEmpty();
    }

    @Test
    void whitespaceRunsCollapse() {
        assertThat(CjkBigramSegmenter.segment("hello   world")).isEqualTo("hello world");
    }

    @Test
    void tsQueryTerms_chineseQuery_yieldsBigramTerms() {
        assertThat(CjkBigramSegmenter.tsQueryTerms("权限管理"))
                .containsExactly("权限", "限管", "管理");
    }

    @Test
    void tsQueryTerms_mixedQuery_yieldsWordsAndBigrams() {
        assertThat(CjkBigramSegmenter.tsQueryTerms("如何配置 BPM workflow"))
                .containsExactly("如何", "何配", "配置", "BPM", "workflow");
    }

    @Test
    void tsQueryTerms_dropsPunctuation() {
        assertThat(CjkBigramSegmenter.tsQueryTerms("权限? (admin)"))
                .containsExactly("权限", "admin");
    }
}

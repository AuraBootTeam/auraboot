package com.auraboot.framework.rag.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * G10: keyword-coverage is the normalized relevance signal the rejection floor
 * thresholds on. These cases pin the separation the eval relies on — a genuine
 * multi-term match scores near 1.0 while an off-topic query that shares only one
 * incidental term with the content scores a small fraction.
 */
class KeywordCoverageTest {

    @Test
    void exactMultiTermMatch_scoresFull() {
        // "数据字典" → bigrams {数据, 据字, 字典}; content contains all three.
        assertThat(KeywordCoverage.coverage("数据字典", "本章介绍数据字典与字段元数据的配置"))
                .isEqualTo(1.0);
    }

    @Test
    void latinExactMatch_scoresFull() {
        assertThat(KeywordCoverage.coverage("permission denied",
                "the request failed with permission denied for this user"))
                .isEqualTo(1.0);
    }

    @Test
    void offTopicQuerySharingOneIncidentalTerm_scoresLow() {
        // "GraphQL 接口" → {graphql, 接口}; a platform doc has 接口 everywhere but
        // never graphql → only 1/2 covered. This is the no-answer false positive.
        assertThat(KeywordCoverage.coverage("GraphQL 接口", "平台提供 REST 接口用于命令执行"))
                .isCloseTo(0.5, within(1e-9));
    }

    @Test
    void offTopicQueryFewIncidentalHits_scoresVeryLow() {
        // "如何配置人脸识别登录" → 9 bigrams {如何,何配,配置,置人,人脸,脸识,识别,别登,登录};
        // an unrelated login-config doc shares only 配置 + 登录 → 2/9.
        double c = KeywordCoverage.coverage("如何配置人脸识别登录", "本节说明单点登录的配置项与会话超时");
        assertThat(c).isCloseTo(2.0 / 9.0, within(1e-9));
    }

    @Test
    void completelyUnrelated_scoresZero() {
        assertThat(KeywordCoverage.coverage("炒菜步骤", "command execution pipeline and audit log"))
                .isEqualTo(0.0);
    }

    @Test
    void blankOrTermlessQuery_scoresZero() {
        assertThat(KeywordCoverage.coverage("", "anything")).isEqualTo(0.0);
        assertThat(KeywordCoverage.coverage("   ", "anything")).isEqualTo(0.0);
        assertThat(KeywordCoverage.coverage(",.!", "anything")).isEqualTo(0.0);
    }

    @Test
    void blankContent_scoresZero() {
        assertThat(KeywordCoverage.coverage("数据字典", "")).isEqualTo(0.0);
        assertThat(KeywordCoverage.coverage("数据字典", null)).isEqualTo(0.0);
    }
}

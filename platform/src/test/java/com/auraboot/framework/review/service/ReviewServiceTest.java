package com.auraboot.framework.review.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.review.dto.ReviewCreateRequest;
import com.auraboot.framework.review.dto.ReviewResponse;
import com.auraboot.framework.review.dto.ReviewSummaryResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ReviewService")
class ReviewServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    private ReviewService service;

    @BeforeEach
    void setUp() {
        service = new ReviewService(jdbcTemplate);
        MetaContext.setContext(10L, 20L, "usr-20", "reviewer");
    }

    @AfterEach
    void tearDown() {
        MetaContext.clear();
    }

    @Test
    @DisplayName("summarize normalizes target type and returns rating distribution")
    void summarizeNormalizesTargetType() {
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(Map.of(
                "total_count", 3L,
                "average_rating", new BigDecimal("4.33"),
                "rating_1", 0L,
                "rating_2", 0L,
                "rating_3", 1L,
                "rating_4", 0L,
                "rating_5", 2L
        )));

        ReviewSummaryResponse summary = service.summarize("MARKETPLACE_PLUGIN", "plugin-1");

        assertThat(summary.targetType()).isEqualTo("marketplace_plugin");
        assertThat(summary.targetId()).isEqualTo("plugin-1");
        assertThat(summary.totalCount()).isEqualTo(3);
        assertThat(summary.averageRating()).isEqualByComparingTo("4.33");
        assertThat(summary.distribution()).containsEntry(5, 2L).containsEntry(3, 1L);
    }

    @Test
    @DisplayName("createReview stores top-level marketplace review and refreshes aggregate stats")
    void createReviewStoresTopLevelReview() {
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of())
                .thenReturn(List.of(reviewRow("rev-1", null, 20L, 5, 0)));
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);

        ReviewResponse response = service.createReview(new ReviewCreateRequest(
                "MARKETPLACE_PLUGIN",
                "plugin-1",
                null,
                5,
                "Useful plugin",
                "Works for Commerce reviews"));

        assertThat(response.pid()).isEqualTo("rev-1");
        assertThat(response.targetType()).isEqualTo("marketplace_plugin");
        assertThat(response.rating()).isEqualTo(5);
        assertThat(response.owner()).isTrue();
        verify(jdbcTemplate).update(contains("UPDATE ab_marketplace_plugin"), any(Object[].class));
    }

    @Test
    @DisplayName("createReview requires rating for marketplace plugin top-level reviews")
    void createReviewRequiresMarketplaceRating() {
        assertThatThrownBy(() -> service.createReview(new ReviewCreateRequest(
                "marketplace-plugin",
                "plugin-1",
                null,
                null,
                null,
                "Missing rating")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Rating is required");
    }

    @Test
    @DisplayName("createReview stores reply against parent review")
    void createReviewStoresReply() {
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(Map.of(
                        "pid", "parent-1",
                        "target_type", "marketplace_plugin",
                        "target_id", "plugin-1",
                        "depth", 0
                )))
                .thenReturn(List.of(reviewRow("reply-1", "parent-1", 20L, null, 0)));
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);

        ReviewResponse response = service.createReview(new ReviewCreateRequest(
                "marketplace_plugin",
                "plugin-1",
                "parent-1",
                5,
                null,
                "Reply content"));

        assertThat(response.pid()).isEqualTo("reply-1");
        assertThat(response.parentId()).isEqualTo("parent-1");
        assertThat(response.rating()).isNull();
        verify(jdbcTemplate).update(contains("reply_count = reply_count + 1"), any(Object[].class));
    }

    @Test
    @DisplayName("vote normalizes vote type and is idempotent")
    void voteNormalizesAndReturnsFalseWhenAlreadyVoted() {
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(Map.of("pid", "rev-1")));
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(0);

        boolean result = service.vote("rev-1", "HELPFUL");

        assertThat(result).isFalse();
        verify(jdbcTemplate, times(1)).update(anyString(), any(Object[].class));
    }

    @Test
    @DisplayName("listReviews uses newest ordering when requested")
    void listReviewsUsesNewestOrdering() {
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(reviewRow("rev-1", null, 21L, 4, 9)));

        List<ReviewResponse> reviews = service.listReviews("MARKETPLACE_PLUGIN", "plugin-1", "newest");

        assertThat(reviews).hasSize(1);
        assertThat(reviews.get(0).owner()).isFalse();
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).queryForList(sql.capture(), any(Object[].class));
        assertThat(sql.getValue()).contains("ORDER BY created_at DESC");
    }

    private Map<String, Object> reviewRow(String pid, String parentId, Long userId, Integer rating, int helpfulCount) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("pid", pid);
        row.put("target_type", "marketplace_plugin");
        row.put("target_id", "plugin-1");
        row.put("parent_id", parentId);
        row.put("user_id", userId);
        row.put("user_name", String.valueOf(userId));
        row.put("rating", rating);
        row.put("title", "Review title");
        row.put("content", "Review content");
        row.put("helpful_count", helpfulCount);
        row.put("reply_count", 0);
        row.put("created_at", Timestamp.from(Instant.parse("2026-05-30T00:00:00Z")));
        row.put("updated_at", Timestamp.from(Instant.parse("2026-05-30T00:00:01Z")));
        return row;
    }
}

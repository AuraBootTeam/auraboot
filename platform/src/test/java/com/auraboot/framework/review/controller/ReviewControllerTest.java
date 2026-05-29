package com.auraboot.framework.review.controller;

import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.review.dto.ReviewCreateRequest;
import com.auraboot.framework.review.dto.ReviewResponse;
import com.auraboot.framework.review.dto.ReviewSummaryResponse;
import com.auraboot.framework.review.service.ReviewService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("ReviewController")
class ReviewControllerTest {

    private final ReviewService reviewService = mock(ReviewService.class);
    private final ReviewController controller = new ReviewController(reviewService);

    @Test
    @DisplayName("listReviews returns ApiResponse data from service")
    void listReviewsReturnsServiceData() {
        ReviewResponse review = review("rev-1");
        when(reviewService.listReviews("marketplace_plugin", "plugin-1", "helpful"))
                .thenReturn(List.of(review));

        ApiResponse<List<ReviewResponse>> response =
                controller.listReviews("marketplace_plugin", "plugin-1", "helpful");

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getData()).containsExactly(review);
    }

    @Test
    @DisplayName("summarize returns ApiResponse data from service")
    void summarizeReturnsServiceData() {
        ReviewSummaryResponse summary = new ReviewSummaryResponse(
                "marketplace_plugin",
                "plugin-1",
                1,
                null,
                Map.of(5, 1L));
        when(reviewService.summarize("marketplace_plugin", "plugin-1")).thenReturn(summary);

        ApiResponse<ReviewSummaryResponse> response =
                controller.summarize("marketplace_plugin", "plugin-1");

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getData()).isSameAs(summary);
    }

    @Test
    @DisplayName("createReview returns created review")
    void createReviewReturnsCreatedReview() {
        ReviewCreateRequest request = new ReviewCreateRequest(
                "marketplace_plugin",
                "plugin-1",
                null,
                5,
                "Title",
                "Content");
        ReviewResponse review = review("rev-2");
        when(reviewService.createReview(request)).thenReturn(review);

        ApiResponse<ReviewResponse> response = controller.createReview(request);

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getData()).isSameAs(review);
    }

    @Test
    @DisplayName("vote returns service vote result")
    void voteReturnsServiceResult() {
        when(reviewService.vote("rev-1", "HELPFUL")).thenReturn(true);

        ApiResponse<Boolean> response = controller.vote("rev-1", "HELPFUL");

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getData()).isTrue();
    }

    private ReviewResponse review(String pid) {
        return new ReviewResponse(
                pid,
                "marketplace_plugin",
                "plugin-1",
                null,
                20L,
                "20",
                5,
                "Title",
                "Content",
                0,
                0,
                true,
                Instant.parse("2026-05-30T00:00:00Z"),
                Instant.parse("2026-05-30T00:00:01Z"));
    }
}

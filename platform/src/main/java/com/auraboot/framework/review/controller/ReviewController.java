package com.auraboot.framework.review.controller;

import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.review.dto.ReviewCreateRequest;
import com.auraboot.framework.review.dto.ReviewResponse;
import com.auraboot.framework.review.dto.ReviewSummaryResponse;
import com.auraboot.framework.review.service.ReviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/reviews")
@RequiredArgsConstructor
@Tag(name = "Reviews", description = "Generic review and rating API")
public class ReviewController {

    private final ReviewService reviewService;

    @GetMapping
    @Operation(summary = "List reviews for a target")
    public ApiResponse<List<ReviewResponse>> listReviews(
            @RequestParam String targetType,
            @RequestParam String targetId,
            @RequestParam(defaultValue = "helpful") String sort) {
        return ApiResponse.success(reviewService.listReviews(targetType, targetId, sort));
    }

    @GetMapping("/summary")
    @Operation(summary = "Get review summary for a target")
    public ApiResponse<ReviewSummaryResponse> summarize(
            @RequestParam String targetType,
            @RequestParam String targetId) {
        return ApiResponse.success(reviewService.summarize(targetType, targetId));
    }

    @PostMapping
    @Operation(summary = "Create a review or reply")
    public ApiResponse<ReviewResponse> createReview(@RequestBody ReviewCreateRequest request) {
        return ApiResponse.success(reviewService.createReview(request));
    }

    @PostMapping("/{pid}/vote")
    @Operation(summary = "Vote on a review")
    public ApiResponse<Boolean> vote(
            @PathVariable String pid,
            @RequestParam(defaultValue = "HELPFUL") String voteType) {
        return ApiResponse.success(reviewService.vote(pid, voteType));
    }
}

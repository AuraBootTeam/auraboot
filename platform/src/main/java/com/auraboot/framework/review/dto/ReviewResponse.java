package com.auraboot.framework.review.dto;

import java.time.Instant;

public record ReviewResponse(
        String pid,
        String targetType,
        String targetId,
        String parentId,
        Long userId,
        String userName,
        Integer rating,
        String title,
        String content,
        int helpfulCount,
        int replyCount,
        boolean owner,
        Instant createdAt,
        Instant updatedAt
) {
}

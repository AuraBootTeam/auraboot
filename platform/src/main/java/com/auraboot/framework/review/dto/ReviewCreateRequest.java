package com.auraboot.framework.review.dto;

public record ReviewCreateRequest(
        String targetType,
        String targetId,
        String parentId,
        Integer rating,
        String title,
        String content
) {
}

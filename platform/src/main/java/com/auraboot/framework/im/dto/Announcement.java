package com.auraboot.framework.im.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record Announcement(
        String content,
        Long updatedBy,
        String updatedByName,
        Instant updatedAt
) {}

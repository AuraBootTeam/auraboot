package com.auraboot.framework.workbench.dto;

import lombok.Data;

import java.time.Instant;

/**
 * DTO for user note responses.
 *
 * @since 6.5.0
 */
@Data
public class UserNoteDTO {

    private String content;
    private Instant updatedAt;
}

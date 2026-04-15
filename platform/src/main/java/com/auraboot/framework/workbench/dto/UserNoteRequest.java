package com.auraboot.framework.workbench.dto;

import lombok.Data;

/**
 * Request DTO for upserting user note.
 *
 * @since 6.5.0
 */
@Data
public class UserNoteRequest {

    private String content;
}

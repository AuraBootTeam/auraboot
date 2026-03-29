package com.auraboot.framework.workbench.service;

import com.auraboot.framework.workbench.dto.UserNoteDTO;

/**
 * Service for personal user notes on the workbench.
 *
 * @since 6.5.0
 */
public interface UserNoteService {

    /**
     * Get the current user's note.
     * Returns null content if no note exists yet.
     */
    UserNoteDTO getNote();

    /**
     * Create or update the current user's note.
     */
    UserNoteDTO upsert(String content);
}

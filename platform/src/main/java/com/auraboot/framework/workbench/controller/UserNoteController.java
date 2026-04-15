package com.auraboot.framework.workbench.controller;

import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.workbench.dto.UserNoteDTO;
import com.auraboot.framework.workbench.dto.UserNoteRequest;
import com.auraboot.framework.workbench.service.UserNoteService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * REST controller for personal user notes.
 *
 * @since 6.5.0
 */
@RestController
@RequestMapping("/api/user-notes")
@RequiredArgsConstructor
@Tag(name = "User Notes", description = "Personal quick notes for the workbench")
public class UserNoteController {

    private final UserNoteService userNoteService;

    /**
     * Get the current user's note.
     * GET /api/user-notes
     */
    @GetMapping
    public ApiResponse<UserNoteDTO> get() {
        return ApiResponse.success(userNoteService.getNote());
    }

    /**
     * Create or update the current user's note.
     * PUT /api/user-notes
     */
    @PutMapping
    public ApiResponse<UserNoteDTO> upsert(@RequestBody UserNoteRequest request) {
        return ApiResponse.success(userNoteService.upsert(request.getContent()));
    }
}

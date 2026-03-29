package com.auraboot.framework.workbench.controller;

import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.workbench.dto.AnnouncementDTO;
import com.auraboot.framework.workbench.dto.AnnouncementRequest;
import com.auraboot.framework.workbench.service.AnnouncementService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST controller for workbench announcements.
 *
 * @since 6.5.0
 */
@RestController
@RequestMapping("/api/announcements")
@RequiredArgsConstructor
@Tag(name = "Announcements", description = "Workbench announcement management")
public class AnnouncementController {

    private final AnnouncementService announcementService;

    /**
     * List active announcements.
     * GET /api/announcements?status=active&limit=20
     */
    @GetMapping
    public ApiResponse<List<AnnouncementDTO>> list(
            @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.success(announcementService.listActive(limit));
    }

    /**
     * Create a new announcement.
     * POST /api/announcements
     */
    @PostMapping
    public ApiResponse<AnnouncementDTO> create(@Valid @RequestBody AnnouncementRequest request) {
        return ApiResponse.success(announcementService.create(request));
    }

    /**
     * Update an announcement.
     * PUT /api/announcements/{id}
     */
    @PutMapping("/{id}")
    public ApiResponse<AnnouncementDTO> update(
            @PathVariable Long id,
            @Valid @RequestBody AnnouncementRequest request) {
        return ApiResponse.success(announcementService.update(id, request));
    }

    /**
     * Soft-delete an announcement.
     * DELETE /api/announcements/{id}
     */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        announcementService.delete(id);
        return ApiResponse.success();
    }
}

package com.auraboot.framework.meta.controller;

import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.meta.service.RecordCommentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Record Comment Controller (GAP-123)
 *
 * Provides REST API for record-level comments and activity history.
 * Comments are stored in ab_record_comment table with polymorphic
 * association via modelCode + recordPid.
 */
@Slf4j
@RestController
@RequestMapping("/api/records")
@RequiredArgsConstructor
@Tag(name = "Record Comments", description = "Record-level comments and activity history")
public class RecordCommentController {

    private final RecordCommentService commentService;

    @GetMapping("/{modelCode}/{recordPid}/comments")
    @Operation(summary = "List comments for a record")
    public ApiResponse<List<Map<String, Object>>> listComments(
            @PathVariable String modelCode,
            @PathVariable String recordPid) {
        return ApiResponse.success(commentService.listComments(modelCode, recordPid));
    }

    @PostMapping("/{modelCode}/{recordPid}/comments")
    @Operation(summary = "Add a comment to a record")
    public ApiResponse<Map<String, Object>> addComment(
            @PathVariable String modelCode,
            @PathVariable String recordPid,
            @RequestBody Map<String, Object> body) {
        String content = (String) body.get("content");
        String mentions = body.get("mentions") != null ? body.get("mentions").toString() : null;
        return ApiResponse.success(commentService.addComment(modelCode, recordPid, content, mentions));
    }

    @PutMapping("/{modelCode}/{recordPid}/comments/{commentId}")
    @Operation(summary = "Edit a comment")
    public ApiResponse<Map<String, Object>> editComment(
            @PathVariable String modelCode,
            @PathVariable String recordPid,
            @PathVariable Long commentId,
            @RequestBody Map<String, Object> body) {
        String content = (String) body.get("content");
        return ApiResponse.success(commentService.editComment(commentId, content));
    }

    @DeleteMapping("/{modelCode}/{recordPid}/comments/{commentId}")
    @Operation(summary = "Delete a comment")
    public ApiResponse<Boolean> deleteComment(
            @PathVariable String modelCode,
            @PathVariable String recordPid,
            @PathVariable Long commentId) {
        commentService.deleteComment(commentId);
        return ApiResponse.success(true);
    }

    @GetMapping("/{modelCode}/{recordPid}/activity")
    @Operation(summary = "List activity history for a record")
    public ApiResponse<List<Map<String, Object>>> listActivity(
            @PathVariable String modelCode,
            @PathVariable String recordPid) {
        return ApiResponse.success(commentService.listActivity(modelCode, recordPid));
    }
}

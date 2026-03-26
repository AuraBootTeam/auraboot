package com.auraboot.framework.bpm.controller;

import com.auraboot.framework.common.dto.ApiResponse;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * BPM Attachment Controller — stub for attachment management.
 * Returns empty collections until full attachment storage is implemented.
 */
@RestController
@RequestMapping("/api/bpm/attachments")
public class BpmAttachmentController {

    @GetMapping("/task/{taskId}")
    public ApiResponse<List<Map<String, Object>>> getTaskAttachments(@PathVariable String taskId) {
        return ApiResponse.success(Collections.emptyList());
    }

    @GetMapping("/process/{processInstanceId}")
    public ApiResponse<List<Map<String, Object>>> getProcessAttachments(@PathVariable String processInstanceId) {
        return ApiResponse.success(Collections.emptyList());
    }
}

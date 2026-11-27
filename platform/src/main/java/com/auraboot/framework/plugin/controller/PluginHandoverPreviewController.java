package com.auraboot.framework.plugin.controller;

import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.plugin.handover.HandoverPreviewService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.Operation;

/** Administrative preview only. There are deliberately no execute, exit, or reverse routes. */
@RestController
@RequestMapping("/api/plugins/handovers")
@RequirePermission("plugin.plugin.manage")
@RequiredArgsConstructor
public class PluginHandoverPreviewController {
    private final HandoverPreviewService previews;
    @Operation(operationId="listPluginHandoverPreviewContracts")
    @GetMapping("/contracts")
    public ApiResponse<List<HandoverPreviewService.ContractView>> contracts() { return ApiResponse.success(previews.catalog()); }
    @Operation(operationId="createPluginHandoverPreview")
    @PostMapping("/previews")
    public ApiResponse<HandoverPreviewService.Preview> preview(@Valid @RequestBody HandoverPreviewService.Request request) {
        return ApiResponse.success(previews.preview(request));
    }
    @Operation(operationId="findPluginHandoverPreviewByKey")
    @GetMapping("/previews/by-key")
    public ApiResponse<HandoverPreviewService.Preview> byKey(@RequestParam String idempotencyKey) { return ApiResponse.success(previews.getByKey(idempotencyKey)); }
    @Operation(operationId="findPluginHandoverPreview")
    @GetMapping("/previews/{batchPid}")
    public ApiResponse<HandoverPreviewService.Preview> get(@PathVariable String batchPid) { return ApiResponse.success(previews.get(batchPid)); }
}

package com.auraboot.framework.bpm.controller;

import com.auraboot.framework.bpm.entity.SlaConfigEntity;
import com.auraboot.framework.bpm.service.SlaConfigService;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.permission.constants.MetaPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/bpm/sla-configs")
@RequiredArgsConstructor
@Tag(name = "SLA Config", description = "SLA configuration management")
@RequirePermission(MetaPermission.BPM_SLA_MANAGE)
public class SlaConfigController {

    private final SlaConfigService slaConfigService;

    @GetMapping
    @Operation(summary = "List all SLA configs")
    public ApiResponse<List<SlaConfigEntity>> list() {
        return ApiResponse.success(slaConfigService.list());
    }

    @GetMapping("/{pid}")
    @Operation(summary = "Get SLA config by PID")
    public ApiResponse<SlaConfigEntity> getByPid(@PathVariable String pid) {
        SlaConfigEntity entity = slaConfigService.getByPid(pid);
        if (entity == null) {
            return ApiResponse.error("SLA config not found");
        }
        return ApiResponse.success(entity);
    }

    @GetMapping("/by-target")
    @Operation(summary = "Get SLA configs by target")
    public ApiResponse<List<SlaConfigEntity>> findByTarget(
            @RequestParam String targetType, @RequestParam String targetKey) {
        return ApiResponse.success(slaConfigService.findByTarget(targetType, targetKey));
    }

    @PostMapping
    @Operation(summary = "Create SLA config")
    public ApiResponse<SlaConfigEntity> create(@RequestBody SlaConfigService.CreateSlaConfigRequest request) {
        return ApiResponse.success(slaConfigService.create(request));
    }

    @PutMapping("/{pid}")
    @Operation(summary = "Update SLA config")
    public ApiResponse<SlaConfigEntity> update(@PathVariable String pid,
            @RequestBody SlaConfigService.UpdateSlaConfigRequest request) {
        return ApiResponse.success(slaConfigService.update(pid, request));
    }

    @DeleteMapping("/{pid}")
    @Operation(summary = "Delete SLA config")
    public ApiResponse<Void> delete(@PathVariable String pid) {
        slaConfigService.delete(pid);
        return ApiResponse.success();
    }
}

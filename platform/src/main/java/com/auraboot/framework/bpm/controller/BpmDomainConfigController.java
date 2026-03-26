package com.auraboot.framework.bpm.controller;

import com.auraboot.framework.bpm.entity.BpmDomainConfig;
import com.auraboot.framework.bpm.service.BpmDomainConfigService;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.exception.RootUnCheckedException;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.permission.constants.MetaPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static com.auraboot.framework.common.constant.ResponseCode.BadParam;

/**
 * REST API for managing BPM domain configurations.
 */
@Slf4j
@RestController
@RequestMapping("/api/bpm/domain-configs")
@RequiredArgsConstructor
@Tag(name = "BPM Domain Config", description = "Manage domain-specific task list configurations")
@RequirePermission(MetaPermission.BPM_CONFIG_MANAGE)
public class BpmDomainConfigController {

    private final BpmDomainConfigService domainConfigService;

    @GetMapping
    @Operation(summary = "List domain configs", description = "Get all domain configs for current tenant")
    public ApiResponse<List<BpmDomainConfig>> list() {
        List<BpmDomainConfig> configs = domainConfigService.list();
        return ApiResponse.success(configs);
    }

    @GetMapping("/{pid}")
    @Operation(summary = "Get domain config", description = "Get a domain config by PID")
    public ApiResponse<BpmDomainConfig> getByPid(
            @Parameter(description = "Domain config PID")
            @PathVariable String pid) {

        BpmDomainConfig config = domainConfigService.getByPid(pid);
        if (config == null) {
            throw new RootUnCheckedException(BadParam, "Domain config not found: " + pid);
        }
        return ApiResponse.success(config);
    }

    @GetMapping("/code/{domainCode}")
    @Operation(summary = "Get by domain code", description = "Get a domain config by domain code")
    public ApiResponse<BpmDomainConfig> getByDomainCode(
            @Parameter(description = "Domain code")
            @PathVariable String domainCode) {

        BpmDomainConfig config = domainConfigService.getByDomainCode(domainCode);
        if (config == null) {
            throw new RootUnCheckedException(BadParam, "Domain config not found for code: " + domainCode);
        }
        return ApiResponse.success(config);
    }

    @PostMapping
    @Operation(summary = "Create domain config", description = "Create a new domain config")
    public ApiResponse<BpmDomainConfig> create(
            @RequestBody BpmDomainConfigService.CreateRequest request) {

        BpmDomainConfig created = domainConfigService.create(request);
        return ApiResponse.success(created);
    }

    @PutMapping("/{pid}")
    @Operation(summary = "Update domain config", description = "Update an existing domain config")
    public ApiResponse<BpmDomainConfig> update(
            @Parameter(description = "Domain config PID")
            @PathVariable String pid,
            @RequestBody BpmDomainConfigService.UpdateRequest request) {

        BpmDomainConfig updated = domainConfigService.update(pid, request);
        return ApiResponse.success(updated);
    }

    @DeleteMapping("/{pid}")
    @Operation(summary = "Delete domain config", description = "Delete a domain config (soft delete)")
    public ApiResponse<Void> delete(
            @Parameter(description = "Domain config PID")
            @PathVariable String pid) {

        log.info("Deleting domain config: pid={}", pid);
        domainConfigService.delete(pid);
        return ApiResponse.success();
    }
}

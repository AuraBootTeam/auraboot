package com.auraboot.framework.plugin.controller;

import com.auraboot.framework.application.security.AdminRoleChecker;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.audit.entity.AdminEventLog;
import com.auraboot.framework.audit.service.AdminEventLogService;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.permission.annotation.RequirePlatformAdmin;
import com.auraboot.framework.permission.enums.RoleCodes;
import com.auraboot.framework.plugin.dto.imports.ImportExecuteResult;
import com.auraboot.framework.plugin.dto.imports.ImportRequest;
import com.auraboot.framework.plugin.service.PluginImportService;
import com.auraboot.framework.tenant.service.TenantService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;

/** Explicit, audited upgrades of deployment-declared plugins in existing tenants. */
@RestController
@RequirePlatformAdmin
@RequestMapping("/api/admin/infrastructure/tenant-plugins")
public class PlatformTenantPluginController {
    private final PluginImportService imports;
    private final TenantService tenants;
    private final AdminRoleChecker roles;
    private final AdminEventLogService audit;
    private final ObjectMapper mapper;
    private final Set<Path> allowedDirectories;

    public PlatformTenantPluginController(PluginImportService imports, TenantService tenants,
            AdminRoleChecker roles, AdminEventLogService audit, ObjectMapper mapper,
            @Value("${aura.tenant.product-plugins:}") String directories) {
        this.imports = imports;
        this.tenants = tenants;
        this.roles = roles;
        this.audit = audit;
        this.mapper = mapper;
        this.allowedDirectories = Arrays.stream(directories.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).map(Path::of)
                .map(Path::normalize).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public record UpgradeRequest(String tenantPid, String path, String releaseId, boolean dryRun) {}

    @PostMapping("/import")
    public ApiResponse<?> upgrade(@RequestBody UpgradeRequest request) {
        MetaContext previous = MetaContext.get();
        if (MetaContext.isImpersonating()
                || !"platform".equals(MetaContext.getCurrentExecutionScope())
                || !roles.hasRole(previous.getTenantId(), previous.getUserId(), RoleCodes.PLATFORM_ADMIN)) {
            throw new BusinessException(ResponseCode.FORBIDDEN, "Platform administrator session required");
        }
        if (request.tenantPid() == null || request.tenantPid().isBlank()
                || request.releaseId() == null || request.releaseId().isBlank()
                || request.releaseId().length() > 128 || request.path() == null) {
            throw new IllegalArgumentException("Explicit tenant, plugin path and release identity required");
        }
        Path directory = Path.of(request.path());
        if (!directory.isAbsolute() || !directory.equals(directory.normalize())
                || !allowedDirectories.contains(directory)) {
            throw new IllegalArgumentException("Plugin directory is not declared by this deployment");
        }
        var tenant = tenants.findByPid(request.tenantPid());
        if (tenant == null || Boolean.TRUE.equals(tenant.getDeletedFlag())
                || !"active".equalsIgnoreCase(tenant.getStatus())
                || tenant.getId().equals(previous.getTenantId())) {
            throw new IllegalArgumentException("Active business tenant required");
        }
        Long previousMember = MetaContext.getCurrentMemberId();
        Set<Long> previousRoles = MetaContext.getCurrentRoleIds();
        boolean success = false;
        MetaContext.setContext(tenant.getId(), previous.getUserId(), previous.getUserPid(), previous.getUsername());
        MetaContext.clearMemberId();
        try {
            var preview = imports.parseDirectory(directory.toString(), true);
            if (!preview.isValid()) {
                throw new IllegalArgumentException("Invalid deployment plugin: " + String.join(", ", preview.getErrors()));
            }
            if (request.dryRun()) {
                success = true;
                return ApiResponse.success(preview);
            }
            var importRequest = ImportRequest.builder().importId(preview.getImportId())
                    .conflictStrategy(ImportRequest.ConflictStrategy.OVERWRITE)
                    .autoPublishModels(true).autoPublishFields(true).autoPublishCommands(true)
                    .autoPublishPages(true).createResourcePermissions(true).build();
            ImportExecuteResult result = imports.execute(preview.getImportId(), importRequest);
            success = result.isSuccess();
            return ApiResponse.success(result);
        } finally {
            MetaContext.setContext(previous.getTenantId(), previous.getUserId(), previous.getUserPid(),
                    previous.getUsername(), previousRoles);
            MetaContext.setMemberId(previousMember);
            audit.record(AdminEventLog.builder().tenantId(previous.getTenantId())
                    .actorUserId(previous.getUserId()).actionType("tenant.plugin.upgrade")
                    .resourceType("tenant").resourcePid(tenant.getPid()).success(success)
                    .reason(request.releaseId()).payload(mapper.createObjectNode()
                            .put("pluginDirectory", directory.toString()).put("dryRun", request.dryRun())).build());
        }
    }
}

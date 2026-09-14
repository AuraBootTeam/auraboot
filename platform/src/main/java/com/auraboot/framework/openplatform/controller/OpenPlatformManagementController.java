package com.auraboot.framework.openplatform.controller;

import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.openplatform.dto.OpenPlatformDtos.ApplicationView;
import com.auraboot.framework.openplatform.dto.OpenPlatformDtos.CreateApplicationRequest;
import com.auraboot.framework.openplatform.dto.OpenPlatformDtos.CredentialSecret;
import com.auraboot.framework.openplatform.dto.OpenPlatformDtos.CredentialView;
import com.auraboot.framework.openplatform.dto.OpenPlatformDtos.InstallApplicationRequest;
import com.auraboot.framework.openplatform.dto.OpenPlatformDtos.InstallationView;
import com.auraboot.framework.openplatform.dto.OpenPlatformDtos.UpdateInstallationScopesRequest;
import com.auraboot.framework.openplatform.service.OpenPlatformManagementService;
import com.auraboot.framework.openplatform.service.OpenApiCapabilityRegistry;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.permission.constants.MetaPermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/open-platform")
@RequirePermission(MetaPermission.SYS_CONNECTOR_MANAGE)
@RequiredArgsConstructor
public class OpenPlatformManagementController {
    private final OpenPlatformManagementService managementService;
    private final OpenApiCapabilityRegistry capabilityRegistry;

    @GetMapping("/capabilities")
    public ApiResponse<List<OpenApiCapabilityRegistry.Capability>> listCapabilities() {
        return ApiResponse.success(capabilityRegistry.list());
    }

    @GetMapping("/applications")
    public ApiResponse<List<ApplicationView>> listApplications() {
        return ApiResponse.success(managementService.listApplications());
    }

    @PostMapping("/applications")
    public ApiResponse<ApplicationView> createApplication(@Valid @RequestBody CreateApplicationRequest request) {
        return ApiResponse.success(managementService.createApplication(request));
    }

    @PostMapping("/applications/{applicationPid}/installations")
    public ApiResponse<InstallationView> install(@PathVariable String applicationPid,
                                                 @Valid @RequestBody InstallApplicationRequest request) {
        return ApiResponse.success(managementService.install(applicationPid, request));
    }

    @PostMapping("/installations/{installationPid}/credentials")
    public ApiResponse<CredentialSecret> createCredential(@PathVariable String installationPid) {
        return ApiResponse.success(managementService.createCredential(installationPid));
    }

    @GetMapping("/installations/{installationPid}/credentials")
    public ApiResponse<List<CredentialView>> listCredentials(@PathVariable String installationPid) {
        return ApiResponse.success(managementService.listCredentials(installationPid));
    }

    @DeleteMapping("/installations/{installationPid}/credentials/{credentialPid}")
    public ApiResponse<Void> revokeCredential(@PathVariable String installationPid,
                                              @PathVariable String credentialPid) {
        managementService.revokeCredential(installationPid, credentialPid);
        return ApiResponse.success();
    }

    @DeleteMapping("/installations/{installationPid}")
    public ApiResponse<Void> disableInstallation(@PathVariable String installationPid) {
        managementService.disableInstallation(installationPid);
        return ApiResponse.success();
    }

    @PutMapping("/installations/{installationPid}/scopes")
    public ApiResponse<InstallationView> updateScopes(@PathVariable String installationPid,
                                                       @Valid @RequestBody UpdateInstallationScopesRequest request) {
        return ApiResponse.success(managementService.updateScopes(installationPid, request));
    }

    @DeleteMapping("/applications/{applicationPid}")
    public ApiResponse<Void> disableApplication(@PathVariable String applicationPid) {
        managementService.disableApplication(applicationPid);
        return ApiResponse.success();
    }
}

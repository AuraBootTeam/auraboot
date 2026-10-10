package com.auraboot.framework.openplatform.controller;

import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.openplatform.service.OpenApiInstallationPolicy;
import com.auraboot.framework.openplatform.service.OpenApiPublicationService;
import com.auraboot.framework.permission.annotation.AuthenticatedAccess;
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
@AuthenticatedAccess("Publication administration is checked by the service for the current tenant")
@RequiredArgsConstructor
public class OpenApiPublicationController {
    private final OpenApiPublicationService publications;

    @GetMapping("/publications")
    public ApiResponse<List<OpenApiPublicationService.PublicationView>> list() {
        return ApiResponse.success(publications.list());
    }

    @PutMapping("/publications/{code}/draft")
    public ApiResponse<OpenApiPublicationService.PublicationView> save(
            @PathVariable String code, @RequestBody OpenApiPublicationService.DraftRequest request) {
        return ApiResponse.success(publications.saveDraft(code, request));
    }

    @PostMapping("/publications/{code}/publish")
    public ApiResponse<OpenApiPublicationService.PublicationView> publish(
            @PathVariable String code, @RequestBody OpenApiPublicationService.RevisionRequest request) {
        return ApiResponse.success(publications.publish(code, request.expectedRevision()));
    }

    @PostMapping("/publications/{code}/withdraw")
    public ApiResponse<OpenApiPublicationService.PublicationView> withdraw(
            @PathVariable String code, @RequestBody OpenApiPublicationService.RevisionRequest request) {
        return ApiResponse.success(publications.withdraw(code, request.expectedRevision()));
    }

    @PutMapping("/installations/{installationPid}/resources/{code}")
    public ApiResponse<Void> grant(@PathVariable String installationPid, @PathVariable String code,
                                   @RequestBody OpenApiInstallationPolicy policy) {
        publications.grant(installationPid, code, policy);
        return ApiResponse.success();
    }

    @DeleteMapping("/installations/{installationPid}/resources/{code}")
    public ApiResponse<Void> revoke(@PathVariable String installationPid, @PathVariable String code) {
        publications.revoke(installationPid, code);
        return ApiResponse.success();
    }
}

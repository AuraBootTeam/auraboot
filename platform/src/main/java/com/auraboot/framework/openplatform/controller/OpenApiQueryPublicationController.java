package com.auraboot.framework.openplatform.controller;

import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.meta.service.NamedQueryService;
import com.auraboot.framework.openplatform.service.*;
import com.auraboot.framework.permission.annotation.AuthenticatedAccess;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/open-platform")
@AuthenticatedAccess("Query publication administration is checked for the current human tenant administrator")
@RequiredArgsConstructor
public class OpenApiQueryPublicationController {
    private final OpenApiQueryPublicationService publications;
    @GetMapping("/query-publications")
    public ApiResponse<List<OpenApiQueryPublicationService.View>> list() { return ApiResponse.success(publications.list()); }
    @GetMapping("/query-sources/{code}")
    public ApiResponse<NamedQueryService.ExecutionDefinition> source(@PathVariable String code) { return ApiResponse.success(publications.source(code)); }
    @PutMapping("/query-publications/{code}/draft")
    public ApiResponse<OpenApiQueryPublicationService.View> save(@PathVariable String code, @RequestBody OpenApiQueryPublicationService.DraftRequest request) {
        return ApiResponse.success(publications.saveDraft(code, request));
    }
    @PostMapping("/query-publications/{code}/publish")
    public ApiResponse<OpenApiQueryPublicationService.View> publish(@PathVariable String code, @RequestBody OpenApiPublicationService.RevisionRequest request) {
        return ApiResponse.success(publications.publish(code, request.expectedRevision()));
    }
    @PostMapping("/query-publications/{code}/withdraw")
    public ApiResponse<OpenApiQueryPublicationService.View> withdraw(@PathVariable String code, @RequestBody OpenApiPublicationService.RevisionRequest request) {
        return ApiResponse.success(publications.withdraw(code, request.expectedRevision()));
    }
    @PutMapping("/installations/{installationPid}/queries/{code}")
    public ApiResponse<Void> grant(@PathVariable String installationPid, @PathVariable String code, @RequestBody OpenApiQueryPolicy policy) {
        publications.grant(installationPid, code, policy);return ApiResponse.success();
    }
    @DeleteMapping("/installations/{installationPid}/queries/{code}")
    public ApiResponse<Void> revoke(@PathVariable String installationPid, @PathVariable String code) {
        publications.revoke(installationPid, code);return ApiResponse.success();
    }
}

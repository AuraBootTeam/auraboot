package com.auraboot.framework.openplatform.controller;

import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.openplatform.service.*;
import com.auraboot.framework.permission.annotation.AuthenticatedAccess;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/open-platform")
@AuthenticatedAccess("Human tenant publication-administrator permission is checked by the service")
@RequiredArgsConstructor
public class OpenApiActionPublicationController {
    private final OpenApiActionPublicationService publications;
    private final OpenApiPublicationService resources;
    @GetMapping("/action-publications") public ApiResponse<List<OpenApiActionPublicationService.View>> list() { return ApiResponse.success(publications.list()); }
    @GetMapping("/action-sources") public ApiResponse<List<OpenApiActionValidator.SourceView>> sources(@RequestParam String resourceCode) {
        return ApiResponse.success(publications.sources(resourceCode));
    }
    @PutMapping("/action-publications/{code}/draft") public ApiResponse<OpenApiActionPublicationService.View> save(@PathVariable String code,
            @RequestBody OpenApiActionPublicationService.DraftRequest request) { return ApiResponse.success(publications.saveDraft(code, request)); }
    @PostMapping("/action-publications/{code}/publish") public ApiResponse<OpenApiActionPublicationService.View> publish(@PathVariable String code,
            @RequestBody OpenApiPublicationService.RevisionRequest request) { return ApiResponse.success(publications.publish(code, request.expectedRevision())); }
    @PostMapping("/action-publications/{code}/withdraw") public ApiResponse<OpenApiActionPublicationService.View> withdraw(@PathVariable String code,
            @RequestBody OpenApiPublicationService.RevisionRequest request) { return ApiResponse.success(publications.withdraw(code, request.expectedRevision())); }
    @GetMapping("/installations/{installationPid}/actions") public ApiResponse<List<OpenApiActionPublicationService.GrantView>> grants(@PathVariable String installationPid) {
        return ApiResponse.success(publications.installationGrants(installationPid));
    }
    @PutMapping("/installations/{installationPid}/actions/{code}") public ApiResponse<Void> grant(@PathVariable String installationPid, @PathVariable String code,
            @RequestBody OpenApiActionPolicy policy) { publications.grant(installationPid, code, policy); return ApiResponse.success(); }
    @DeleteMapping("/installations/{installationPid}/actions/{code}") public ApiResponse<Void> revoke(@PathVariable String installationPid, @PathVariable String code) {
        publications.revoke(installationPid, code); return ApiResponse.success();
    }
    @GetMapping("/action-publications/{code}/openapi") public Map<String, Object> document(@PathVariable String code) {
        var contract = publications.publishedForAdministration(code);
        return OpenApiContractArtifacts.action(contract, resources.publishedForAdministration(contract.resourceCode()));
    }
    @GetMapping("/action-publications/{code}/types") public org.springframework.http.ResponseEntity<String> types(@PathVariable String code) {
        var contract = publications.publishedForAdministration(code);
        return org.springframework.http.ResponseEntity.ok().contentType(org.springframework.http.MediaType.parseMediaType("text/plain;charset=UTF-8"))
                .header("Content-Disposition", org.springframework.http.ContentDisposition.attachment().filename(contract.code() + ".d.ts", java.nio.charset.StandardCharsets.UTF_8).build().toString())
                .body(OpenApiContractArtifacts.actionTypes(contract, resources.publishedForAdministration(contract.resourceCode())));
    }
}

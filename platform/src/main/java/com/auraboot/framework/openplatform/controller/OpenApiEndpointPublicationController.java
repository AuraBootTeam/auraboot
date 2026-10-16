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
public class OpenApiEndpointPublicationController {
    private final OpenApiEndpointPublicationService publications;
    @GetMapping("/endpoint-publications") public ApiResponse<List<OpenApiEndpointPublicationService.View>> list() { return ApiResponse.success(publications.list()); }
    @GetMapping("/endpoint-candidates") public ApiResponse<List<OpenApiEndpointRegistry.Candidate>> candidates(@RequestParam(required = false) String search) {
        return ApiResponse.success(publications.candidates(search));
    }
    @GetMapping("/endpoint-source") public ApiResponse<OpenApiEndpointValidator.Description> source(@RequestParam String candidateCode, @RequestParam String bindingCode) {
        return ApiResponse.success(publications.source(candidateCode, bindingCode));
    }
    @PutMapping("/endpoint-publications/{code}/draft") public ApiResponse<OpenApiEndpointPublicationService.View> save(@PathVariable String code,
            @RequestBody OpenApiEndpointPublicationService.DraftRequest request) { return ApiResponse.success(publications.saveDraft(code, request)); }
    @PostMapping("/endpoint-publications/{code}/publish") public ApiResponse<OpenApiEndpointPublicationService.View> publish(@PathVariable String code,
            @RequestBody OpenApiPublicationService.RevisionRequest request) { return ApiResponse.success(publications.publish(code, request.expectedRevision())); }
    @PostMapping("/endpoint-publications/{code}/withdraw") public ApiResponse<OpenApiEndpointPublicationService.View> withdraw(@PathVariable String code,
            @RequestBody OpenApiPublicationService.RevisionRequest request) { return ApiResponse.success(publications.withdraw(code, request.expectedRevision())); }
    @GetMapping("/installations/{installationPid}/endpoints") public ApiResponse<List<OpenApiEndpointPublicationService.GrantView>> grants(@PathVariable String installationPid) {
        return ApiResponse.success(publications.installationGrants(installationPid));
    }
    @PutMapping("/installations/{installationPid}/endpoints/{code}") public ApiResponse<Void> grant(@PathVariable String installationPid, @PathVariable String code,
            @RequestBody OpenApiEndpointPolicy policy) { publications.grant(installationPid, code, policy); return ApiResponse.success(); }
    @DeleteMapping("/installations/{installationPid}/endpoints/{code}") public ApiResponse<Void> revoke(@PathVariable String installationPid, @PathVariable String code) {
        publications.revoke(installationPid, code); return ApiResponse.success();
    }
    @GetMapping("/endpoint-publications/{code}/openapi") public Map<String, Object> document(@PathVariable String code) {
        return publications.document(code);
    }
    @GetMapping("/endpoint-publications/{code}/types") public org.springframework.http.ResponseEntity<String> types(@PathVariable String code) {
        return org.springframework.http.ResponseEntity.ok().contentType(org.springframework.http.MediaType.parseMediaType("text/plain;charset=UTF-8"))
                .header("Content-Disposition", org.springframework.http.ContentDisposition.attachment().filename(code + ".d.ts", java.nio.charset.StandardCharsets.UTF_8).build().toString())
                .body(publications.types(code));
    }
}

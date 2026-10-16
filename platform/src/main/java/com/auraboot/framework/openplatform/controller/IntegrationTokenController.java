package com.auraboot.framework.openplatform.controller;

import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.openplatform.dto.IntegrationTokenDtos.*;
import com.auraboot.framework.openplatform.service.IntegrationTokenService;
import com.auraboot.framework.permission.annotation.AuthenticatedAccess;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/open-platform/installations/{installationPid}/integration-tokens")
@AuthenticatedAccess("Human application runtime-management authority is checked for this installation")
@RequiredArgsConstructor
public class IntegrationTokenController {
    private final IntegrationTokenService tokens;

    @GetMapping
    public ApiResponse<List<View>> list(@PathVariable String installationPid) {
        return ApiResponse.success(tokens.list(installationPid));
    }
    @PostMapping
    public ResponseEntity<ApiResponse<Secret>> create(@PathVariable String installationPid,
            @Valid @RequestBody Create request, @RequestHeader("Idempotency-Key") String key) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(tokens.create(installationPid, request, key)));
    }
    @PostMapping("/{tokenPid}/rotate")
    public ResponseEntity<ApiResponse<Rotation>> rotate(@PathVariable String installationPid, @PathVariable String tokenPid,
            @Valid @RequestBody Rotate request, @RequestHeader("Idempotency-Key") String key) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(tokens.rotate(installationPid, tokenPid, request, key)));
    }
    @DeleteMapping("/{tokenPid}")
    public ApiResponse<Void> revoke(@PathVariable String installationPid, @PathVariable String tokenPid) {
        tokens.revoke(installationPid, tokenPid);
        return ApiResponse.success();
    }
}

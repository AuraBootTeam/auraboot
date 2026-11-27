package com.auraboot.framework.openplatform.controller;

import com.auraboot.framework.auth.service.ApiRateLimiter;
import com.auraboot.framework.openplatform.service.SignedIntegrationCallbackService;
import com.auraboot.framework.permission.annotation.AuthenticatedAccess;
import com.auraboot.framework.plugin.extension.integration.SignedIntegrationCallbackExtension;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Anonymous transport only; installation, tenant secret and provider signature authenticate every delivery. */
@RestController
@RequestMapping("/api/public/integration-callbacks")
@AuthenticatedAccess("JWT-exempt provider callback; exact installation, current scope, tenant-owned secret and provider signature precede installed command execution")
@RequiredArgsConstructor
public class SignedIntegrationCallbackController {
    private final SignedIntegrationCallbackService callbacks;
    private final ApiRateLimiter limiter;

    @PostMapping(value = "/{tenant}/{installation}/{code}", produces = "application/json")
    public Map<String,Object> receive(@PathVariable Long tenant, @PathVariable String installation,
                                      @PathVariable String code, HttpServletRequest request) throws IOException {
        // Use the socket peer, not untrusted proxy headers. No provider query parameter can supply authority.
        if (!limiter.isAllowed("signed-callback:peer:" + request.getRemoteAddr(), 120))
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS);
        if (request.getQueryString() != null || request.getHeader("Content-Encoding") != null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        byte[] body = request.getInputStream().readNBytes(SignedIntegrationCallbackExtension.MAX_BODY_BYTES + 1);
        if (body.length == 0 || body.length > SignedIntegrationCallbackExtension.MAX_BODY_BYTES)
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE);
        return callbacks.receive(tenant, installation, code, request.getContentType(), body);
    }

    @ExceptionHandler({SecurityException.class, IllegalArgumentException.class})
    public ResponseEntity<Map<String,Object>> rejected() {
        return ResponseEntity.badRequest().body(Map.of("error", "CALLBACK_REJECTED"));
    }
}

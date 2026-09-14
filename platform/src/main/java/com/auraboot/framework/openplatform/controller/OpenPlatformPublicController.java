package com.auraboot.framework.openplatform.controller;

import com.auraboot.framework.application.security.ExternalApiKeyAuthenticator.ExternalApiKeyPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/open/v1")
public class OpenPlatformPublicController {
    @GetMapping("/whoami")
    public Map<String, Object> whoAmI(@AuthenticationPrincipal ExternalApiKeyPrincipal principal) {
        return Map.of(
                "applicationPid", principal.applicationPid(),
                "installationPid", principal.installationPid(),
                "environment", principal.environment(),
                "scopes", principal.scopes());
    }
}

package com.auraboot.framework.application.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Exposes the immutable application release identity supplied by the deployer. */
@RestController
@RequestMapping("/api/application")
public class ApplicationIdentityController {

    private final ApplicationIdentity identity;

    public ApplicationIdentityController(
            @Value("${aura.application.id:auraboot-core}") String applicationId,
            @Value("${aura.application.version:dev}") String applicationVersion,
            @Value("${aura.application.lock-identity:dev}") String lockIdentity,
            @Value("${aura.application.source-commit:dev}") String sourceCommit,
            @Value("${aura.application.image-digest:dev}") String imageDigest) {
        this.identity = new ApplicationIdentity(
                applicationId, applicationVersion, lockIdentity, sourceCommit, imageDigest);
    }

    @GetMapping("/identity")
    public ResponseEntity<ApplicationIdentity> identity() {
        return ResponseEntity.ok(identity);
    }

    public record ApplicationIdentity(
            String applicationId,
            String applicationVersion,
            String lockIdentity,
            String sourceCommit,
            String imageDigest) {
    }
}

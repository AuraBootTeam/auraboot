package com.auraboot.framework.application.controller;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApplicationIdentityControllerTest {

    @Test
    void returnsTheExactDeployerSuppliedReleaseIdentity() {
        var controller = new ApplicationIdentityController(
                "aura-bpm",
                "1.0.0",
                "sha256:lock",
                "0123456789012345678901234567890123456789",
                "sha256:image");

        var response = controller.identity();

        assertThat(response.getBody()).isEqualTo(new ApplicationIdentityController.ApplicationIdentity(
                "aura-bpm",
                "1.0.0",
                "sha256:lock",
                "0123456789012345678901234567890123456789",
                "sha256:image"));
    }
}

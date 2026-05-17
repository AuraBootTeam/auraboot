package com.auraboot.framework.saas.bootstrap.dto;

import lombok.Data;

@Data
public class BootstrapRequest {
    private String companyName;
    private String adminEmail;
    private String adminPassword;
    private String adminDisplayName;
    private String systemMode;       // single | multi | hybrid, default: single
    /** Deprecated compatibility field. /api/bootstrap/setup ignores demo seeding. */
    private Boolean seedDemoData;
    private String instanceUrl;      // e.g. "https://auraboot.example.com", default: "http://localhost:6443"
}

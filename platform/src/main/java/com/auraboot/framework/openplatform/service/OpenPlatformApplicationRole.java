package com.auraboot.framework.openplatform.service;

import java.util.Locale;

public enum OpenPlatformApplicationRole {
    OWNER(true, true, true, true),
    MAINTAINER(false, false, true, true),
    VIEWER(false, false, false, true);

    private final boolean manageMembers;
    private final boolean disableApplication;
    private final boolean manageRuntime;
    private final boolean readOperations;

    OpenPlatformApplicationRole(boolean manageMembers, boolean disableApplication,
                                boolean manageRuntime, boolean readOperations) {
        this.manageMembers = manageMembers;
        this.disableApplication = disableApplication;
        this.manageRuntime = manageRuntime;
        this.readOperations = readOperations;
    }

    public static OpenPlatformApplicationRole fromStorage(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Application role is required");
        }
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
    }

    public String storageValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    public boolean canManageMembers() {
        return manageMembers;
    }

    public boolean canDisableApplication() {
        return disableApplication;
    }

    public boolean canManageRuntime() {
        return manageRuntime;
    }

    public boolean canReadOperations() {
        return readOperations;
    }
}

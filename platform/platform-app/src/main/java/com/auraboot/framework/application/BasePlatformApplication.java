package com.auraboot.framework.application;

/** Base backend launcher; core-only retains its separate CRM/BPM product meaning. */
public final class BasePlatformApplication {
    private BasePlatformApplication() {}

    public static void main(String[] args) {
        PlatformApplication.launch(PlatformApplication.applicationSource(args), AgentAssembly.BASE, args);
    }
}

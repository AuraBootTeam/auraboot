package com.auraboot.framework.application;

import org.springframework.boot.SpringApplication;

/** Full application launcher; legacy scan configurations remain compatible until S2.2. */
public final class PlatformApplication {
    private PlatformApplication() {
    }

    public static void main(String[] args) {
        SpringApplication.run(applicationSource(args), args);
    }

    static Class<?> applicationSource(String... args) {
        return MetaApplication.applicationSource(args);
    }
}

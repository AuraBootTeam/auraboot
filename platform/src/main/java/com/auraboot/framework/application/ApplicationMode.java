package com.auraboot.framework.application;

/** Resolves the immutable application composition selected for this process. */
public final class ApplicationMode {

    public static final String PROPERTY = "aura.application.mode";
    public static final String CORE_ONLY = "core-only";

    private ApplicationMode() {
    }

    public static String resolve(String[] args) {
        String mode = System.getProperty(PROPERTY);
        if (mode == null || mode.isBlank()) {
            mode = System.getenv("AURA_APPLICATION_MODE");
        }
        if ((mode == null || mode.isBlank()) && args != null) {
            String prefix = "--" + PROPERTY + "=";
            for (String argument : args) {
                if (argument.startsWith(prefix)) {
                    mode = argument.substring(prefix.length());
                    break;
                }
            }
        }
        return mode == null ? "" : mode.trim();
    }

    public static boolean isCoreOnly() {
        return CORE_ONLY.equals(resolve(null));
    }
}

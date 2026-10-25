package com.auraboot.framework.application;

import org.springframework.boot.SpringApplication;

/** Default full application launcher, preserving the existing product-mode contract. */
public final class PlatformApplication {
    private PlatformApplication() {
    }

    public static void main(String[] args) {
        launch(applicationSource(args), AgentAssembly.WITH_AGENT, args);
    }

    static void launch(Class<?> source, AgentAssembly assembly, String[] args) {
        var application = new SpringApplication(source);
        application.addInitializers(context -> context.getBeanFactory()
                .registerSingleton("agentAssembly", assembly));
        application.run(args);
    }

    static Class<?> applicationSource(String... args) {
        return MetaApplication.applicationSource(args);
    }
}

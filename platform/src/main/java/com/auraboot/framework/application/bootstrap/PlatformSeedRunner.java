package com.auraboot.framework.application.bootstrap;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(1)
@RequiredArgsConstructor
@ConditionalOnExpression("'${aura.application.mode:}' != 'core-only'")
public class PlatformSeedRunner implements ApplicationRunner {
    private final PlatformSeedService platformSeedService;

    @Override
    public void run(ApplicationArguments args) {
        platformSeedService.seed();
    }
}

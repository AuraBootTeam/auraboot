package com.auraboot.framework.saas.bootstrap;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@Order(Integer.MAX_VALUE)
@Profile("!integration-test")
@RequiredArgsConstructor
public class BootstrapStartupLogger implements ApplicationRunner {

    private final BootstrapStatusEvaluator evaluator;

    @Override
    public void run(ApplicationArguments args) {
        BootstrapStatusEvaluator.Result result = evaluator.evaluate();
        if (result.missingParts().isEmpty()) {
            return;
        }
        log.warn("================================================");
        log.warn("  AuraBoot Bootstrap NOT INITIALIZED");
        log.warn("  Missing: {}", result.missingParts());
        log.warn("  Run: scripts/reset-and-init.sh");
        log.warn("  Or:  visit http://localhost:5173/setup");
        log.warn("================================================");
    }
}

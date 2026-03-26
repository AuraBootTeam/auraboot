package com.auraboot.framework.application.bootstrap;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import com.auraboot.framework.application.bootstrap.seeder.*;

@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
public class PlatformSeedRunner implements ApplicationRunner {
    private final SystemFieldSeeder systemFieldSeeder;
    private final QueryOperatorSeeder queryOperatorSeeder;
    private final I18nBaseSeeder i18nBaseSeeder;
    private final CloudConfigSeeder cloudConfigSeeder;
    private final MarketplaceCategorySeeder marketplaceCategorySeeder;
    private final AgentTemplateSeeder agentTemplateSeeder;
    private final SolutionSeeder solutionSeeder;

    @Override
    public void run(ApplicationArguments args) {
        log.info("PlatformSeedRunner: starting platform data initialization...");
        systemFieldSeeder.seed();
        queryOperatorSeeder.seed();
        i18nBaseSeeder.seed();
        cloudConfigSeeder.seed();
        marketplaceCategorySeeder.seed();
        agentTemplateSeeder.seed();
        solutionSeeder.seed();
        log.info("PlatformSeedRunner: platform data initialization complete.");
    }
}

package com.auraboot.framework.application.bootstrap;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import com.auraboot.framework.application.bootstrap.seeder.*;
import com.auraboot.framework.i18n.service.I18nOverrideAuditor;

import java.util.List;
import java.util.stream.Collectors;

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
    private final I18nOverrideAuditor i18nOverrideAuditor;

    /** Locales the platform seeds; audited for seed→DB drift after seeding. */
    private static final List<String> AUDITED_LOCALES = List.of("zh-CN", "en-US");

    @Override
    public void run(ApplicationArguments args) {
        log.info("PlatformSeedRunner: starting platform data initialization...");
        systemFieldSeeder.seed();
        queryOperatorSeeder.seed();
        i18nBaseSeeder.seed();
        auditI18nDrift();
        cloudConfigSeeder.seed();
        marketplaceCategorySeeder.seed();
        agentTemplateSeeder.seed();
        solutionSeeder.seed();
        log.info("PlatformSeedRunner: platform data initialization complete.");
    }

    /**
     * Warn (once per locale) when seed/i18n-base.json values have drifted from the
     * DB for platform-owned ({@code source='system'}) keys — i.e. the seed file was
     * updated but {@code I18nBaseSeeder}'s insert-ignore left the stale DB value.
     * Observability only; never blocks bootstrap.
     */
    private void auditI18nDrift() {
        for (String lang : AUDITED_LOCALES) {
            try {
                I18nOverrideAuditor.OverrideAuditReport report = i18nOverrideAuditor.audit(lang);
                if (report.driftCount() > 0) {
                    String sample = report.entries().stream()
                        .filter(e -> I18nOverrideAuditor.CLASS_SEED_DRIFT.equals(e.classification()))
                        .map(I18nOverrideAuditor.OverrideAuditEntry::key)
                        .limit(5)
                        .collect(Collectors.joining(", "));
                    log.warn("i18n[{}]: {} system keys drift from seed (DB is stale), e.g. {}; "
                            + "see GET /api/admin/i18n/override-audit?lang={}&onlyDrift=true",
                        lang, report.driftCount(), sample, lang);
                }
            } catch (Exception e) {
                log.warn("i18n override audit failed for lang={} (non-fatal)", lang, e);
            }
        }
    }
}

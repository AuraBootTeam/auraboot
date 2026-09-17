package com.auraboot.framework.application.bootstrap;

import com.auraboot.framework.application.bootstrap.seeder.AgentTemplateSeeder;
import com.auraboot.framework.application.bootstrap.seeder.CloudConfigSeeder;
import com.auraboot.framework.application.bootstrap.seeder.I18nBaseSeeder;
import com.auraboot.framework.application.bootstrap.seeder.MarketplaceCategorySeeder;
import com.auraboot.framework.application.bootstrap.seeder.QueryOperatorSeeder;
import com.auraboot.framework.application.bootstrap.seeder.SolutionSeeder;
import com.auraboot.framework.application.bootstrap.seeder.SystemFieldSeeder;
import com.auraboot.framework.i18n.service.I18nOverrideAuditor;
import java.util.List;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** Idempotent platform data initialization invoked by an explicit bootstrap flow. */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlatformSeedService {

    private static final List<String> AUDITED_LOCALES = List.of("zh-CN", "en-US");

    private final SystemFieldSeeder systemFieldSeeder;
    private final QueryOperatorSeeder queryOperatorSeeder;
    private final I18nBaseSeeder i18nBaseSeeder;
    private final CloudConfigSeeder cloudConfigSeeder;
    private final MarketplaceCategorySeeder marketplaceCategorySeeder;
    private final AgentTemplateSeeder agentTemplateSeeder;
    private final SolutionSeeder solutionSeeder;
    private final I18nOverrideAuditor i18nOverrideAuditor;

    public void seed() {
        log.info("PlatformSeedService: starting explicit platform data initialization...");
        systemFieldSeeder.seed();
        queryOperatorSeeder.seed();
        i18nBaseSeeder.seed();
        auditI18nDrift();
        cloudConfigSeeder.seed();
        marketplaceCategorySeeder.seed();
        agentTemplateSeeder.seed();
        solutionSeeder.seed();
        log.info("PlatformSeedService: explicit platform data initialization complete.");
    }

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

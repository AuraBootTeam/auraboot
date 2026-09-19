package com.auraboot.framework.application.bootstrap;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.when;

import com.auraboot.framework.application.bootstrap.seeder.AgentTemplateSeeder;
import com.auraboot.framework.application.bootstrap.seeder.CloudConfigSeeder;
import com.auraboot.framework.application.bootstrap.seeder.I18nBaseSeeder;
import com.auraboot.framework.application.bootstrap.seeder.MarketplaceCategorySeeder;
import com.auraboot.framework.application.bootstrap.seeder.QueryOperatorSeeder;
import com.auraboot.framework.application.bootstrap.seeder.SolutionSeeder;
import com.auraboot.framework.application.bootstrap.seeder.SystemFieldSeeder;
import com.auraboot.framework.i18n.service.I18nOverrideAuditor;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PlatformSeedServiceTest {

    @Mock private SystemFieldSeeder systemFieldSeeder;
    @Mock private QueryOperatorSeeder queryOperatorSeeder;
    @Mock private I18nBaseSeeder i18nBaseSeeder;
    @Mock private CloudConfigSeeder cloudConfigSeeder;
    @Mock private MarketplaceCategorySeeder marketplaceCategorySeeder;
    @Mock private AgentTemplateSeeder agentTemplateSeeder;
    @Mock private SolutionSeeder solutionSeeder;
    @Mock private I18nOverrideAuditor i18nOverrideAuditor;

    @InjectMocks private PlatformSeedService service;

    @BeforeEach
    void stubAudits() {
        for (String locale : List.of("zh-CN", "en-US")) {
            when(i18nOverrideAuditor.audit(locale))
                    .thenReturn(new I18nOverrideAuditor.OverrideAuditReport(locale, 0, 0, 0, List.of()));
        }
    }

    @Test
    void seedRunsTheExplicitPlatformInitializationSequence() {
        service.seed();

        InOrder order = inOrder(
                systemFieldSeeder,
                queryOperatorSeeder,
                i18nBaseSeeder,
                i18nOverrideAuditor,
                cloudConfigSeeder,
                marketplaceCategorySeeder,
                agentTemplateSeeder,
                solutionSeeder);
        order.verify(systemFieldSeeder).seed();
        order.verify(queryOperatorSeeder).seed();
        order.verify(i18nBaseSeeder).seed();
        order.verify(i18nOverrideAuditor).audit("zh-CN");
        order.verify(i18nOverrideAuditor).audit("en-US");
        order.verify(cloudConfigSeeder).seed();
        order.verify(marketplaceCategorySeeder).seed();
        order.verify(agentTemplateSeeder).seed();
        order.verify(solutionSeeder).seed();
    }
}

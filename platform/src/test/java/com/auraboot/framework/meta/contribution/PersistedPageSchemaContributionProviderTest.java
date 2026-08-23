package com.auraboot.framework.meta.contribution;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.environment.service.EnvironmentService;
import com.auraboot.framework.meta.contribution.mapper.PageSchemaContributionMapper;
import com.auraboot.framework.meta.dto.PageSchemaDTO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PersistedPageSchemaContributionProviderTest {

    @Mock private PageSchemaContributionMapper mapper;
    @Mock private EnvironmentService environmentService;

    @AfterEach
    void clearContext() {
        MetaContext.clear();
    }

    @Test
    void scopesLookupToCurrentTenantEnvironmentAndMapsStoredContract() {
        MetaContext.setCurrentTenantId(41L);
        MetaContext.setEnvironmentId(73L);
        PersistedPageSchemaContribution row = row();
        when(mapper.findActiveForPage(41L, 73L, "crm-opportunity-detail"))
                .thenReturn(List.of(row));
        PageSchemaDTO page = new PageSchemaDTO();
        page.setPageKey("crm-opportunity-detail");

        List<PageSchemaContribution> result = new PersistedPageSchemaContributionProvider(
                mapper, environmentService).findActiveContributions(page);

        assertThat(result).containsExactly(new PageSchemaContribution(
                "select-product", "com.auraboot.sales", "product-actions",
                PageSchemaContributionKind.ACTION, 100, Map.of("code", "select_product")));
        verify(mapper).findActiveForPage(41L, 73L, "crm-opportunity-detail");
        verifyNoInteractions(environmentService);
    }

    @Test
    void resolvesDefaultEnvironmentWhenContextHasNoEnvironment() {
        MetaContext.setCurrentTenantId(41L);
        when(environmentService.findOrCreateDefaultId(41L)).thenReturn(73L);
        when(mapper.findActiveForPage(41L, 73L, "crm-opportunity-detail")).thenReturn(List.of());
        PageSchemaDTO page = new PageSchemaDTO();
        page.setPageKey("crm-opportunity-detail");

        assertThat(new PersistedPageSchemaContributionProvider(mapper, environmentService)
                .findActiveContributions(page)).isEmpty();

        verify(environmentService).findOrCreateDefaultId(41L);
    }

    private PersistedPageSchemaContribution row() {
        PersistedPageSchemaContribution row = new PersistedPageSchemaContribution();
        row.setContributionId("select-product");
        row.setPluginPid("sales-plugin-pid");
        row.setContributorId("com.auraboot.sales");
        row.setSlotId("product-actions");
        row.setKind("action");
        row.setPriority(100);
        row.setPayload(Map.of("code", "select_product"));
        return row;
    }
}

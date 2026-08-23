package com.auraboot.framework.meta.contribution;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.environment.service.EnvironmentService;
import com.auraboot.framework.meta.contribution.mapper.PageSchemaContributionMapper;
import com.auraboot.framework.meta.dto.PageSchemaDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/** Runtime provider backed by plugin lifecycle-aware persistent rows. */
@Primary
@Component
@RequiredArgsConstructor
public class PersistedPageSchemaContributionProvider implements PageSchemaContributionProvider {

    private final PageSchemaContributionMapper mapper;
    private final EnvironmentService environmentService;

    @Override
    public List<PageSchemaContribution> findActiveContributions(PageSchemaDTO pageSchema) {
        Long tenantId = MetaContext.getCurrentTenantId();
        Long envId = MetaContext.getCurrentEnvironmentId();
        if (envId == null) {
            envId = environmentService.findOrCreateDefaultId(tenantId);
        }
        return mapper.findActiveForPage(tenantId, envId, pageSchema.getPageKey()).stream()
                .map(row -> new PageSchemaContribution(
                        row.getContributionId(), row.getContributorId(), row.getSlotId(),
                        PageSchemaContributionKind.valueOf(row.getKind().toUpperCase(Locale.ROOT)),
                        row.getPriority(), row.getPayload()))
                .toList();
    }
}

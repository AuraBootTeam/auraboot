package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.release.ApplicationRuntimeDefinitionCatalog;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.entity.NamedQuery;
import com.auraboot.framework.meta.entity.NamedQueryField;
import com.auraboot.framework.meta.mapper.NamedQueryFieldMapper;
import com.auraboot.framework.meta.mapper.NamedQueryMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Shared read access to named-query definitions — extracted from
 * NamedQueryServiceImpl (2026-11-14 split step 1) so the definition CRUD
 * facade, the execution engine and the export services all resolve
 * definitions through one store: runtime-release bound definitions when
 * enabled, the live table otherwise.
 */
@Component
public class NamedQueryDefinitionStore {

    private final ApplicationRuntimeDefinitionCatalog applicationRuntimeDefinitionCatalog;
    private final NamedQueryMapper namedQueryMapper;
    private final NamedQueryFieldMapper namedQueryFieldMapper;

    @Value("${aura.application.default-code:}")
    private String defaultApplicationCode;

    @Value("${aura.application.definition-read.runtime-primary-enabled:false}")
    private boolean applicationRuntimePrimaryEnabled;

    public NamedQueryDefinitionStore(ApplicationRuntimeDefinitionCatalog applicationRuntimeDefinitionCatalog,
                                     NamedQueryMapper namedQueryMapper,
                                     NamedQueryFieldMapper namedQueryFieldMapper) {
        this.applicationRuntimeDefinitionCatalog = applicationRuntimeDefinitionCatalog;
        this.namedQueryMapper = namedQueryMapper;
        this.namedQueryFieldMapper = namedQueryFieldMapper;
    }

    public boolean releaseReadsEnabled() {
        return applicationRuntimePrimaryEnabled && MetaContext.exists()
                && MetaContext.getCurrentTenantId() != null
                && org.springframework.util.StringUtils.hasText(defaultApplicationCode);
    }

    public ApplicationRuntimeDefinitionCatalog.BoundNamedQuery readDefinition(String code) {
        Long tenantId = MetaContext.getCurrentTenantId();
        if (releaseReadsEnabled()) {
            var bound = applicationRuntimeDefinitionCatalog.findNamedQuery(tenantId, defaultApplicationCode.trim(), code);
            if (bound.isPresent()) return bound.get();
        }
        NamedQuery query = namedQueryMapper.findByCode(code);
        return query == null ? null : new ApplicationRuntimeDefinitionCatalog.BoundNamedQuery(
                query, null);
    }

    public List<NamedQueryField> readFields(ApplicationRuntimeDefinitionCatalog.BoundNamedQuery definition) {
        return definition.fields() != null ? definition.fields()
                : namedQueryFieldMapper.findByQueryCode(definition.query().getTenantId(), definition.query().getCode());
    }
}

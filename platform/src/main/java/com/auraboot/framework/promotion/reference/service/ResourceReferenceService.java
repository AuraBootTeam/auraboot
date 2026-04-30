package com.auraboot.framework.promotion.reference.service;

import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.meta.entity.PageSchema;
import com.auraboot.framework.promotion.reference.dao.entity.ResourceReference;
import com.auraboot.framework.promotion.reference.dao.mapper.ResourceReferenceMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;
import java.util.Set;

/**
 * Read/write API for the reverse-reference index.
 *
 * <p>{@code refresh(page)} replaces the page's existing references with a freshly extracted set;
 * call after every PageSchema save. {@code findReferencingPages} reads the reverse index for
 * impact analysis ("delete field X breaks pages [A,B,C]").
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResourceReferenceService {

    private final ResourceReferenceMapper mapper;
    private final ResourceReferenceExtractor extractor;

    /**
     * Replace all existing references rooted at {@code (PAGE_SCHEMA, page.pid)} with the freshly
     * extracted set. Idempotent.
     */
    @Transactional
    public void refresh(PageSchema page) {
        if (page == null || page.getPid() == null) return;

        // Soft-delete prior refs for this page (env + tenant filtered by interceptors).
        UpdateWrapper<ResourceReference> uw = new UpdateWrapper<>();
        uw.eq("source_type", "PAGE_SCHEMA")
                .eq("source_id", page.getPid())
                .eq("deleted_flag", false)
                .set("deleted_flag", true);
        mapper.update(null, uw);

        Set<ResourceReference> fresh = extractor.extract(page);
        Date now = new Date();
        for (ResourceReference ref : fresh) {
            ref.setPid(UniqueIdGenerator.generate());
            ref.setCreatedAt(now);
            mapper.insert(ref);
        }
        log.debug("Refreshed {} references for page {}", fresh.size(), page.getPid());
    }

    /**
     * Reverse-index lookup. Honors current MetaContext env via standard tenant interceptor; if
     * cross-env results are needed, caller wraps in {@link MetaContext#runWithoutEnvFilter}.
     *
     * @return list of references whose target matches the (type, code) pair.
     */
    public List<ResourceReference> findReferencingPages(String targetType, String targetCode) {
        // env_id filter is auto-injected by the @EnvScoped interceptor since ab_resource_reference
        // is in ENV_SCOPED_TABLES whitelist. To go cross-env, wrap in MetaContext.runWithoutEnvFilter.
        QueryWrapper<ResourceReference> qw = new QueryWrapper<>();
        qw.eq("target_type", targetType)
                .eq("target_code", targetCode)
                .eq("deleted_flag", false);
        return mapper.selectList(qw);
    }
}

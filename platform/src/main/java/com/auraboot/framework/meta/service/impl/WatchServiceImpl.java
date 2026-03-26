package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.entity.AbWatch;
import com.auraboot.framework.meta.mapper.AbWatchMapper;
import com.auraboot.framework.meta.service.WatchService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Implementation of {@link WatchService}.
 * Uses MyBatis Plus for CRUD and raw SQL queries for bulk lookups.
 *
 * @since 6.1.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WatchServiceImpl implements WatchService {

    private final AbWatchMapper watchMapper;

    @Override
    @Transactional
    public boolean toggleWatch(String modelCode, Long recordId) {
        Long tenantId = MetaContext.getCurrentTenantId();
        Long userId = MetaContext.getCurrentUserId();

        AbWatch existing = findExisting(tenantId, userId, modelCode, recordId);
        if (existing != null) {
            watchMapper.deleteById(existing.getId());
            log.debug("User {} unwatched {}/{}", userId, modelCode, recordId);
            return false;
        }

        AbWatch watch = new AbWatch();
        watch.setTenantId(tenantId);
        watch.setUserId(userId);
        watch.setModelCode(modelCode);
        watch.setRecordId(recordId);
        watch.setCreatedAt(Instant.now());
        watchMapper.insert(watch);
        log.debug("User {} now watching {}/{}", userId, modelCode, recordId);
        return true;
    }

    @Override
    public boolean isWatching(String modelCode, Long recordId) {
        Long tenantId = MetaContext.getCurrentTenantId();
        Long userId = MetaContext.getCurrentUserId();
        return findExisting(tenantId, userId, modelCode, recordId) != null;
    }

    @Override
    public List<Long> getWatchers(String modelCode, Long recordId) {
        Long tenantId = MetaContext.getCurrentTenantId();
        return watchMapper.findWatcherUserIds(tenantId, modelCode, recordId);
    }

    @Override
    public List<Long> getWatchedRecordIds(String modelCode, Long userId) {
        Long tenantId = MetaContext.getCurrentTenantId();
        return watchMapper.findWatchedRecordIds(tenantId, userId, modelCode);
    }

    private AbWatch findExisting(Long tenantId, Long userId, String modelCode, Long recordId) {
        return watchMapper.selectOne(new LambdaQueryWrapper<AbWatch>()
                .eq(AbWatch::getTenantId, tenantId)
                .eq(AbWatch::getUserId, userId)
                .eq(AbWatch::getModelCode, modelCode)
                .eq(AbWatch::getRecordId, recordId));
    }
}

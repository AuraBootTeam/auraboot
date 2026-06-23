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
import org.springframework.util.StringUtils;

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
        watch.setRecordPid(recordId != null ? String.valueOf(recordId) : null);
        watch.setCreatedAt(Instant.now());
        watchMapper.insert(watch);
        log.debug("User {} now watching {}/{}", userId, modelCode, recordId);
        return true;
    }

    @Override
    @Transactional
    public boolean toggleWatchByRecordPid(String modelCode, String recordPid) {
        if (!StringUtils.hasText(recordPid)) {
            return false;
        }

        Long tenantId = MetaContext.getCurrentTenantId();
        Long userId = MetaContext.getCurrentUserId();

        AbWatch existing = findExistingByRecordPid(tenantId, userId, modelCode, recordPid);
        if (existing != null) {
            watchMapper.deleteById(existing.getId());
            log.debug("User {} unwatched {}/{}", userId, modelCode, recordPid);
            return false;
        }

        AbWatch watch = new AbWatch();
        watch.setTenantId(tenantId);
        watch.setUserId(userId);
        watch.setModelCode(modelCode);
        watch.setRecordPid(recordPid);
        watch.setRecordId(parseLongOrNull(recordPid));
        watch.setCreatedAt(Instant.now());
        watchMapper.insert(watch);
        log.debug("User {} now watching {}/{}", userId, modelCode, recordPid);
        return true;
    }

    @Override
    public boolean isWatching(String modelCode, Long recordId) {
        Long tenantId = MetaContext.getCurrentTenantId();
        Long userId = MetaContext.getCurrentUserId();
        return findExisting(tenantId, userId, modelCode, recordId) != null;
    }

    @Override
    public boolean isWatchingByRecordPid(String modelCode, String recordPid) {
        if (!StringUtils.hasText(recordPid)) {
            return false;
        }
        Long tenantId = MetaContext.getCurrentTenantId();
        Long userId = MetaContext.getCurrentUserId();
        return findExistingByRecordPid(tenantId, userId, modelCode, recordPid) != null;
    }

    @Override
    public List<Long> getWatchers(String modelCode, Long recordId) {
        Long tenantId = MetaContext.getCurrentTenantId();
        return watchMapper.findWatcherUserIds(tenantId, modelCode, recordId);
    }

    @Override
    public List<Long> getWatchersByRecordPid(String modelCode, String recordPid) {
        if (!StringUtils.hasText(recordPid)) {
            return List.of();
        }
        Long tenantId = MetaContext.getCurrentTenantId();
        return watchMapper.findWatcherUserIdsByRecordPid(tenantId, modelCode, recordPid);
    }

    @Override
    public List<Long> getWatchedRecordIds(String modelCode, Long userId) {
        Long tenantId = MetaContext.getCurrentTenantId();
        return watchMapper.findWatchedRecordIds(tenantId, userId, modelCode);
    }

    @Override
    public List<String> getWatchedRecordPids(String modelCode, Long userId) {
        Long tenantId = MetaContext.getCurrentTenantId();
        return watchMapper.findWatchedRecordPids(tenantId, userId, modelCode);
    }

    private AbWatch findExisting(Long tenantId, Long userId, String modelCode, Long recordId) {
        return watchMapper.selectOne(new LambdaQueryWrapper<AbWatch>()
                .eq(AbWatch::getTenantId, tenantId)
                .eq(AbWatch::getUserId, userId)
                .eq(AbWatch::getModelCode, modelCode)
                .eq(AbWatch::getRecordId, recordId));
    }

    private AbWatch findExistingByRecordPid(Long tenantId, Long userId, String modelCode, String recordPid) {
        return watchMapper.selectOne(new LambdaQueryWrapper<AbWatch>()
                .eq(AbWatch::getTenantId, tenantId)
                .eq(AbWatch::getUserId, userId)
                .eq(AbWatch::getModelCode, modelCode)
                .eq(AbWatch::getRecordPid, recordPid));
    }

    private Long parseLongOrNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.ChangeLogQueryRequest;
import com.auraboot.framework.meta.dto.PaginationResult;
import com.auraboot.framework.meta.entity.DataChangeLog;
import com.auraboot.framework.meta.mapper.DataChangeLogMapper;
import com.auraboot.framework.meta.service.ChangeLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Implementation of ChangeLogService.
 *
 * @since 5.1.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChangeLogServiceImpl implements ChangeLogService {

    private final DataChangeLogMapper changeLogMapper;

    @Override
    public List<DataChangeLog> getHistory(String modelCode, String recordId) {
        Long tenantId = MetaContext.getCurrentTenantId();
        return changeLogMapper.findByRecord(tenantId, modelCode, recordId);
    }

    @Override
    public PaginationResult<DataChangeLog> getByUser(Long userId, ChangeLogQueryRequest request) {
        Long tenantId = MetaContext.getCurrentTenantId();

        int pageNum = Math.max(1, request.getPageNum());
        int pageSize = Math.min(100, Math.max(1, request.getPageSize()));
        int offset = (pageNum - 1) * pageSize;

        List<DataChangeLog> records = changeLogMapper.findByUser(tenantId, userId, pageSize, offset);
        long total = changeLogMapper.countByUser(tenantId, userId);

        return PaginationResult.of(records, total, pageNum, pageSize);
    }

    @Override
    public DataChangeLog getById(Long id) {
        Long tenantId = MetaContext.getCurrentTenantId();
        return changeLogMapper.findById(tenantId, id);
    }
}

package com.auraboot.framework.meta.service;

import com.auraboot.framework.meta.dto.ChangeLogQueryRequest;
import com.auraboot.framework.meta.dto.PaginationResult;
import com.auraboot.framework.meta.entity.DataChangeLog;

import java.util.List;

/**
 * Service for querying data change history.
 *
 * @since 5.1.0
 */
public interface ChangeLogService {

    /**
     * Get change history for a specific record.
     */
    List<DataChangeLog> getHistory(String modelCode, String recordId);

    /**
     * Get change logs by user with pagination.
     */
    PaginationResult<DataChangeLog> getByUser(Long userId, ChangeLogQueryRequest request);

    /**
     * Get a single change log entry by ID.
     */
    DataChangeLog getById(Long id);
}

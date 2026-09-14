package com.auraboot.framework.dashboard.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.constant.StatusConstants;
import com.auraboot.framework.dashboard.dto.WorkbenchStatsDTO;
import com.auraboot.framework.dashboard.dto.WorkbenchStatsDTO.StatItem;
import com.auraboot.framework.dashboard.service.WorkbenchStatsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Core-only workbench statistics. Product statistics are contributed by product packages. */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkbenchStatsServiceImpl implements WorkbenchStatsService {
    private static final String KEY_INBOX_PENDING = "inbox_pending";
    private static final String KEY_INBOX_URGENT = "inbox_urgent";
    private static final List<String> DEFAULT_KEYS = List.of(KEY_INBOX_PENDING, KEY_INBOX_URGENT);
    private static final String FORMAT_NUMBER = "number";
    private final JdbcTemplate jdbcTemplate;

    @Override
    public WorkbenchStatsDTO getStats(List<String> keys) {
        List<String> requested = keys == null || keys.isEmpty() ? DEFAULT_KEYS : keys;
        Long tenantId = MetaContext.getCurrentTenantId();
        Long userId = MetaContext.getCurrentUserId();
        Map<String, StatItem> stats = new LinkedHashMap<>();
        for (String key : requested) {
            StatItem item = switch (key) {
                case KEY_INBOX_PENDING -> pending(tenantId, userId);
                case KEY_INBOX_URGENT -> urgent(tenantId, userId);
                default -> null;
            };
            if (item != null) stats.put(key, item);
        }
        return WorkbenchStatsDTO.builder().stats(stats).build();
    }

    private StatItem pending(Long tenantId, Long userId) {
        Long value = count("SELECT COUNT(*) FROM ab_inbox_item WHERE status = ? AND user_id = ? AND tenant_id = ?",
                StatusConstants.PENDING, userId, tenantId);
        return StatItem.builder().value(value).label("workbench.stats.inbox_pending")
                .format(FORMAT_NUMBER).series(pendingSeries(tenantId, userId)).build();
    }

    private StatItem urgent(Long tenantId, Long userId) {
        Long value = count("SELECT COUNT(*) FROM ab_inbox_item WHERE status = ? AND priority IN ('urgent', 'high') AND user_id = ? AND tenant_id = ?",
                StatusConstants.PENDING, userId, tenantId);
        return StatItem.builder().value(value).label("workbench.stats.inbox_urgent")
                .format(FORMAT_NUMBER).build();
    }

    private WorkbenchStatsDTO.Series pendingSeries(Long tenantId, Long userId) {
        try {
            List<Long> points = jdbcTemplate.queryForList("""
                    SELECT COALESCE(c.cnt, 0) AS cnt
                    FROM generate_series(current_date - interval '6 day', current_date, interval '1 day') AS d
                    LEFT JOIN (
                      SELECT DATE(created_at) AS day, COUNT(*) AS cnt FROM ab_inbox_item
                      WHERE status = ? AND user_id = ? AND tenant_id = ?
                        AND created_at >= current_date - interval '6 day'
                      GROUP BY DATE(created_at)
                    ) c ON c.day = d::date ORDER BY d ASC
                    """, Long.class, StatusConstants.PENDING, userId, tenantId);
            if (points == null || points.size() != 7) return null;
            return WorkbenchStatsDTO.Series.builder().period("day").points(new ArrayList<>(points)).build();
        } catch (DataAccessException error) {
            log.debug("Failed to compute inbox pending series: {}", error.getMessage());
            return null;
        }
    }

    private Long count(String sql, Object... arguments) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, arguments);
        return value == null ? 0L : value;
    }
}

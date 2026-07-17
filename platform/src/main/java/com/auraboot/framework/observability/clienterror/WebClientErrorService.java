package com.auraboot.framework.observability.clienterror;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.PaginationSafetyUtils;
import com.auraboot.framework.meta.dto.PaginationResult;
import com.auraboot.framework.observability.clienterror.mapper.WebClientErrorMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * Persists and reads {@link WebClientError} rows. Tenant-scoped via {@link MetaContext}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebClientErrorService {

    private static final int MAX_MESSAGE = 2000;
    private static final int MAX_STACK = 8000;
    private static final int MAX_URL = 2000;
    private static final int MAX_UA = 512;

    private final WebClientErrorMapper webClientErrorMapper;

    /** Record one browser-reported error, stamped with the current tenant/user. */
    public void record(WebClientErrorRequest req) {
        WebClientError row = new WebClientError();
        row.setTenantId(MetaContext.getCurrentTenantId());
        row.setUserId(MetaContext.getCurrentUserId());
        row.setSessionId(req.getSessionId());
        row.setTraceId(req.getTraceId());
        row.setErrorType("unhandledrejection".equals(req.getErrorType()) ? "unhandledrejection" : "error");
        row.setMessage(clip(req.getMessage(), MAX_MESSAGE));
        row.setStack(clip(req.getStack(), MAX_STACK));
        row.setPageUrl(clip(req.getPageUrl(), MAX_URL));
        row.setUserAgent(clip(req.getUserAgent(), MAX_UA));
        row.setAppVersion(clip(req.getAppVersion(), 50));
        row.setClientTimestamp(req.getClientTimestamp());
        row.setCreatedAt(Instant.now());
        webClientErrorMapper.insert(row);
    }

    public PaginationResult<WebClientError> page(int pageNum, int pageSize) {
        Long tenantId = MetaContext.getCurrentTenantId();
        int safePageNum = PaginationSafetyUtils.pageNumber(pageNum);
        int clampedPageSize = PaginationSafetyUtils.pageSize(pageSize, 200);
        int offset = PaginationSafetyUtils.offset(safePageNum, clampedPageSize, 200);

        List<WebClientError> rows = webClientErrorMapper.pageByTenant(tenantId, clampedPageSize, offset);
        long total = webClientErrorMapper.countByTenant(tenantId);
        return PaginationResult.of(rows, total, safePageNum, clampedPageSize);
    }

    private static String clip(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}

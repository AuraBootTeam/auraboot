package com.auraboot.framework.behavior.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.behavior.mapper.BehaviorEventMapper;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.UUID;

/** Validates ownership of client-observed presentation; this is not a business outcome. */
@Service
@RequiredArgsConstructor
public class AnalyticsResultViewService {
    private final BehaviorEventMapper events;
    private final AnalyticsJourneyService journey;

    public void record(UUID analysisId) {
        String hash = events.findSuccessfulQueryHash(MetaContext.getCurrentTenantId(),
                MetaContext.getCurrentUserId(), analysisId.toString());
        if (hash == null) {
            throw new BusinessException(ResponseCode.BadParam, "Analysis result is unavailable");
        }
        journey.resultViewed(analysisId.toString(), hash);
    }
}

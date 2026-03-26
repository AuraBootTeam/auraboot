package com.auraboot.framework.base.service;

import com.auraboot.framework.base.service.request.BaseRequest;
import com.auraboot.framework.base.service.response.BaseResponse;

public interface BaseServiceFacade {
    /**
     * @deprecated Unused — orchestration result was discarded (returned null). Scheduled for removal.
     */
    @Deprecated(forRemoval = true)
    BaseResponse create(BaseRequest request);
}

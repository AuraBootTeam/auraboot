package com.auraboot.framework.base.service.impl;

import com.auraboot.framework.base.service.BaseServiceFacade;
import com.auraboot.framework.base.service.ServiceOrchestration;
import com.auraboot.framework.base.service.request.BaseRequest;
import com.auraboot.framework.base.service.response.BaseResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class DefaultBaseServiceFacade implements BaseServiceFacade {

    @Autowired
    private ServiceOrchestration serviceOrchestration;

    /**
     * @deprecated Unused — orchestration result was discarded (returned null). Scheduled for removal.
     */
    @Deprecated(forRemoval = true)
    @Override
    public BaseResponse create(BaseRequest request) {
        throw new UnsupportedOperationException("Deprecated: use Command engine instead");
    }
}

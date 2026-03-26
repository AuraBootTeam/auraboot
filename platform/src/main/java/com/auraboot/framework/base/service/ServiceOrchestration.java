package com.auraboot.framework.base.service;


import com.auraboot.framework.base.service.request.BaseRequest;
import com.auraboot.framework.base.service.response.BaseResponse;

public interface ServiceOrchestration {


    BaseResponse orchestrate(BaseRequest request);
}

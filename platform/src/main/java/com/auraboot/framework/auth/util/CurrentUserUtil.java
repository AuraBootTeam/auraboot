package com.auraboot.framework.auth.util;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.exception.RootUnCheckedException;
import org.springframework.stereotype.Component;

@Component
public class CurrentUserUtil {
    
    /**
     * 获取当前用户ID - 直接从TenantContext获取
     */
    public static Long getCurrentUserId() {
        Long userId = MetaContext.getCurrentUserId();
        if (userId == null) {
            throw new RootUnCheckedException(ResponseCode.SystemError, "User not authenticated");
        }
        return userId;
    }

}
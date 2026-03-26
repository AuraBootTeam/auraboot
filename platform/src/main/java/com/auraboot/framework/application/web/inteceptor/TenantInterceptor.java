package com.auraboot.framework.application.web.inteceptor;

import com.auraboot.framework.application.tenant.MetaContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 租户上下文拦截器
 * 在每个请求开始时设置租户上下文，请求结束时清理
 */
@Slf4j
@Component
public class TenantInterceptor implements HandlerInterceptor {

    // @Autowired
    // private TenantMemberService tenantMemberService;

    // @Autowired
    // private UserService userService;

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        // 确保清理租户上下文，避免内存泄漏
        MetaContext.clear();
        log.debug("Cleared tenant context");
    }
}
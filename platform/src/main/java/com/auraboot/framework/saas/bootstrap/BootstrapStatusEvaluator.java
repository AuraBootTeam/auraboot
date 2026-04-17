package com.auraboot.framework.saas.bootstrap;

import com.auraboot.framework.rbac.constant.RoleConstants;
import com.auraboot.framework.saas.bootstrap.constant.BootstrapMissingPart;
import com.auraboot.framework.saas.bootstrap.mapper.BootstrapStatusMapper;
import com.auraboot.framework.saas.config.service.SystemConfigService;
import com.auraboot.framework.saas.executor.SystemTenantContextExecutor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
public class BootstrapStatusEvaluator {

    private final BootstrapStatusMapper bootstrapStatusMapper;
    private final SystemConfigService systemConfigService;

    public Result evaluate() {
        List<String> missing = new ArrayList<>();
        if (bootstrapStatusMapper.countPlatformAdminAssignments(RoleConstants.PLATFORM_ADMIN) == 0L) {
            missing.add(BootstrapMissingPart.ADMIN_USER);
        }
        if (bootstrapStatusMapper.countTenantById(SystemTenantContextExecutor.SYSTEM_TENANT_ID) == 0L) {
            missing.add(BootstrapMissingPart.DEFAULT_TENANT);
        }
        if (!systemConfigService.isInitialized()) {
            missing.add(BootstrapMissingPart.SYSTEM_CONFIG);
        }
        String reason = missing.isEmpty() ? null
                : "Missing bootstrap data: " + String.join(", ", missing);
        return new Result(List.copyOf(missing), reason);
    }

    public record Result(List<String> missingParts, String reason) {}
}

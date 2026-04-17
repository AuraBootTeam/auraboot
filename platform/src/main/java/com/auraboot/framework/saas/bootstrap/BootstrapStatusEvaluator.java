package com.auraboot.framework.saas.bootstrap;

import com.auraboot.framework.saas.bootstrap.constant.BootstrapMissingPart;
import com.auraboot.framework.saas.bootstrap.mapper.BootstrapStatusMapper;
import com.auraboot.framework.saas.config.service.SystemConfigService;
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
        if (bootstrapStatusMapper.countPlatformAdminAssignments() == 0L) {
            missing.add(BootstrapMissingPart.ADMIN_USER);
        }
        if (bootstrapStatusMapper.countSystemTenant() == 0L) {
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

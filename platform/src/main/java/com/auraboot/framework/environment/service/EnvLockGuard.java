package com.auraboot.framework.environment.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.environment.dao.entity.Environment;
import com.auraboot.framework.environment.dao.mapper.EnvironmentMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Write-side enforcement for {@link Environment#getIsLocked() lock state} (env-layering #17).
 *
 * <p>Locked environments reject direct writes — the only legitimate path is a promotion that
 * passes the four-eyes check at {@code PromotionService.apply}. Promotion / plugin-import /
 * system migrations may bypass via {@link MetaContext#runWithoutLockGuard}.
 *
 * <p>PoC scope: INSERT only. Hooked from {@code AuraBootObjectHandler.insertFill} so every
 * INSERT of an {@code @EnvScoped} entity is checked. UPDATE/DELETE coverage is deferred to
 * a follow-up that adds a MyBatis-Plus inner interceptor.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EnvLockGuard {

    private final EnvironmentMapper environmentMapper;

    /**
     * @throws IllegalStateException if the target environment is locked and no bypass is active.
     */
    public void assertWritable(Long envId) {
        if (envId == null) return;
        if (MetaContext.isLockGuardBypassed()) return;
        Environment env = environmentMapper.selectById(envId);
        if (env != null && Boolean.TRUE.equals(env.getIsLocked())) {
            throw new IllegalStateException(
                    "Environment " + env.getCode() + " is locked; promote via /api/promotions "
                            + "(four-eyes apply) instead of direct write");
        }
    }
}

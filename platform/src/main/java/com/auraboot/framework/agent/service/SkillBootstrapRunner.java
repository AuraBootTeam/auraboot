package com.auraboot.framework.agent.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Ensures the 2 built-in skills (dsl.command, dsl.query) exist for all tenants on startup.
 * Runs after all beans are initialized (ApplicationRunner, not @PostConstruct).
 */
@Slf4j
@Component
@Order(100)
@RequiredArgsConstructor
public class SkillBootstrapRunner implements ApplicationRunner {

    private final SkillAutoGenerator skillAutoGenerator;
    private final DynamicDataMapper dynamicDataMapper;

    @Override
    public void run(ApplicationArguments args) {
        try {
            String sql = "SELECT DISTINCT id FROM ab_tenant " +
                    "WHERE status = 'active' AND (deleted_flag = FALSE OR deleted_flag IS NULL)";
            List<Map<String, Object>> tenants = dynamicDataMapper.selectByQueryWithoutTenant(sql, Map.of());

            int synced = 0;
            for (Map<String, Object> tenant : tenants) {
                Long tenantId = ((Number) tenant.get("id")).longValue();
                try {
                    // Set MetaContext for tenant-scoped queries (MyBatis TenantLineInterceptor requires it)
                    MetaContext.setCurrentTenantId(tenantId);
                    var result = skillAutoGenerator.syncSkills(tenantId);
                    synced++;
                    if (result.created() > 0) {
                        log.info("Skill bootstrap for tenant {}: created={}, updated={}",
                            tenantId, result.created(), result.updated());
                    }
                } catch (Exception e) {
                    log.warn("Skill bootstrap failed for tenant {}: {}", tenantId, e.getMessage());
                } finally {
                    MetaContext.clear();
                }
            }
            log.info("Skill bootstrap complete: {} tenants synced", synced);
        } catch (Exception e) {
            log.error("Skill bootstrap runner failed: {}", e.getMessage());
            // Don't throw — app should still start even if skill bootstrap fails
        }
    }
}

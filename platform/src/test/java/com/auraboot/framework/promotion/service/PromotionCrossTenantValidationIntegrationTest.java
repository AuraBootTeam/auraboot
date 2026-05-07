package com.auraboot.framework.promotion.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.environment.dao.entity.Environment;
import com.auraboot.framework.environment.dao.mapper.EnvironmentMapper;
import com.auraboot.framework.environment.dto.EnvironmentRequest;
import com.auraboot.framework.environment.dto.EnvironmentResponse;
import com.auraboot.framework.environment.service.EnvironmentService;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.promotion.dto.PromotionRequest;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for cross-tenant validation in PromotionService.create().
 *
 * <p>Slice 1 P1-1 review finding: PromotionServiceImpl.create() previously trusted
 * sourceEnvId / targetEnvId straight from the request without verifying they belong
 * to the caller's tenant. A tenant_admin in tenant A could craft a Promotion row
 * naming tenant B's env IDs. These tests assert the explicit tenant-membership
 * check now rejects such requests up front.
 */
class PromotionCrossTenantValidationIntegrationTest extends BaseIntegrationTest {

    private static final String OTHER_TENANT_PREFIX = "promo_xt_other_";

    @Autowired
    private PromotionService promotionService;

    @Autowired
    private EnvironmentService environmentService;

    @Autowired
    private EnvironmentMapper environmentMapper;

    @AfterEach
    void clearEnv() {
        MetaContext.setEnvironmentId(null);
    }

    @Test
    void create_rejectsSourceFromOtherTenant() {
        // Caller's own tenant: a real target env created via the service.
        Long ownTargetEnv = createOwnEnv("xt_tgt_" + shortId());
        // Foreign source env: directly inserted under a synthetic other tenant id.
        Long foreignTenantId = otherTenantId();
        Long foreignSourceEnv = insertForeignEnv(foreignTenantId, "xt_src_" + shortId());

        PromotionRequest req = buildRequest(foreignSourceEnv, ownTargetEnv, "any-pid-" + shortId());

        assertThatThrownBy(() -> promotionService.create(req, testTenant.getId(), testUser.getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Source environment not found in tenant");
    }

    @Test
    void create_rejectsTargetFromOtherTenant() {
        // Caller's own source env.
        Long ownSourceEnv = createOwnEnv("xt_src2_" + shortId());
        // Foreign target env under a synthetic other tenant id.
        Long foreignTenantId = otherTenantId();
        Long foreignTargetEnv = insertForeignEnv(foreignTenantId, "xt_tgt2_" + shortId());

        PromotionRequest req = buildRequest(ownSourceEnv, foreignTargetEnv, "any-pid-" + shortId());

        assertThatThrownBy(() -> promotionService.create(req, testTenant.getId(), testUser.getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Target environment not found in tenant");
    }

    // ---------- helpers ----------

    /** Caller's own tenant context — same flow as PromotionLifecycleIntegrationTest.createEnv. */
    private Long createOwnEnv(String code) {
        EnvironmentRequest req = new EnvironmentRequest();
        req.setCode(code);
        req.setName(code);
        req.setIsDefault(false);
        req.setSortOrder(0);
        EnvironmentResponse env = environmentService.create(req, testTenant.getId(), testUser.getId());
        Environment probe = environmentMapper.selectOne(
                new QueryWrapper<Environment>().eq("pid", env.getPid()).eq("tenant_id", testTenant.getId()));
        return probe.getId();
    }

    /**
     * Insert a synthetic env row under a different tenant id, bypassing the service layer.
     * The TenantLineInnerInterceptor still injects tenant_id on subsequent SELECTs from the
     * caller, which is exactly what we need to verify cross-tenant id rejection.
     */
    private Long insertForeignEnv(Long foreignTenantId, String code) {
        Long priorTenant = MetaContext.getCurrentTenantId();
        // Switch tenant context so the interceptor stamps tenant_id = foreignTenantId on insert.
        MetaContext.setCurrentTenantId(foreignTenantId);
        try {
            Environment env = new Environment();
            env.setPid(UniqueIdGenerator.generate());
            env.setTenantId(foreignTenantId);
            env.setCode(code);
            env.setName(code);
            env.setIsDefault(false);
            env.setIsLocked(false);
            env.setSortOrder(0);
            env.setStatus("active");
            env.setDeletedFlag(false);
            env.setCreatedAt(new Date());
            env.setUpdatedAt(new Date());
            environmentMapper.insert(env);
            return env.getId();
        } finally {
            MetaContext.setCurrentTenantId(priorTenant);
        }
    }

    /** Generate a tenant id that is guaranteed not to collide with the test tenant. */
    private Long otherTenantId() {
        // testTenant.getId() is a snowflake; offset by a large constant to land outside it.
        return testTenant.getId() + 999_999L;
    }

    private PromotionRequest buildRequest(Long sourceEnv, Long targetEnv, String pagePid) {
        PromotionRequest req = new PromotionRequest();
        req.setSourceEnvId(sourceEnv);
        req.setTargetEnvId(targetEnv);
        PromotionRequest.PromotionUnitDto unit = new PromotionRequest.PromotionUnitDto();
        unit.setResourceType("PAGE_SCHEMA");
        unit.setResourcePid(pagePid);
        unit.setSortOrder(0);
        req.setUnits(List.of(unit));
        return req;
    }

    private static String shortId() {
        return UniqueIdGenerator.generate().toLowerCase();
    }
}

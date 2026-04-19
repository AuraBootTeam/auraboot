package com.auraboot.framework.agent.controller;

import com.auraboot.framework.agent.memory.MemoryL1L2Promoter;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.dto.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * PR-85 / Phase 4 — admin-facing memory tier controls.
 *
 * <p>Design: {@code docs/plans/2026-04/2026-04-19-memory-l1-l2-promotion-design.md §9.2}.
 *
 * <p>Route lives under {@code /api/admin/**} so the platform-wide
 * {@link com.auraboot.framework.application.security.AdminRoleInterceptor}
 * already enforces tenant-admin role before the handler runs. Non-admin
 * callers get HTTP 200 with {@code code=409 / "admin role required"} from
 * the interceptor — we do not duplicate that guard here.
 *
 * <p>Feature-flagged via {@code acp.memory.l1l2.admin-promote.enabled}
 * (default {@code false}). When the flag is off the endpoint returns
 * {@code 409 / "admin_promote_disabled"} without touching the DB — the
 * controller bean still registers so the interceptor coverage smoke test
 * finds the route.
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/memory")
public class MemoryTierAdminController {

    private final MemoryL1L2Promoter promoter;

    @Value("${acp.memory.l1l2.admin-promote.enabled:false}")
    private boolean enabled;

    public MemoryTierAdminController(MemoryL1L2Promoter promoter) {
        this.promoter = promoter;
    }

    /**
     * {@code POST /api/admin/memory/{pid}/promote-now}
     *
     * <p>Body: {@code {"reason": "..."}} — required, non-blank, ≤512 chars.
     *
     * <p>Outcomes:
     * <ul>
     *   <li>{@code 200 / code=0} — row promoted; {@code data.outcome=promoted}
     *       and {@code data.target_pid} echoes the promoted pid.</li>
     *   <li>{@code 200 / code=0} with {@code data.outcome=dedup_hit} or
     *       {@code dedup_hit_semantic} — admin override hit a duplicate;
     *       target L2 row was bumped instead (hash / cosine merge).</li>
     *   <li>{@code 200 / code=409} — row not in {@code category='session'},
     *       row missing, or feature flag off.</li>
     * </ul>
     */
    @PostMapping("/{pid}/promote-now")
    public ApiResponse<Map<String, Object>> promoteNow(
            @PathVariable("pid") String pid,
            @RequestBody(required = false) Map<String, Object> body) {

        if (!enabled) {
            return ApiResponse.error(409, "admin_promote_disabled");
        }
        if (pid == null || pid.isBlank()) {
            return ApiResponse.error(400, "pid required");
        }

        String reason = extractReason(body);
        if (reason == null) {
            return ApiResponse.error(400, "reason required");
        }
        if (reason.length() > 512) {
            return ApiResponse.error(400, "reason must be <= 512 chars");
        }

        String adminUserId = Objects.toString(MetaContext.getCurrentUserId(), "unknown");
        Long tenantId = MetaContext.getCurrentTenantId();
        log.info("MemoryTierAdminController.promoteNow: admin={} tenant={} pid={} reason={}",
                adminUserId, tenantId, pid, reason);

        try {
            MemoryL1L2Promoter.AdminPromoteOutcome result =
                    promoter.promoteNow(pid, adminUserId, reason);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("pid", pid);
            out.put("outcome", result.outcome().name().toLowerCase());
            out.put("target_pid", result.targetPid());
            out.put("reason", reason);
            return ApiResponse.ok(out);
        } catch (IllegalStateException e) {
            // memory_not_found / memory_not_l1 — caller must pick a live L1 pid.
            return ApiResponse.error(409, e.getMessage());
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        }
    }

    private static String extractReason(Map<String, Object> body) {
        if (body == null) return null;
        Object v = body.get("reason");
        if (v == null) return null;
        String s = v.toString().trim();
        return s.isBlank() ? null : s;
    }
}

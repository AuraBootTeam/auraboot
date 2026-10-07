package com.auraboot.framework.tenant.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.auth.service.SessionManagementService;
import com.auraboot.framework.auth.util.JwtUtil;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.exception.RootUnCheckedException;
import com.auraboot.framework.exception.ValidationException;
import com.auraboot.framework.menu.entity.Menu;
import com.auraboot.framework.menu.constant.MenuStatus;
import com.auraboot.framework.menu.service.MenuService;
import com.auraboot.framework.rbac.entity.Role;
import com.auraboot.framework.rbac.entity.UserRole;
import com.auraboot.framework.rbac.service.RoleService;
import com.auraboot.framework.rbac.service.UserRoleService;
import com.auraboot.framework.permission.service.AutoPermissionAssignmentService;
import com.auraboot.framework.saas.config.service.SystemModeService;
import com.auraboot.framework.tenant.dao.entity.Invitation;
import com.auraboot.framework.tenant.dao.entity.Tenant;
import com.auraboot.framework.tenant.dao.entity.TenantMember;
import com.auraboot.framework.tenant.dto.TenantRequest;
import com.auraboot.framework.tenant.dto.TenantResponse;
import com.auraboot.framework.tenant.dto.TenantSelectionRequest;
import com.auraboot.framework.tenant.dto.TenantSelectionResponse;
import com.auraboot.framework.tenant.service.TenantApplicationService;
import com.auraboot.framework.application.release.ApplicationReleaseControlService;
import com.auraboot.framework.common.util.UlidGenerator;
import com.auraboot.framework.meta.service.IdempotencyService;
import com.auraboot.framework.tenant.service.TenantBootstrapService;
import com.auraboot.framework.tenant.service.TenantInviteService;
import com.auraboot.framework.tenant.service.TenantMemberService;
import com.auraboot.framework.tenant.service.TenantService;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

import static com.auraboot.framework.common.constant.ResponseCode.CommonValidationFailed;
import com.auraboot.framework.common.constant.StatusConstants;


/**
 * 租户服务实现类
 */
@Slf4j
@Service
@Transactional
public class TenantApplicationServiceImpl implements TenantApplicationService {

    @Autowired
    private TenantService tenantService;
    @Autowired
    private TenantMemberService tenantMemberService;

    @Autowired
    private TenantInviteService tenantInviteService;

    @Autowired
    private UserService userService;
    @Autowired
    private JwtUtil jwtUtil;
    @Autowired
    private UserDetailsService userDetailsService;
    @Autowired
    private SessionManagementService sessionManagementService;
    // 新增的依赖注入
    @Autowired
    private RoleService roleService;
    @Autowired
    private MenuService menuService;
    @Autowired
    private UserRoleService userRoleService;
    
    @Autowired
    private AutoPermissionAssignmentService autoPermissionAssignmentService;
    
    @Autowired
    private TenantBootstrapService tenantBootstrapService;

    @Autowired
    private SystemModeService systemModeService;

    @Autowired
    private com.auraboot.framework.plugin.service.BuiltinPluginImportService builtinPluginImportService;

    @Autowired
    private ApplicationReleaseControlService applicationReleaseControlService;

    @Autowired
    private IdempotencyService idempotencyService;

    @Autowired
    private com.auraboot.framework.application.release.ApplicationReleaseTenantInitializer applicationReleaseTenantInitializer;

    @Value("${aura.application.default-code:}")
    private String defaultApplicationCode;

    @Value("${aura.application.tenant-zero-import-enabled:false}")
    private boolean tenantZeroImportEnabled;

    /** Roles bound after tenant state initialization; comma-separated stable role codes. */
    @Value("${aura.tenant.creator-roles:}")
    private String tenantCreatorRoles;
    
    /**
     * 获取当前用户的租户信息
     * 
     * @param userId 用户ID
     * @return 租户响应DTO
     */
    @Override
    public TenantResponse getCurrentTenantInfo(Long userId) {
        log.info("获取当前用户租户信息: userId={}", userId);
        
        Long tenantId = MetaContext.getCurrentTenantId();
        
        if (tenantId == null) {
            log.error("租户上下文为空: userId={}", userId);
            throw new ValidationException(
                CommonValidationFailed,
                "租户上下文为空"
            );
        }
        
        Tenant tenant = tenantService.getById(tenantId);
        
        if (tenant == null) {
            log.error("租户不存在: tenantId={}", tenantId);
            throw new ValidationException(
                CommonValidationFailed,
                "租户不存在: tenantId=" + tenantId
            );
        }
        
        return convertToResponse(tenant);
    }
    
    /**
     * 更新租户信息
     * 
     * @param tenantPid 租户PID
     * @param request 更新请求
     * @param userId 用户ID
     * @return 租户响应DTO
     */
    @Override
    public TenantResponse updateTenant(String tenantPid, TenantRequest request, Long userId) {
        log.info("更新租户信息: tenantPid={}, userId={}", tenantPid, userId);
        
        Tenant tenant = tenantService.findByPid(tenantPid);
        
        if (tenant == null) {
            log.error("租户不存在: tenantPid={}", tenantPid);
            throw new ValidationException(
                CommonValidationFailed,
                "租户不存在: tenantPid=" + tenantPid
            );
        }
        
        // TODO: 添加权限验证 - 检查用户是否有权限修改该租户
        
        // 更新租户信息
        if (request.getDisplayName() != null) {
            tenant.setDisplayName(request.getDisplayName());
        }
        if (request.getIndustry() != null) {
            tenant.setIndustry(request.getIndustry());
        }
        if (request.getContactEmail() != null) {
            tenant.setContactEmail(request.getContactEmail());
        }
        if (request.getContactPhone() != null) {
            tenant.setContactPhone(request.getContactPhone());
        }
        if (request.getDescription() != null) {
            tenant.setDescription(request.getDescription());
        }
        
        tenant.setUpdatedBy(userId);
        Tenant updatedTenant = tenantService.updateTenant(tenant);
        
        return convertToResponse(updatedTenant);
    }

    @Override
    public TenantSelectionResponse createTenantForUser(TenantSelectionRequest request, User user) {
        if (!systemModeService.isTenantSelfProvisioningAllowed()) {
            throw new RootUnCheckedException(ResponseCode.FORBIDDEN,
                    "Tenant self-provisioning is disabled for this deployment");
        }
        Long currentTenantId = MetaContext.exists() ? MetaContext.getCurrentTenantId() : null;
        Long requestScopeTenantId = currentTenantId == null ? 0L : currentTenantId;
        String operationCode = "tenant.create.user." + user.getId();
        Map<String, Object> requestIntent = tenantCreationIntent(request, user.getId());
        Map<String, Object> replay = request.getClientRequestId() == null || request.getClientRequestId().isBlank()
                ? null
                : MetaContext.runWithoutTenantFilter(() -> idempotencyService.claimScopedIdempotency(
                        request.getClientRequestId(), operationCode, requestIntent, requestScopeTenantId));
        if (replay != null) {
            return tenantCreationResponse(replay);
        }
        TenantSelectionResponse response = new TenantSelectionResponse();

        // Reject duplicate tenant name
        Tenant existingTenant = tenantService.findByName(request.getTenantName());
        if (existingTenant != null) {
            throw new ValidationException(
                CommonValidationFailed,
                "Tenant name already exists: " + request.getTenantName()
            );
        }

        // 创建租户
        Tenant tenant = new Tenant();
        tenant.setPid(UniqueIdGenerator.generate());
        tenant.setName(request.getTenantName());
        tenant.setDisplayName(request.getDisplayName());
        tenant.setIndustry(request.getIndustry());
        tenant.setContactEmail(request.getContactEmail());
        tenant.setContactPhone(request.getContactPhone());
        tenant.setDescription(request.getDescription());
        tenant.setStatus(StatusConstants.ACTIVE);
        tenant.setCreatedBy(user.getId());
        tenant.setUpdatedBy(user.getId());

        // tune logo

        Tenant createdTenant;
        try {
            createdTenant = tenantService.createTenant(tenant);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new ValidationException(
                CommonValidationFailed,
                "Tenant name already exists: " + request.getTenantName()
            );
        }

        var newMember = tenantMemberService.addMember(user.getId(),createdTenant.getId(), StatusConstants.ACTIVE);

        // 使用新的TenantBootstrapService初始化默认RBAC数据
        try {
            TenantBootstrapService.BootstrapResult result = tenantBootstrapService.bootstrapTenant(
                createdTenant.getId(),
                user.getId()
            );
            log.info("租户初始化成功: {}", result.getMessage());
        } catch (Exception e) {
            log.error("租户初始化失败，回滚事务", e);
            throw e;
        }

        // Bind the exact stable application release before projecting tenant-owned state.
        boolean applicationBound = defaultApplicationCode != null && !defaultApplicationCode.isBlank();
        if (applicationBound) {
            var binding = applicationReleaseControlService.bindStable(
                    createdTenant.getId(), defaultApplicationCode.trim(),
                    "user:id:" + user.getId(), UlidGenerator.generate());
            log.info("Bound tenant {} to application {} release {}",
                    createdTenant.getId(), defaultApplicationCode.trim(), binding.releaseId());
            if (tenantZeroImportEnabled) {
                applicationReleaseTenantInitializer.initialize(
                        createdTenant.getId(), defaultApplicationCode.trim());
            }
        }

        if (!tenantZeroImportEnabled || !applicationBound) {
            builtinPluginImportService.importForTenant(createdTenant.getId(), user.getId());
        }

        // Bind the deployment-declared creator roles after tenant-owned role state exists.
        if (tenantCreatorRoles != null && !tenantCreatorRoles.isBlank() && newMember != null) {
            for (String roleCode : tenantCreatorRoles.split(",")) {
                String trimmed = roleCode.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                userRoleService.assignRolesToMemberByRoleCodes(
                        newMember.getPid(), List.of(trimmed), createdTenant.getId(), user.getId());
                log.info("Bound creator role {} to member {} in tenant {}", trimmed, newMember.getPid(), createdTenant.getId());
            }
        }

        // 生成新的JWT令牌（包含租户信息 + memberId + security version）。
        // memberId is required: PermissionInterceptor/UserPermissionServiceImpl resolves
        // a user's permissions through their tenant membership, so a JWT carrying only
        // tenantId (no memberId) yields "MemberId not available in MetaContext" and 403s
        // every permission-gated endpoint. A freshly self-registered founder must land in
        // a fully usable workspace, so mint the member-scoped token here (mirrors login).
        int securityVersion = user.getSecurityVersion() != null ? user.getSecurityVersion() : 0;
        String newJwt = jwtUtil.generateTokenWithTenantId(
                userDetailsService.loadUserByUsername(user.getEmail()),
                user.getPid(),
                createdTenant.getId(),
                newMember != null ? newMember.getId() : null,
                securityVersion
        );
        // Persist the session in the same transaction as the new tenant/member. The session FK
        // must never observe a tenant_member row that is still uncommitted in a suspended
        // transaction.
        sessionManagementService.createSession(user.getId(), newJwt, null, null);

        response.setStatus(StatusConstants.SUCCESS);
        // i18n key — resolved to the request locale by the controller (TenantSelectionController).
        response.setMessage("$i18n:tenant.application.create_success");
        response.setTenantId(createdTenant.getId());
        response.setTenantName(createdTenant.getName());
        response.setJwt(newJwt);
        response.setNeedsApproval(false);

        if (request.getClientRequestId() != null && !request.getClientRequestId().isBlank()) {
            // Both mapper operations explicitly constrain the original tenant and user operation.
            // A tenantless caller uses scope 0, which differs from the ambient empty-tenant filter.
            MetaContext.runWithoutTenantFilter(() -> idempotencyService.recordScopedOutcome(
                    request.getClientRequestId(), operationCode, requestIntent,
                    tenantCreationOutcome(response), requestScopeTenantId));
        }

        return response;
    }

    private static Map<String, Object> tenantCreationIntent(TenantSelectionRequest request, Long userId) {
        Map<String, Object> intent = new LinkedHashMap<>();
        intent.put("userId", userId);
        intent.put("tenantName", Objects.toString(request.getTenantName(), ""));
        intent.put("displayName", Objects.toString(request.getDisplayName(), ""));
        intent.put("industry", Objects.toString(request.getIndustry(), ""));
        intent.put("contactEmail", Objects.toString(request.getContactEmail(), ""));
        intent.put("contactPhone", Objects.toString(request.getContactPhone(), ""));
        intent.put("description", Objects.toString(request.getDescription(), ""));
        return intent;
    }

    private static Map<String, Object> tenantCreationOutcome(TenantSelectionResponse response) {
        Map<String, Object> outcome = new LinkedHashMap<>();
        outcome.put("status", Objects.toString(response.getStatus(), ""));
        outcome.put("message", Objects.toString(response.getMessage(), ""));
        outcome.put("tenantId", response.getTenantId());
        outcome.put("tenantName", Objects.toString(response.getTenantName(), ""));
        outcome.put("jwt", Objects.toString(response.getJwt(), ""));
        outcome.put("needsApproval", response.getNeedsApproval());
        return outcome;
    }

    private static TenantSelectionResponse tenantCreationResponse(Map<String, Object> outcome) {
        TenantSelectionResponse response = new TenantSelectionResponse();
        response.setStatus(Objects.toString(outcome.get("status"), null));
        response.setMessage(Objects.toString(outcome.get("message"), null));
        Object tenantId = outcome.get("tenantId");
        if (tenantId instanceof Number number) {
            response.setTenantId(number.longValue());
        } else if (tenantId != null) {
            response.setTenantId(Long.valueOf(tenantId.toString()));
        }
        response.setTenantName(Objects.toString(outcome.get("tenantName"), null));
        response.setJwt(Objects.toString(outcome.get("jwt"), null));
        response.setNeedsApproval(Boolean.TRUE.equals(outcome.get("needsApproval")));
        return response;
    }

    @Override
    public TenantSelectionResponse joinTenantByInviteCode(TenantSelectionRequest request, User user) {
        if (systemModeService.isSingleTenant()) {
            throw new RootUnCheckedException(ResponseCode.FORBIDDEN,
                    "Tenant joining is disabled in single business tenant mode");
        }
        TenantSelectionResponse response = new TenantSelectionResponse();

        // 验证邀请码
        Invitation invitation = tenantInviteService.findByInvitationCode(request.getInviteCode());
        if (invitation == null) {
            response.setStatus("error");
            response.setMessage("$i18n:tenant.application.invite_invalid");
            return response;
        }

        if (!StatusConstants.ACTIVE.equals(invitation.getStatus())) {
            response.setStatus("error");
            response.setMessage("$i18n:tenant.application.invite_revoked");
            return response;
        }

        if (invitation.getExpiredAt() != null && invitation.getExpiredAt().isBefore(Instant.now())) {
            response.setStatus("error");
            response.setMessage("$i18n:tenant.application.invite_expired");
            return response;
        }



        tenantMemberService.addMember(user.getId(), invitation.getTenantId(), StatusConstants.PENDING);

        Tenant tenant = tenantService.getById(invitation.getTenantId());

        response.setStatus(StatusConstants.PENDING);
        response.setMessage("$i18n:tenant.application.join_pending");
        response.setTenantId(invitation.getTenantId());
        response.setTenantName(tenant != null ? tenant.getName() : "$i18n:tenant.application.unknown_tenant");
        response.setNeedsApproval(true);

        return response;
    }
    
    /**
     * 根据PID获取租户信息
     * 
     * @param tenantPid 租户PID
     * @param userId 用户ID (用于权限验证)
     * @return 租户响应DTO
     */
    @Override
    public TenantResponse getTenantByPid(String tenantPid, Long userId) {
        log.info("获取租户信息: tenantPid={}, userId={}", tenantPid, userId);
        
        Tenant tenant = tenantService.findByPid(tenantPid);
        
        if (tenant == null) {
            log.error("租户不存在: tenantPid={}", tenantPid);
            throw new ValidationException(
                CommonValidationFailed, 
                "租户不存在: tenantPid=" + tenantPid
            );
        }
        
        // TODO: 添加权限验证 - 检查用户是否有权限访问该租户
        
        return convertToResponse(tenant);
    }


    private TenantResponse convertToResponse(Tenant tenant) {
        TenantResponse response = new TenantResponse();
        BeanUtils.copyProperties(tenant, response);

        // 获取统计信息
//        Long memberCount = tenantMemberService.countByTenantId(tenant.getId());
//        Long storeCount = storeService.countByTenantId(tenant.getId());
//
//        response.setMemberCount(memberCount);
//        response.setStoreCount(storeCount);

        return response;
    }
}

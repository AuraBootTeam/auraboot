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
import com.auraboot.framework.tenant.dao.entity.Invitation;
import com.auraboot.framework.tenant.dao.entity.Tenant;
import com.auraboot.framework.tenant.dao.entity.TenantMember;
import com.auraboot.framework.tenant.dto.TenantRequest;
import com.auraboot.framework.tenant.dto.TenantResponse;
import com.auraboot.framework.tenant.dto.TenantSelectionRequest;
import com.auraboot.framework.tenant.dto.TenantSelectionResponse;
import com.auraboot.framework.tenant.service.TenantApplicationService;
import com.auraboot.framework.tenant.service.TenantBootstrapService;
import com.auraboot.framework.tenant.service.TenantInviteService;
import com.auraboot.framework.tenant.service.TenantMemberService;
import com.auraboot.framework.tenant.service.TenantService;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
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
    private com.auraboot.framework.plugin.service.BuiltinPluginImportService builtinPluginImportService;
    
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

        tenantMemberService.addMember(user.getId(),createdTenant.getId(), StatusConstants.ACTIVE);

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

        // Import built-in plugins
        builtinPluginImportService.importForTenant(
            createdTenant.getId(),
            user.getId()
        );

        // 生成新的JWT令牌（包含租户信息 + security version）
        int securityVersion = user.getSecurityVersion() != null ? user.getSecurityVersion() : 0;
        String newJwt = jwtUtil.generateTokenWithTenantId(
                userDetailsService.loadUserByUsername(user.getEmail()),
                user.getPid(),
                createdTenant.getId(),
                securityVersion
        );
        // Register this JWT in server-side session store (NOT_SUPPORTED propagation, won't affect this transaction)
        sessionManagementService.createSession(user.getId(), newJwt, null, null);

        response.setStatus(StatusConstants.SUCCESS);
        response.setMessage("租户创建成功，您已成为该租户的管理员");
        response.setTenantId(createdTenant.getId());
        response.setTenantName(createdTenant.getName());
        response.setJwt(newJwt);
        response.setNeedsApproval(false);

        return response;
    }

    @Override
    public TenantSelectionResponse joinTenantByInviteCode(TenantSelectionRequest request, User user) {
        TenantSelectionResponse response = new TenantSelectionResponse();

        // 验证邀请码
        Invitation invitation = tenantInviteService.findByInvitationCode(request.getInviteCode());
        if (invitation == null) {
            response.setStatus("error");
            response.setMessage("无效的邀请码");
            return response;
        }

        if (!StatusConstants.ACTIVE.equals(invitation.getStatus())) {
            response.setStatus("error");
            response.setMessage("邀请码已失效");
            return response;
        }

        if (invitation.getExpiredAt() != null && invitation.getExpiredAt().isBefore(Instant.now())) {
            response.setStatus("error");
            response.setMessage("邀请码已过期");
            return response;
        }



        tenantMemberService.addMember(user.getId(), invitation.getTenantId(), StatusConstants.PENDING);

        Tenant tenant = tenantService.getById(invitation.getTenantId());

        response.setStatus(StatusConstants.PENDING);
        response.setMessage("加入申请已提交，等待租户管理员审批");
        response.setTenantId(invitation.getTenantId());
        response.setTenantName(tenant != null ? tenant.getName() : "未知租户");
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

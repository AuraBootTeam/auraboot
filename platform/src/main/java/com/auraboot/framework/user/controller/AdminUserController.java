package com.auraboot.framework.user.controller;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.application.annotation.CurrentUserId;
import com.auraboot.framework.auth.service.PasswordManagementService;
import com.auraboot.framework.auth.service.PasswordPolicyService;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.exception.RootUnCheckedException;
import com.auraboot.framework.permission.constants.MetaPermission;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.user.dto.EmployeeAccountProvisionRequest;
import com.auraboot.framework.user.dto.EmployeeAccountProvisionResponse;
import com.auraboot.framework.user.dto.EmployeeAccountImportPreviewResponse;
import com.auraboot.framework.user.dto.EmployeeAccountRow;
import com.auraboot.framework.user.dto.UserProvisionRequest;
import com.auraboot.framework.user.dto.UserProvisionResponse;
import com.auraboot.framework.user.dto.UserSearchDTO;
import com.auraboot.framework.user.service.EmployeeAccountProvisioningService;
import com.auraboot.framework.user.service.EmployeeAccountWorkbookParser;
import com.auraboot.framework.user.service.UserProvisioningService;
import com.auraboot.framework.user.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
@Tag(name = "Admin User Management", description = "Administrator user management operations")
public class AdminUserController {

    private final PasswordManagementService passwordManagementService;
    private final PasswordPolicyService passwordPolicyService;
    private final UserProvisioningService userProvisioningService;
    private final EmployeeAccountProvisioningService employeeAccountProvisioningService;
    private final EmployeeAccountWorkbookParser employeeAccountWorkbookParser;
    private final UserService userService;

    private static final String CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#$%";
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * Admin Create User (User Provisioning).
     * Creates a user account, adds to the current tenant, and assigns roles.
     * This is the primary user entry mode for private deployment / ERP / internal systems.
     */
    @PostMapping
    @Operation(summary = "Provision a new user in the current tenant")
    @RequirePermission(MetaPermission.ROLE_MANAGE)
    public ApiResponse<UserProvisionResponse> provisionUser(
            @Valid @RequestBody UserProvisionRequest request,
            @CurrentUserId Long currentUserId) {
        Long tenantId = MetaContext.getCurrentTenantId();
        if (tenantId == null) {
            throw new RootUnCheckedException(ResponseCode.BadParam, "No tenant context — admin must be in a tenant");
        }
        UserProvisionResponse response = userProvisioningService.provision(request, tenantId, currentUserId);
        return ApiResponse.success(response);
    }

    /**
     * Customer employee account batch provisioning.
     * Names are used as login names; passwords are generated as prefix + random digits.
     */
    @PostMapping("/employee-accounts")
    @Operation(summary = "Provision customer employee accounts in the current tenant")
    @RequirePermission(MetaPermission.ROLE_MANAGE)
    public ApiResponse<EmployeeAccountProvisionResponse> provisionEmployeeAccounts(
            @Valid @RequestBody EmployeeAccountProvisionRequest request,
            @CurrentUserId Long currentUserId) {
        Long tenantId = MetaContext.getCurrentTenantId();
        if (tenantId == null) {
            throw new RootUnCheckedException(ResponseCode.BadParam, "No tenant context — admin must be in a tenant");
        }
        EmployeeAccountProvisionResponse response =
                employeeAccountProvisioningService.provision(request, tenantId, currentUserId);
        return ApiResponse.success(response);
    }

    /**
     * Download the canonical employee account workbook.
     */
    @GetMapping(value = "/employee-accounts/import/template",
            produces = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    @Operation(summary = "Download the employee account import template")
    @RequirePermission(MetaPermission.ROLE_MANAGE)
    public void downloadEmployeeAccountTemplate(HttpServletResponse response) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        employeeAccountWorkbookParser.writeTemplate(output);
        byte[] bytes = output.toByteArray();
        String encodedName = URLEncoder.encode("用户导入模板.xlsx", StandardCharsets.UTF_8)
                .replace("+", "%20");
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition",
                "attachment; filename=\"user-import-template.xlsx\"; filename*=UTF-8''" + encodedName);
        response.setContentLength(bytes.length);
        response.getOutputStream().write(bytes);
    }

    /**
     * Parse and validate an employee account workbook without writing data.
     */
    @PostMapping(value = "/employee-accounts/import/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Preview an employee account workbook in the current tenant")
    @RequirePermission(MetaPermission.ROLE_MANAGE)
    public ApiResponse<EmployeeAccountImportPreviewResponse> previewEmployeeAccounts(
            @RequestParam("file") MultipartFile file) {
        Long tenantId = requireTenantId();
        List<EmployeeAccountRow> rows = parseEmployeeWorkbook(file);
        return ApiResponse.success(employeeAccountProvisioningService.preview(rows, tenantId));
    }

    /**
     * Customer employee account provisioning from Excel.
     * Canonical headers: 姓名*, 登录名, 手机号, 工号, 部门编码, 岗位编码.
     */
    @PostMapping(value = "/employee-accounts/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Import customer employee accounts from Excel in the current tenant")
    @RequirePermission(MetaPermission.ROLE_MANAGE)
    public ApiResponse<EmployeeAccountProvisionResponse> importEmployeeAccounts(
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false) String passwordPrefix,
            @RequestParam(required = false) Integer randomDigitCount,
            @CurrentUserId Long currentUserId) {
        Long tenantId = requireTenantId();
        EmployeeAccountProvisionRequest request = new EmployeeAccountProvisionRequest();
        request.setEmployees(parseEmployeeWorkbook(file));
        if (passwordPrefix != null && !passwordPrefix.isBlank()) {
            request.setPasswordPrefix(passwordPrefix);
        }
        if (randomDigitCount != null) {
            request.setRandomDigitCount(randomDigitCount);
        }
        EmployeeAccountProvisionResponse response =
                employeeAccountProvisioningService.provision(request, tenantId, currentUserId);
        return ApiResponse.success(response);
    }

    private Long requireTenantId() {
        Long tenantId = MetaContext.getCurrentTenantId();
        if (tenantId == null) {
            throw new RootUnCheckedException(ResponseCode.BadParam,
                    "No tenant context — admin must be in a tenant");
        }
        return tenantId;
    }

    private List<EmployeeAccountRow> parseEmployeeWorkbook(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new RootUnCheckedException(ResponseCode.BadParam,
                    "Employee account import file is required");
        }
        String fileName = file.getOriginalFilename();
        if (fileName == null || !fileName.toLowerCase(java.util.Locale.ROOT).endsWith(".xlsx")) {
            throw new RootUnCheckedException(ResponseCode.BadParam, "Only .xlsx files are supported");
        }
        try {
            return employeeAccountWorkbookParser.parse(file.getInputStream());
        } catch (IOException e) {
            throw new RootUnCheckedException(ResponseCode.BadParam,
                    "Failed to read employee account import file", e);
        }
    }

    /**
     * Tenant-scoped user search for picker UIs (MemberPicker, reference-field dropdown,
     * approval assignee selection, etc.).
     *
     * <p>Authenticated endpoint — authentication is enforced by the default Spring Security
     * filter chain; any tenant member may call it because picker selection is a UX need, not
     * an admin action. The query is strictly scoped to the caller's current tenant via
     * ab_tenant_member (status=active), so callers can never see users outside their tenant.</p>
     *
     * <p>Replaces the previously-removed public {@code /api/users/search} endpoint, which was
     * deleted during the 2026-04 security review because it lacked authentication.</p>
     */
    @GetMapping("/search")
    @Operation(summary = "Search users in the current tenant by keyword (for picker UIs)")
    public ApiResponse<List<UserSearchDTO>> search(
            @RequestParam(required = false, defaultValue = "") String keyword,
            @RequestParam(required = false, defaultValue = "20") int size) {
        Long tenantId = MetaContext.getCurrentTenantId();
        if (tenantId == null) {
            throw new RootUnCheckedException(ResponseCode.BadParam, "No tenant context — caller must be in a tenant");
        }
        List<UserSearchDTO> results = userService.searchInTenant(tenantId, keyword, size);
        return ApiResponse.success(results);
    }

    /**
     * Lookup a single user by PID for picker resolve-name flows.
     * Returns the same safe projection as /search; password and other sensitive
     * fields are never included. Tenant-scoped: 404 if the user is not in the
     * caller's tenant.
     */
    @GetMapping("/{userPid}")
    @Operation(summary = "Get a single user by PID (picker resolve-name)")
    public ApiResponse<UserSearchDTO> getOne(@PathVariable String userPid) {
        Long tenantId = MetaContext.getCurrentTenantId();
        if (tenantId == null) {
            throw new RootUnCheckedException(ResponseCode.BadParam, "No tenant context");
        }
        UserSearchDTO dto = userService.findInTenantByPid(tenantId, userPid);
        if (dto == null) {
            throw new RootUnCheckedException(ResponseCode.NOT_FOUND, "User not found in current tenant: " + userPid);
        }
        return ApiResponse.success(dto);
    }

    @PostMapping("/{userPid}/reset-password")
    @Operation(summary = "Admin reset user password")
    @RequirePermission(MetaPermission.ROLE_MANAGE)
    public ApiResponse<Map<String, String>> resetPassword(@PathVariable String userPid) {
        String tempPassword = generatePolicyCompliantPassword(12);
        passwordManagementService.resetPasswordByAdmin(userPid, tempPassword);

        log.info("Admin reset password for user {}, mustChangePassword=false", userPid);

        return ApiResponse.success(Map.of("tempPassword", tempPassword));
    }

    private String generateRandomPassword(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(CHARS.charAt(RANDOM.nextInt(CHARS.length())));
        }
        return sb.toString();
    }

    private String generatePolicyCompliantPassword(int length) {
        for (int i = 0; i < 20; i++) {
            String candidate = generateRandomPassword(length);
            if (passwordPolicyService.isValid(candidate)) {
                return candidate;
            }
        }
        throw new RootUnCheckedException(ResponseCode.PasswordTooWeak, "Unable to generate a policy-compliant temporary password");
    }
}

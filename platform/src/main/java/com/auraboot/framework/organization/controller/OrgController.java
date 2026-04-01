package com.auraboot.framework.organization.controller;

import io.swagger.v3.oas.annotations.tags.Tag;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.exception.RootUnCheckedException;
import com.auraboot.framework.meta.dto.DynamicQueryRequest;
import com.auraboot.framework.meta.dto.PaginationResult;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.organization.dto.*;
import com.auraboot.framework.organization.service.OrgEmployeeService;
import com.auraboot.framework.organization.service.OrganizationService;
import com.auraboot.framework.tenant.dao.entity.TenantMember;
import com.auraboot.framework.tenant.dao.mapper.TenantMemberMapper;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.mapper.UserMapper;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

import static com.auraboot.framework.common.constant.ResponseCode.BadParam;

/**
 * Organization management — department tree, employee CRUD, member linking.
 */
@Slf4j
@RestController
@RequestMapping("/api/org")
@Tag(name = "Organization Management", description = "Department tree and employee management")
@RequiredArgsConstructor
public class OrgController {

    private static final String MODEL_ORG_DEPARTMENT = "org_department";

    private final OrganizationService organizationService;
    private final OrgEmployeeService orgEmployeeService;
    private final DynamicDataService dynamicDataService;
    private final TenantMemberMapper tenantMemberMapper;
    private final UserMapper userMapper;

    // ==================== Department Endpoints ====================

    /**
     * Get the full department tree for the current tenant.
     */
    @GetMapping("/departments/tree")
    public ApiResponse<List<DepartmentTreeNode>> getDepartmentTree() {
        Long tenantId = MetaContext.getCurrentTenantId();
        return ApiResponse.success(organizationService.getDepartmentTree(tenantId));
    }

    /**
     * Create a new department.
     */
    @PostMapping("/departments")
    public ApiResponse<Map<String, Object>> createDepartment(@RequestBody Map<String, Object> data) {
        Map<String, Object> created = dynamicDataService.create(MODEL_ORG_DEPARTMENT, data);
        return ApiResponse.success(created);
    }

    /**
     * Update a department by PID.
     */
    @PutMapping("/departments/{pid}")
    public ApiResponse<Void> updateDepartment(
            @PathVariable String pid,
            @RequestBody Map<String, Object> data) {
        dynamicDataService.update(MODEL_ORG_DEPARTMENT, pid, data);
        return ApiResponse.success();
    }

    /**
     * Delete a department by PID.
     * Validates no child departments and no employees exist before deletion.
     */
    @DeleteMapping("/departments/{pid}")
    public ApiResponse<Void> deleteDepartment(@PathVariable String pid) {
        // Check for child departments
        List<String> subPids = organizationService.getDeptAndSubPids(pid);
        if (subPids.size() > 1) {
            throw new RootUnCheckedException(BadParam,
                "Cannot delete department: it has child departments. Remove children first.");
        }

        // Check for employees in this department
        PaginationResult<OrgEmployeeDTO> employees = organizationService.getEmployeesByDept(
            pid, false, 1, 1, null);
        if (employees.getTotal() > 0) {
            throw new RootUnCheckedException(BadParam,
                "Cannot delete department: it still has employees. Transfer or remove them first.");
        }

        dynamicDataService.delete(MODEL_ORG_DEPARTMENT, pid);
        return ApiResponse.success();
    }

    // ==================== Employee Endpoints ====================

    /**
     * List employees in a department, optionally including sub-departments.
     */
    @GetMapping("/departments/{pid}/employees")
    public ApiResponse<PaginationResult<OrgEmployeeDTO>> getEmployeesByDept(
            @PathVariable String pid,
            @RequestParam(defaultValue = "true") boolean recursive,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String keyword) {
        PaginationResult<OrgEmployeeDTO> result = organizationService.getEmployeesByDept(
            pid, recursive, pageNum, pageSize, keyword);
        return ApiResponse.success(result);
    }

    /**
     * One-stop employee creation: creates user + member + employee with bidirectional linking.
     */
    @PostMapping("/employees")
    public ApiResponse<OrgEmployeeDTO> createEmployee(@Valid @RequestBody CreateEmployeeRequest request) {
        OrgEmployeeDTO employee = orgEmployeeService.createWithUser(request);
        return ApiResponse.success(employee);
    }

    /**
     * Link an existing tenant member to a new employee record.
     */
    @PostMapping("/employees/link")
    public ApiResponse<OrgEmployeeDTO> linkMember(@Valid @RequestBody LinkMemberRequest request) {
        OrgEmployeeDTO employee = orgEmployeeService.linkMember(request);
        return ApiResponse.success(employee);
    }

    /**
     * Update an employee record via dynamic data service.
     */
    @PutMapping("/employees/{pid}")
    public ApiResponse<Void> updateEmployee(
            @PathVariable String pid,
            @RequestBody Map<String, Object> data) {
        dynamicDataService.update("org_employee", pid, data);
        return ApiResponse.success();
    }

    /**
     * Transfer an employee to a new department and/or position.
     */
    @PutMapping("/employees/{pid}/transfer")
    public ApiResponse<Void> transferEmployee(
            @PathVariable String pid,
            @Valid @RequestBody TransferRequest request) {
        orgEmployeeService.transfer(pid, request);
        return ApiResponse.success();
    }

    /**
     * Batch transfer multiple employees to a new department and/or position.
     */
    @PutMapping("/employees/batch-transfer")
    public ApiResponse<Void> batchTransferEmployees(@Valid @RequestBody BatchTransferRequest request) {
        TransferRequest transferRequest = new TransferRequest();
        transferRequest.setNewDeptPid(request.newDeptPid());
        transferRequest.setNewPositionPid(request.newPositionPid());
        orgEmployeeService.batchTransfer(request.employeePids(), transferRequest);
        return ApiResponse.success();
    }

    // ==================== Unlinked Members ====================

    /**
     * Get tenant members that have no associated employee record.
     * These members can be linked to org employees via the /employees/link endpoint.
     */
    @GetMapping("/members/unlinked")
    public ApiResponse<List<Map<String, Object>>> getUnlinkedMembers(
            @RequestParam(required = false) String keyword) {
        Long tenantId = MetaContext.getCurrentTenantId();

        // Query active members without employee_id
        List<TenantMember> allMembers = tenantMemberMapper.findByTenantId(tenantId);
        List<TenantMember> unlinked = allMembers.stream()
            .filter(m -> m.getEmployeeId() == null)
            .filter(m -> "active".equalsIgnoreCase(m.getStatus()))
            .collect(Collectors.toList());

        // Enrich with user info and apply keyword filter
        List<Map<String, Object>> result = new ArrayList<>();
        for (TenantMember member : unlinked) {
            User user = userMapper.selectById(member.getUserId());
            if (user == null) {
                continue;
            }

            // Apply keyword filter on name, email, phone
            if (keyword != null && !keyword.isBlank()) {
                String lowerKeyword = keyword.toLowerCase();
                boolean matches = (user.getNickName() != null && user.getNickName().toLowerCase().contains(lowerKeyword))
                    || (user.getEmail() != null && user.getEmail().toLowerCase().contains(lowerKeyword))
                    || (user.getMobile() != null && user.getMobile().toLowerCase().contains(lowerKeyword));
                if (!matches) {
                    continue;
                }
            }

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("memberPid", member.getPid());
            item.put("userId", member.getUserId());
            item.put("name", user.getNickName());
            item.put("email", user.getEmail());
            item.put("phone", user.getMobile());
            item.put("status", member.getStatus());
            result.add(item);
        }

        return ApiResponse.success(result);
    }
}

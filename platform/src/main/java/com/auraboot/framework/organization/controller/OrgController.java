package com.auraboot.framework.organization.controller;

import io.swagger.v3.oas.annotations.tags.Tag;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.exception.RootUnCheckedException;
import com.auraboot.framework.meta.dto.DynamicQueryRequest;
import com.auraboot.framework.meta.dto.PaginationResult;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.organization.dto.*;
import com.auraboot.framework.organization.service.OrgEmployeeService;
import com.auraboot.framework.organization.service.OrganizationService;
import com.auraboot.framework.organization.service.PermittedDepartmentTreeService;
import com.auraboot.framework.permission.annotation.RequirePermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.*;

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
    private final PermittedDepartmentTreeService permittedDepartmentTreeService;
    private final OrgEmployeeService orgEmployeeService;
    private final DynamicDataService dynamicDataService;

    // ==================== Department Endpoints ====================

    /**
     * Get the full department tree for the current tenant.
     */
    @GetMapping("/departments/tree")
    public ApiResponse<List<DepartmentTreeNode>> getDepartmentTree() {
        Long tenantId = MetaContext.getCurrentTenantId();
        return ApiResponse.success(organizationService.getDepartmentTree(tenantId));
    }

    /** Get the department tree permitted by the caller's effective resource data scope. */
    @GetMapping("/departments/permitted-tree")
    public ApiResponse<List<DepartmentTreeNode>> getPermittedDepartmentTree(
            @RequestParam String resource,
            @RequestParam(defaultValue = "read") String action) {
        return ApiResponse.success(permittedDepartmentTreeService.getPermittedTree(resource, action));
    }

    /**
     * Create a new department.
     */
    @PostMapping("/departments")
    @RequirePermission("org.team.manage")
    public ApiResponse<Map<String, Object>> createDepartment(@RequestBody @jakarta.validation.constraints.NotEmpty Map<String, Object> data) {
        Map<String, Object> created = dynamicDataService.create(MODEL_ORG_DEPARTMENT, data);
        return ApiResponse.success(created);
    }

    /**
     * Update a department by PID.
     */
    @PutMapping("/departments/{pid}")
    @RequirePermission("org.team.manage")
    public ApiResponse<Void> updateDepartment(
            @PathVariable String pid,
            @RequestBody @jakarta.validation.constraints.NotEmpty Map<String, Object> data) {
        dynamicDataService.update(MODEL_ORG_DEPARTMENT, pid, data);
        return ApiResponse.success();
    }

    /**
     * Delete a department by PID.
     * Validates no child departments and no employees exist before deletion.
     */
    @DeleteMapping("/departments/{pid}")
    @RequirePermission("org.team.manage")
    public ApiResponse<Void> deleteDepartment(@PathVariable String pid) {
        requireDepartmentExists(pid);

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

    /**
     * Pre-check department deletion without deleting. Mirrors the exact guards
     * enforced by DELETE /departments/{pid} so the UI can warn before committing.
     */
    @GetMapping("/departments/{pid}/delete-check")
    public ApiResponse<DepartmentAdminRequests.DeleteCheckResponse> checkDepartmentDelete(
            @PathVariable String pid) {
        requireDepartmentExists(pid);

        List<DepartmentAdminRequests.DeleteBlocker> blockers = new ArrayList<>();
        int subTreeSize = organizationService.getDeptAndSubPids(pid).size();
        if (subTreeSize > 1) {
            blockers.add(new DepartmentAdminRequests.DeleteBlocker(
                "child_departments", subTreeSize - 1L,
                "Department has child departments. Remove children first."));
        }
        long employeeCount = organizationService.getEmployeesByDept(pid, false, 1, 1, null).getTotal();
        if (employeeCount > 0) {
            blockers.add(new DepartmentAdminRequests.DeleteBlocker(
                "employees", employeeCount,
                "Department still has employees. Transfer or remove them first."));
        }
        return ApiResponse.success(new DepartmentAdminRequests.DeleteCheckResponse(
            blockers.isEmpty(), blockers));
    }

    /**
     * Batch reorder departments. Each item is applied independently.
     */
    @PostMapping("/departments/sort")
    @RequirePermission("org.team.manage")
    public ApiResponse<Void> sortDepartments(
            @Valid @RequestBody DepartmentAdminRequests.SortRequest request) {
        for (DepartmentAdminRequests.SortItem item : request.items()) {
            dynamicDataService.update(MODEL_ORG_DEPARTMENT, item.pid(),
                Map.of("org_dept_order", item.order()));
        }
        return ApiResponse.success();
    }

    /**
     * Set the department commander (manager) by employee PID.
     * The manager is stored as an org_employee reference and resolved to a
     * user by the approver resolver.
     */
    @PostMapping("/departments/{pid}/set-commander")
    @RequirePermission("org.team.manage")
    public ApiResponse<Void> setDepartmentCommander(
            @PathVariable String pid,
            @Valid @RequestBody DepartmentAdminRequests.SetCommanderRequest request) {
        requireDepartmentExists(pid);
        if (dynamicDataService.getById("org_employee", request.employeePid()) == null) {
            throw new RootUnCheckedException(BadParam,
                "Employee not found: " + request.employeePid());
        }
        dynamicDataService.update(MODEL_ORG_DEPARTMENT, pid,
            Map.of("org_dept_manager_id", request.employeePid()));
        return ApiResponse.success();
    }

    private void requireDepartmentExists(String pid) {
        if (dynamicDataService.getById(MODEL_ORG_DEPARTMENT, pid) == null) {
            throw new RootUnCheckedException(ResponseCode.NOT_FOUND,
                "Department not found: " + pid);
        }
    }

    // ==================== Employee Endpoints ====================

    /**
     * List employees in a department, optionally including sub-departments.
     */
    @GetMapping("/employees")
    public ApiResponse<PaginationResult<OrgEmployeeDTO>> getEmployeesByTenant(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String keyword) {
        PaginationResult<OrgEmployeeDTO> result = organizationService.getEmployeesByTenant(
            pageNum, pageSize, keyword);
        return ApiResponse.success(result);
    }

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
    @RequirePermission("org.team.manage")
    public ApiResponse<OrgEmployeeDTO> createEmployee(@Valid @RequestBody CreateEmployeeRequest request) {
        OrgEmployeeDTO employee = orgEmployeeService.createWithUser(request);
        return ApiResponse.success(employee);
    }

    /**
     * Link an existing tenant member to a new employee record.
     */
    @PostMapping("/employees/link")
    @RequirePermission("org.team.manage")
    public ApiResponse<OrgEmployeeDTO> linkMember(@Valid @RequestBody LinkMemberRequest request) {
        OrgEmployeeDTO employee = orgEmployeeService.linkMember(request);
        return ApiResponse.success(employee);
    }

    /**
     * Update an employee record via dynamic data service.
     */
    @PutMapping("/employees/{pid}")
    @RequirePermission("org.team.manage")
    public ApiResponse<Void> updateEmployee(
            @PathVariable String pid,
            @RequestBody @jakarta.validation.constraints.NotEmpty Map<String, Object> data) {
        dynamicDataService.update("org_employee", pid, data);
        return ApiResponse.success();
    }

    /**
     * Transfer an employee to a new department and/or position.
     */
    @PutMapping("/employees/{pid}/transfer")
    @RequirePermission("org.team.manage")
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
    @RequirePermission("org.team.manage")
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
        return ApiResponse.success(orgEmployeeService.getUnlinkedMembers(keyword));
    }
}

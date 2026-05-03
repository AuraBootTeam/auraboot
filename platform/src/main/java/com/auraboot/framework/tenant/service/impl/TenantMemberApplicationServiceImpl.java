package com.auraboot.framework.tenant.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.auth.service.PasswordManagementService;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.common.constant.StatusConstants;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.meta.dto.DynamicQueryRequest;
import com.auraboot.framework.meta.dto.PaginationResult;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.organization.service.TeamMemberService;
import com.auraboot.framework.tenant.dao.entity.TenantMember;
import com.auraboot.framework.tenant.dto.MemberQueryRequest;
import com.auraboot.framework.tenant.dto.MemberResponse;
import com.auraboot.framework.tenant.dto.TenantMemberImportError;
import com.auraboot.framework.tenant.dto.TenantMemberImportResult;
import com.auraboot.framework.tenant.dto.TenantMemberImportRow;
import com.auraboot.framework.tenant.service.TenantMemberApplicationService;
import com.auraboot.framework.tenant.service.TenantMemberService;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.service.UserService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@Transactional
public class TenantMemberApplicationServiceImpl implements TenantMemberApplicationService {

    private static final String MODEL_EMPLOYEE = "org_employee";
    private static final String MODEL_DEPARTMENT = "org_department";
    private static final String MODEL_POSITION = "org_position";
    private static final String EMP_NAME = "org_emp_name";
    private static final String EMP_EMAIL = "org_emp_email";
    private static final String EMP_PHONE = "org_emp_phone";
    private static final String EMP_DEPT_ID = "org_emp_dept_id";
    private static final String EMP_POSITION_ID = "org_emp_position_id";
    private static final String EMP_STATUS = "org_emp_status";
    private static final String EMP_TYPE = "org_emp_type";
    private static final String EMP_MEMBER_ID = "org_emp_member_id";
    private static final String EMP_USER_ID = "org_emp_user_id";
    private static final String DEPT_NAME = "org_dept_name";
    private static final String POS_NAME = "org_pos_name";
    private static final String TEMP_PASSWORD_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789!@#$";
    private static final SecureRandom RANDOM = new SecureRandom();
    
    @Autowired
    private TenantMemberService tenantMemberService;

    @Autowired
    private UserService userService;

    @Autowired
    private PasswordManagementService passwordManagementService;

    @Autowired
    private TeamMemberService teamMemberService;

    @Autowired
    private DynamicDataService dynamicDataService;
    
    @Override
    public PaginationResult<MemberResponse> searchMembers(MemberQueryRequest request, Long userId) {
        try {
            // 获取当前用户的租户ID
            Long tenantId = MetaContext.getCurrentTenantId();
            if (tenantId == null) {
                tenantId = tenantMemberService.getTenantIdByUserId(userId);
            }
            
            if (tenantId == null) {
                throw new BusinessException(ResponseCode.BadParam, "用户未加入任何租户");
            }
            
            // 分页查询成员
            Page<TenantMember> page = tenantMemberService.findMembers(
                request.getPageNum(), 
                request.getPageSize(), 
                tenantId,
                request.getKeyword(), 
                request.getMemberType(), 
                request.getStatus()
            );
            
            // 转换为响应对象
            List<MemberResponse> memberResponses = page.getRecords().stream()
                .map(this::convertToMemberResponse)
                .collect(Collectors.toList());


            PaginationResult<MemberResponse> memberResponsePaginationResult = PaginationResult.of(memberResponses, page.getTotal(), request.getPageNum(), request.getPageSize());

            return memberResponsePaginationResult;
            
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("搜索成员失败", e);
            throw new BusinessException(ResponseCode.BUSINESS_ERROR, "搜索成员失败: " + e.getMessage(), e);
        }
    }
    
    @Override
    public MemberResponse getMemberById(String memberPid, Long userId) {
        try {
            TenantMember member = tenantMemberService.findByPid(memberPid);
            if (member == null) {
                throw new BusinessException(ResponseCode.NOT_FOUND, "成员不存在");
            }

            // 验证权限：只能查看同租户的成员
            Long currentTenantId = MetaContext.getCurrentTenantId();
            if (currentTenantId == null) {
                currentTenantId = tenantMemberService.getTenantIdByUserId(userId);
            }

            if (!member.getTenantId().equals(currentTenantId)) {
                throw new BusinessException(ResponseCode.FORBIDDEN, "无权限查看该成员信息");
            }

            return convertToMemberResponse(member);

        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("获取成员信息失败，memberPid: {}", memberPid, e);
            throw new BusinessException(ResponseCode.BUSINESS_ERROR, "获取成员信息失败: " + e.getMessage(), e);
        }
    }
    
    @Override
    public boolean approveMember(String memberPid, String action, String reason, Long userId) {
        try {
            TenantMember member = tenantMemberService.findByPid(memberPid);
            if (member == null) {
                throw new BusinessException(ResponseCode.NOT_FOUND, "成员不存在");
            }

            // 验证权限：只能操作同租户的成员
            Long currentTenantId = MetaContext.getCurrentTenantId();
            if (currentTenantId == null) {
                currentTenantId = tenantMemberService.getTenantIdByUserId(userId);
            }

            if (!member.getTenantId().equals(currentTenantId)) {
                throw new BusinessException(ResponseCode.FORBIDDEN, "无权限操作该成员");
            }

            // 根据操作类型更新状态
            if ("approve".equals(action)) {
                member.setStatus(StatusConstants.ACTIVE);
                log.info("用户 {} 审批通过成员 {}", userId, memberPid);
            } else if ("reject".equals(action)) {
                member.setStatus(StatusConstants.REJECTED);
                
                // 将拒绝原因存储到extensions JSON字段中
                if (reason != null && !reason.trim().isEmpty()) {
                    try {
                        ObjectMapper objectMapper = new ObjectMapper();
                        ObjectNode extensionsNode;
                        
                        // 如果已有extensions数据，则解析现有数据
                        if (member.getExtensions() != null && !member.getExtensions().trim().isEmpty()) {
                            extensionsNode = (ObjectNode) objectMapper.readTree(member.getExtensions());
                        } else {
                            extensionsNode = objectMapper.createObjectNode();
                        }
                        
                        // 添加拒绝原因和拒绝时间
                        extensionsNode.put("rejectReason", reason.trim());
                        extensionsNode.put("rejectTime", Instant.now().toEpochMilli());
                        extensionsNode.put("rejectedBy", userId);
                        
                        member.setExtensions(objectMapper.writeValueAsString(extensionsNode));
                    } catch (Exception e) {
                        // §P2 best-effort: failure to persist the rejection reason
                        // into the extensions JSON must not block the primary
                        // rejection workflow — the member is still rejected.
                        log.warn("存储拒绝原因到extensions字段失败: {}", e.getMessage(), e);
                    }
                }
                
                log.info("用户 {} 拒绝成员 {}, 原因: {}", userId, memberPid, reason);
            } else {
                throw new BusinessException(ResponseCode.BadParam, "无效的操作类型: " + action);
            }
            
            member.setUpdatedBy(userId);
            member.setUpdatedAt(Instant.now());
            
            tenantMemberService.updateMember(member);
            return true;
            
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("审批成员失败，memberPid: {}, action: {}", memberPid, action, e);
            throw new BusinessException(ResponseCode.BUSINESS_ERROR, "审批成员失败: " + e.getMessage(), e);
        }
    }
    
    @Override
    public boolean updateMemberStatus(String memberPid, String status, String reason, Long userId) {
        try {
            TenantMember member = tenantMemberService.findByPid(memberPid);
            if (member == null) {
                throw new BusinessException(ResponseCode.NOT_FOUND, "成员不存在");
            }

            // 验证权限：只能操作同租户的成员
            Long currentTenantId = MetaContext.getCurrentTenantId();
            if (currentTenantId == null) {
                currentTenantId = tenantMemberService.getTenantIdByUserId(userId);
            }

            if (!member.getTenantId().equals(currentTenantId)) {
                throw new BusinessException(ResponseCode.FORBIDDEN, "无权限操作该成员");
            }

            // 根据状态调用相应的服务方法
            boolean result = false;
            switch (status) {
                case StatusConstants.ACTIVE:
                    result = tenantMemberService.activateMember(member.getId());
                    break;
                case StatusConstants.INACTIVE:
                    result = tenantMemberService.deactivateMember(member.getId());
                    break;
                case StatusConstants.SUSPENDED:
                    result = tenantMemberService.suspendMember(member.getId(), reason);
                    break;
                default:
                    throw new BusinessException(ResponseCode.BadParam, "无效的状态: " + status);
            }
            
            log.info("用户 {} 更新成员 {} 状态为 {}, 原因: {}", userId, memberPid, status, reason);
            return result;
            
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("更新成员状态失败，memberPid: {}, status: {}", memberPid, status, e);
            throw new BusinessException(ResponseCode.BUSINESS_ERROR, "更新成员状态失败: " + e.getMessage(), e);
        }
    }
    
    @Override
    public boolean removeMember(String memberPid, Long userId) {
        try {
            TenantMember member = tenantMemberService.findByPid(memberPid);
            if (member == null) {
                throw new BusinessException(ResponseCode.NOT_FOUND, "成员不存在");
            }

            // 验证权限：只能操作同租户的成员
            Long currentTenantId = MetaContext.getCurrentTenantId();
            if (currentTenantId == null) {
                currentTenantId = tenantMemberService.getTenantIdByUserId(userId);
            }

            if (!member.getTenantId().equals(currentTenantId)) {
                throw new BusinessException(ResponseCode.FORBIDDEN, "无权限操作该成员");
            }

            // 不能删除自己
            if (member.getUserId().equals(userId)) {
                throw new BusinessException(ResponseCode.BadParam, "不能删除自己");
            }
            
            boolean result = tenantMemberService.removeMember(member.getId());
            log.info("用户 {} 移除成员 {}", userId, memberPid);
            return result;
            
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("移除成员失败，memberPid: {}", memberPid, e);
            throw new BusinessException(ResponseCode.BUSINESS_ERROR, "移除成员失败: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean sendPasswordResetEmail(String memberPid, Long userId) {
        try {
            TenantMember member = tenantMemberService.findByPid(memberPid);
            if (member == null) {
                throw new BusinessException(ResponseCode.NOT_FOUND, "成员不存在");
            }

            Long currentTenantId = MetaContext.getCurrentTenantId();
            if (currentTenantId == null) {
                currentTenantId = tenantMemberService.getTenantIdByUserId(userId);
            }

            if (!member.getTenantId().equals(currentTenantId)) {
                throw new BusinessException(ResponseCode.FORBIDDEN, "无权限操作该成员");
            }

            User targetUser = userService.findByUserId(member.getUserId());
            if (targetUser == null) {
                throw new BusinessException(ResponseCode.NOT_FOUND, "成员关联用户不存在");
            }
            if (targetUser.getEmail() == null || targetUser.getEmail().isBlank()) {
                throw new BusinessException(ResponseCode.BadParam, "成员未配置邮箱，无法发送重置密码邮件");
            }

            passwordManagementService.sendPasswordResetEmail(targetUser.getId());
            log.info("用户 {} 为成员 {} 发送了重置密码邮件", userId, memberPid);
            return true;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("发送成员重置密码邮件失败，memberPid: {}", memberPid, e);
            throw new BusinessException(ResponseCode.BUSINESS_ERROR, "发送重置密码邮件失败: " + e.getMessage(), e);
        }
    }
    
    @Override
    public boolean batchRemoveMembers(List<String> memberPids, Long userId) {
        try {
            if (memberPids == null || memberPids.isEmpty()) {
                return true;
            }
            
            Long currentTenantId = MetaContext.getCurrentTenantId();
            if (currentTenantId == null) {
                currentTenantId = tenantMemberService.getTenantIdByUserId(userId);
            }
            
            for (String memberPid : memberPids) {
                TenantMember member = tenantMemberService.findByPid(memberPid);
                if (member == null) {
                    log.warn("成员不存在: {}", memberPid);
                    continue;
                }
                
                // 验证权限
                if (!member.getTenantId().equals(currentTenantId)) {
                    log.warn("无权限操作成员: {}", memberPid);
                    continue;
                }
                
                // 不能删除自己
                if (member.getUserId().equals(userId)) {
                    log.warn("不能删除自己: {}", memberPid);
                    continue;
                }
                
                tenantMemberService.removeMember(member.getId());
            }
            
            log.info("用户 {} 批量移除成员: {}", userId, memberPids);
            return true;
            
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("批量移除成员失败", e);
            throw new BusinessException(ResponseCode.BUSINESS_ERROR, "批量移除成员失败: " + e.getMessage(), e);
        }
    }
    
    @Override
    public List<Map<String, Object>> getMemberTeams(String memberPid) {
        TenantMember member = tenantMemberService.findByPid(memberPid);
        if (member == null) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "Member not found");
        }
        Long tenantId = MetaContext.getCurrentTenantId();
        return teamMemberService.getTeamMembershipsByUserId(member.getUserId(), tenantId);
    }

    @Override
    public Resource downloadImportTemplate() {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Members");
            String[] headers = {"姓名*", "邮箱*", "手机号", "部门", "职位"};
            String[] sample = {"张三", "zhangsan@example.com", "13800138000", "销售部", "销售经理"};

            CellStyle headerStyle = workbook.createCellStyle();
            Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);

            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers[i]);
                cell.setCellStyle(headerStyle);
                sheet.setColumnWidth(i, 20 * 256);
            }

            Row sampleRow = sheet.createRow(1);
            for (int i = 0; i < sample.length; i++) {
                sampleRow.createCell(i).setCellValue(sample[i]);
            }

            workbook.write(output);
            return new ByteArrayResource(output.toByteArray());
        } catch (IOException e) {
            throw new BusinessException(ResponseCode.SystemError, "生成导入模板失败: " + e.getMessage(), e);
        }
    }

    @Override
    public TenantMemberImportResult importMembers(MultipartFile file, Long userId) {
        throw new BusinessException(ResponseCode.BadParam, "当前版本请使用页面上传 Excel 文件");
    }

    @Override
    public TenantMemberImportResult importMembers(List<TenantMemberImportRow> rows, Long userId) {
        if (rows == null || rows.isEmpty()) {
            throw new BusinessException(ResponseCode.BadParam, "导入数据不能为空");
        }

        Long tenantId = resolveCurrentTenantId(userId);
        Map<String, String> departmentMap = null;
        Map<String, String> positionMap = null;
        List<TenantMemberImportError> errors = new ArrayList<>();
        int successCount = 0;
        int existingUserBoundCount = 0;
        int invitedCount = 0;
        int employeeCreatedCount = 0;
        int totalRows = 0;

        for (int index = 0; index < rows.size(); index++) {
            TenantMemberImportRow row = rows.get(index);
            String name = trimToNull(row.getName());
            String email = normalizeEmail(row.getEmail());
            String phone = trimToNull(row.getPhone());
            String departmentName = trimToNull(row.getDepartment());
            String positionName = trimToNull(row.getPosition());

            if (name == null && email == null && phone == null && departmentName == null && positionName == null) {
                continue;
            }
            totalRows++;

            try {
                if (name == null) {
                    throw new BusinessException(ResponseCode.BadParam, "姓名不能为空");
                }
                if (email == null) {
                    throw new BusinessException(ResponseCode.BadParam, "邮箱不能为空");
                }

                User user = userService.findByEmail(email);
                boolean existingUser = user != null;

                if (user == null) {
                    user = userService.signUp(email, generateTemporaryPassword(16), name);
                    if (phone != null) {
                        user.setMobile(phone);
                    }
                    user.setMustChangePassword(true);
                    userService.update(user);
                } else if (phone != null && (user.getMobile() == null || user.getMobile().isBlank())) {
                    user.setMobile(phone);
                    userService.update(user);
                }

                TenantMember existingMember = tenantMemberService.findByTenantIdAndUserId(tenantId, user.getId());
                if (existingMember != null) {
                    throw new BusinessException(ResponseCode.BadParam, "该邮箱已是当前租户成员");
                }

                String memberStatus = existingUser ? StatusConstants.ACTIVE : StatusConstants.PENDING;
                TenantMember member = tenantMemberService.addMember(user.getId(), tenantId, memberStatus);

                if (!existingUser) {
                    passwordManagementService.sendPasswordResetEmail(user.getId());
                    invitedCount++;
                } else {
                    existingUserBoundCount++;
                }

                if (departmentName != null || positionName != null) {
                    if (departmentName != null && departmentMap == null) {
                        departmentMap = loadNameToPidMap(MODEL_DEPARTMENT, DEPT_NAME);
                    }
                    if (positionName != null && positionMap == null) {
                        positionMap = loadNameToPidMap(MODEL_POSITION, POS_NAME);
                    }
                    String deptPid = resolveRelatedPid(departmentMap, departmentName, "部门");
                    String positionPid = resolveRelatedPid(positionMap, positionName, "职位");
                    createEmployeeRecord(name, email, phone, deptPid, positionPid, member, user, memberStatus);
                    employeeCreatedCount++;
                }

                successCount++;
            } catch (Exception e) {
                String reason = e instanceof BusinessException
                        ? e.getMessage()
                        : "导入失败: " + e.getMessage();
                errors.add(new TenantMemberImportError(index + 2, name, email, reason));
            }
        }

        return TenantMemberImportResult.builder()
                .totalRows(totalRows)
                .successCount(successCount)
                .errorCount(errors.size())
                .existingUserBoundCount(existingUserBoundCount)
                .invitedCount(invitedCount)
                .employeeCreatedCount(employeeCreatedCount)
                .errors(errors)
                .build();
    }

    /**
     * 转换为成员响应对象
     */
    private MemberResponse convertToMemberResponse(TenantMember member) {
        MemberResponse response = new MemberResponse();
        BeanUtils.copyProperties(member, response);
        
        // 获取用户信息
        if (member.getUserId() != null) {
            try {
                User user = userService.findByUserId(member.getUserId());
                if (user != null) {
                    MemberResponse.UserInfo userInfo = new MemberResponse.UserInfo();
                    userInfo.setId(user.getId());
                    userInfo.setPid(user.getPid());
                    userInfo.setUsername(user.getUserName());
                    userInfo.setEmail(user.getEmail());
                    userInfo.setPhone(user.getMobile());
//                    userInfo.setRealName(user.getRealName());
//                    userInfo.setAvatar(user.getAvatar());
                    response.setUser(userInfo);
                }
            } catch (Exception e) {
                log.warn("获取用户信息失败，userId: {}", member.getUserId(), e);
            }
        }
        
        return response;
    }

    private Long resolveCurrentTenantId(Long userId) {
        Long currentTenantId = MetaContext.getCurrentTenantId();
        if (currentTenantId == null) {
            currentTenantId = tenantMemberService.getTenantIdByUserId(userId);
        }
        if (currentTenantId == null) {
            throw new BusinessException(ResponseCode.BadParam, "用户未加入任何租户");
        }
        return currentTenantId;
    }

    private Map<String, String> loadNameToPidMap(String modelCode, String fieldCode) {
        DynamicQueryRequest request = DynamicQueryRequest.builder().pageNum(1).pageSize(500).build();
        PaginationResult<Map<String, Object>> result = dynamicDataService.list(modelCode, request);
        Map<String, String> mapping = new HashMap<>();
        for (Map<String, Object> record : result.getRecords()) {
            String name = trimToNull(asString(record.get(fieldCode)));
            String pid = asString(record.get("pid"));
            if (name != null && pid != null) {
                mapping.putIfAbsent(name.toLowerCase(), pid);
            }
        }
        return mapping;
    }

    private void createEmployeeRecord(
            String name,
            String email,
            String phone,
            String deptPid,
            String positionPid,
            TenantMember member,
            User user,
            String memberStatus) {
        Map<String, Object> employeeData = new HashMap<>();
        employeeData.put(EMP_NAME, name);
        employeeData.put(EMP_EMAIL, email);
        employeeData.put(EMP_PHONE, phone);
        employeeData.put(EMP_STATUS, memberStatus);
        employeeData.put(EMP_TYPE, "human");
        employeeData.put(EMP_MEMBER_ID, member.getPid());
        employeeData.put(EMP_USER_ID, user.getPid());
        if (deptPid != null) {
            employeeData.put(EMP_DEPT_ID, deptPid);
        }
        if (positionPid != null) {
            employeeData.put(EMP_POSITION_ID, positionPid);
        }
        Map<String, Object> created = dynamicDataService.create(MODEL_EMPLOYEE, employeeData);
        member.setEmployeeId(extractId(created));
        tenantMemberService.updateMember(member);
    }

    private Long extractId(Map<String, Object> record) {
        Object id = record.get("id");
        if (id instanceof Number number) {
            return number.longValue();
        }
        if (id instanceof String strId) {
            return Long.parseLong(strId);
        }
        throw new BusinessException(ResponseCode.SystemError, "无法解析创建后的员工 ID");
    }

    private Map<Integer, String> resolveImportHeaders(Row headerRow) {
        if (headerRow == null) {
            throw new BusinessException(ResponseCode.BadParam, "导入模板缺少表头");
        }
        Map<Integer, String> mapping = new HashMap<>();
        DataFormatter formatter = new DataFormatter();
        for (Cell cell : headerRow) {
            String raw = formatter.formatCellValue(cell);
            String normalized = normalizeHeader(raw);
            if (normalized != null) {
                mapping.put(cell.getColumnIndex(), normalized);
            }
        }
        return mapping;
    }

    private String normalizeHeader(String header) {
        String normalized = trimToNull(header);
        if (normalized == null) {
            return null;
        }
        normalized = normalized.replace("*", "").replace("（必填）", "").replace("(required)", "").trim().toLowerCase();
        return switch (normalized) {
            case "姓名", "name", "display name" -> "name";
            case "邮箱", "email", "e-mail" -> "email";
            case "手机号", "手机", "phone", "mobile" -> "phone";
            case "部门", "department" -> "department";
            case "职位", "position", "title" -> "position";
            default -> null;
        };
    }

    private boolean isEmptyRow(Row row, DataFormatter formatter) {
        if (row == null) {
            return true;
        }
        for (Cell cell : row) {
            if (trimToNull(formatter.formatCellValue(cell)) != null) {
                return false;
            }
        }
        return true;
    }

    private String readCell(Row row, Map<Integer, String> headerMap, String targetHeader, DataFormatter formatter) {
        for (Map.Entry<Integer, String> entry : headerMap.entrySet()) {
            if (!targetHeader.equals(entry.getValue())) {
                continue;
            }
            Cell cell = row.getCell(entry.getKey());
            return trimToNull(cell == null ? null : formatter.formatCellValue(cell));
        }
        return null;
    }

    private String resolveRelatedPid(Map<String, String> nameMap, String value, String label) {
        if (value == null) {
            return null;
        }
        String pid = nameMap.get(value.toLowerCase());
        if (pid == null) {
            throw new BusinessException(ResponseCode.BadParam, label + "不存在: " + value);
        }
        return pid;
    }

    private String normalizeEmail(String email) {
        String trimmed = trimToNull(email);
        return trimmed == null ? null : trimmed.toLowerCase();
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String generateTemporaryPassword(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(TEMP_PASSWORD_CHARS.charAt(RANDOM.nextInt(TEMP_PASSWORD_CHARS.length())));
        }
        return sb.toString();
    }

}

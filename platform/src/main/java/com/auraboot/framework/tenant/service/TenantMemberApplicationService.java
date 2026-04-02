package com.auraboot.framework.tenant.service;

import com.auraboot.framework.meta.dto.PaginationResult;
import com.auraboot.framework.tenant.dto.MemberQueryRequest;
import com.auraboot.framework.tenant.dto.MemberResponse;
import com.auraboot.framework.tenant.dto.TenantMemberImportResult;
import com.auraboot.framework.tenant.dto.TenantMemberImportRow;
import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 租户成员应用服务接口
 */
public interface TenantMemberApplicationService {
    
    /**
     * 搜索成员
     * @param request 查询请求
     * @param userId 当前用户ID
     * @return 分页结果
     */
    PaginationResult<MemberResponse> searchMembers(MemberQueryRequest request, Long userId);
    
    /**
     * 根据ID获取成员信息
     * @param memberPid 成员业务ID
     * @param userId 当前用户ID
     * @return 成员信息
     */
    MemberResponse getMemberById(String memberPid, Long userId);
    
    /**
     * 审批成员
     * @param memberPid 成员业务ID
     * @param action 操作类型 (APPROVE, REJECT)
     * @param reason 操作原因
     * @param userId 当前用户ID
     * @return 是否成功
     */
    boolean approveMember(String memberPid, String action, String reason, Long userId);
    
    /**
     * 更新成员状态
     * @param memberPid 成员业务ID
     * @param status 新状态
     * @param reason 操作原因
     * @param userId 当前用户ID
     * @return 是否成功
     */
    boolean updateMemberStatus(String memberPid, String status, String reason, Long userId);
    
    /**
     * 移除成员
     * @param memberPid 成员业务ID
     * @param userId 当前用户ID
     * @return 是否成功
     */
    boolean removeMember(String memberPid, Long userId);
    
    /**
     * 批量移除成员
     * @param memberPids 成员业务ID列表
     * @param userId 当前用户ID
     * @return 是否成功
     */
    boolean batchRemoveMembers(List<String> memberPids, Long userId);

    /**
     * Send a password reset email to the user's mailbox for this tenant member.
     */
    boolean sendPasswordResetEmail(String memberPid, Long userId);

    /**
     * Get team memberships for a specific member
     * @param memberPid member's business ID
     * @return list of team membership info
     */
    List<java.util.Map<String, Object>> getMemberTeams(String memberPid);

    /**
     * Download the tenant member Excel import template.
     */
    Resource downloadImportTemplate();

    /**
     * Batch import tenant members from Excel.
     */
    TenantMemberImportResult importMembers(MultipartFile file, Long userId);

    /**
     * Batch import tenant members from already parsed rows.
     */
    TenantMemberImportResult importMembers(List<TenantMemberImportRow> rows, Long userId);
}

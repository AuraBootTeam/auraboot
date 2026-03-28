package com.auraboot.framework.tenant.service;

import com.auraboot.framework.tenant.dao.entity.TenantMember;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

/**
 * 租户成员服务接口
 */
public interface TenantMemberService extends IService<TenantMember> {

    /**
     * 添加租户成员
     */
    TenantMember addMember(Long userId, Long tenantId, String status);

    /**
     * 更新成员信息
     */
    TenantMember updateMember(TenantMember member);

    /**
     * 根据租户ID查询成员列表
     */
    List<TenantMember> findByTenantId(Long tenantId);



    /**
     * 分页查询租户成员
     */
    Page<TenantMember> findMembers(int pageNum, int pageSize, Long tenantId,
                                   String keyword, String memberType, String status);



    /**
     * 激活成员
     */
    boolean activateMember(Long memberId);

    /**
     * 停用成员
     */
    boolean deactivateMember(Long memberId);

    /**
     * 暂停成员
     */
    boolean suspendMember(Long memberId, String reason);

    /**
     * 移除成员(逻辑删除)
     */
    boolean removeMember(Long memberId);






    /**
     * 查询即将离职的成员
     */
    List<TenantMember> findLeavingMembers(int days);


    /**
     * 根据用户ID获取所有租户ID列表
     * @param userId 用户ID
     * @return 租户ID列表
     */
    List<Long> getTenantIdsByUserId(Long userId);

    /**
     * 根据用户ID获取租户ID（单个）
     * 如果用户属于多个租户，会记录错误日志并抛出异常
     * @param userId 用户ID
     * @return 租户ID，如果用户不属于任何租户则返回null
     * @throws RuntimeException 当用户属于多个租户时
     */
    Long getTenantIdByUserId(Long userId);
    
    /**
     * 根据业务ID查询租户成员
     * @param pid 业务ID
     * @return 租户成员信息
     */
    TenantMember findByPid(String pid);
    
    /**
     * 根据租户ID和用户ID查询租户成员
     * @param tenantId 租户ID
     * @param userId 用户ID
     * @return 租户成员信息，如果不存在则返回null
     */
    TenantMember findByTenantIdAndUserId(Long tenantId, Long userId);

    /**
     * Get tenant name by tenant ID.
     */
    String getTenantNameById(Long tenantId);
}

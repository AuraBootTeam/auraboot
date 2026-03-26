package com.auraboot.framework.tenant.service;

import com.auraboot.framework.tenant.dao.entity.Invitation;

/**
 * 租户邀请码服务接口
 */
public interface TenantInviteService {
    

    /**
     * 生成邀请码（带过期时间）
     * @param userId 邀请人用户ID
     * @param expiryDays 过期天数
     * @return 邀请码
     */
    String generateInviteCode(Long userId, Integer expiryDays);
    
    /**
     * 获取当前有效的邀请码
     * @param userId 用户ID
     * @return 邀请信息
     */
    Invitation getCurrentValidInviteCode(Long userId);
    
    /**
     * 作废邀请码
     * @param userId 用户ID
     * @param code 邀请码
     * @return 是否成功
     */
    boolean revokeInviteCode(Long userId, String code);
    
    /**
     * 验证邀请码
     * @param code 邀请码
     * @return 是否有效
     */
    boolean validateInviteCode(String code);
    
    /**
     * 使用邀请码
     * @param code 邀请码
     * @param userId 使用者用户ID
     * @return 是否成功
     */
    boolean useInviteCode(String code, Long userId);


    Invitation createInvitation(Invitation invitation);




    /**
     * 根据邀请码查询邀请
     */
    Invitation findByInvitationCode(String invitationCode);



    /**
     * 生成邀请码
     * @param invitationId 邀请ID
     * @return 邀请码
     */
    String generateInvitationCode(Long invitationId);

    /**
     * 根据租户ID和邀请人用户ID查询有效的邀请码
     * @param tenantId 租户ID
     * @param inviterUserId 邀请人用户ID
     * @return 有效的邀请记录
     */
    Invitation findValidInvitationByInviter(Long tenantId, Long inviterUserId);


}
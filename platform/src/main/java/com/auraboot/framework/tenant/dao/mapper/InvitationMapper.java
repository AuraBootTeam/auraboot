package com.auraboot.framework.tenant.dao.mapper;

import com.auraboot.framework.tenant.dao.entity.Invitation;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 邀请数据访问层
 */
@Mapper
public interface InvitationMapper extends BaseMapper<Invitation> {

    /**
     * 根据邀请码查询邀请
     */
    @Select("SELECT * FROM ab_invitation WHERE invite_code = #{inviteCode}  ")
    Invitation findByInviteCode(@Param("inviteCode") String inviteCode);

    /**
     * 根据邀请人查询邀请列表
     */
    @Select("SELECT * FROM ab_invitation WHERE inviter_user_id = #{inviterUserId}   ORDER BY created_at DESC")
    List<Invitation> findByInviterUserId(@Param("inviterUserId") String inviterUserId);

    /**
     * 根据租户ID和邀请人用户ID查询有效的邀请码
     */
    @Select("SELECT * FROM ab_invitation WHERE  inviter_user_id = #{inviterUserId} " +
            "AND status = 'pending'   AND expired_at > NOW() " +
            "ORDER BY created_at DESC LIMIT 1")
    Invitation findValidInvitationByInviter(@Param("tenantId") Long tenantId, @Param("inviterUserId") Long inviterUserId);

}
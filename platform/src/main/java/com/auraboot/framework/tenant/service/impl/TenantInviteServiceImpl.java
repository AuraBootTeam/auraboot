package com.auraboot.framework.tenant.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.tenant.dao.entity.Invitation;
import com.auraboot.framework.tenant.dao.entity.TenantMember;
import com.auraboot.framework.tenant.dao.mapper.InvitationMapper;
import com.auraboot.framework.tenant.service.TenantInviteService;
import com.auraboot.framework.tenant.service.TenantMemberService;
import com.auraboot.framework.user.service.UserService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.IService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import com.auraboot.framework.common.constant.StatusConstants;


@Slf4j
@Service
public class TenantInviteServiceImpl  extends ServiceImpl<InvitationMapper, Invitation> implements IService<Invitation>,TenantInviteService {

    @Autowired
    private TenantMemberService tenantMemberService;
    
    @Autowired
    private UserService userService;

    @Resource
    private InvitationMapper invitationMapper;

    private static final String INVITATION_CODE_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final int INVITATION_CODE_LENGTH = 8;
    private static final int INVITATION_TOKEN_LENGTH = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    @Override
    public String generateInviteCode(Long userId, Integer expiryDays) {
        // 获取用户的租户ID
        Long tenantId = tenantMemberService.getTenantIdByUserId(userId);
        if (tenantId == null) {
            throw new BusinessException("用户未加入任何租户");
        }
        
        // 计算过期时间
        Date expiredAt = null;
        if (expiryDays != null && expiryDays > 0) {
            long expiredTime = System.currentTimeMillis() + (expiryDays * 24L * 60L * 60L * 1000L);
            expiredAt = new Date(expiredTime);
        }
        
        // 创建邀请记录
        Invitation invitation = new Invitation();
        invitation.setPid(UniqueIdGenerator.generate());
        invitation.setTenantId(tenantId);
        invitation.setInviterUserId(userId);
        invitation.setStatus(StatusConstants.ACTIVE);
        invitation.setExpiredAt(expiredAt != null ? expiredAt.toInstant() : null);
        invitation.setCreatedAt(Instant.now());
        invitation.setUpdatedAt(Instant.now());
        invitation.setDeletedFlag(false);
        // 生成邀请码和令牌
        invitation.setInviteCode(generateInvitationCode(null));

        // 设置过期时间（默认3天）
        if (invitation.getExpiredAt() == null) {
            Instant expiryDateTime = Instant.now().plus(3, ChronoUnit.DAYS);
            invitation.setExpiredAt(expiryDateTime);
        }
        
        // 保存邀请记录
        Invitation savedInvitation = this.createInvitation(invitation);
        
        log.info("用户 {} 生成邀请码: {}, 过期时间: {}", userId, savedInvitation.getInviteCode(), expiredAt);
        return savedInvitation.getInviteCode();
    }
    
    @Override
    public boolean validateInviteCode(String code) {

            Invitation invitation = this.findByInvitationCode(code);
            if (invitation == null) {
                return false;
            }
            
            // 检查是否已删除
            if (invitation.getDeletedFlag()) {
                return false;
            }

            // Only ACTIVE invite codes are valid.
            if (!"active".equalsIgnoreCase(invitation.getStatus())) {
                return false;
            }
            
            // 检查是否过期
            if (invitation.getExpiredAt() != null && invitation.getExpiredAt().isBefore(Instant.now())) {
                return false;
            }
            
            return true;
            

    }
    
    @Override
    @Transactional
    public boolean useInviteCode(String code, Long userId) {

            // 验证邀请码
            if (!validateInviteCode(code)) {
                log.warn("邀请码无效或已过期: {}", code);
                return false;
            }
            
            Invitation invitation = this.findByInvitationCode(code);
            if (invitation == null) {
                return false;
            }
            
            // 检查用户是否已经是该租户的成员
            TenantMember existingMember = tenantMemberService.findByTenantIdAndUserId(
                invitation.getTenantId(), userId);
            if (existingMember != null) {
                log.warn("用户 {} 已经是租户 {} 的成员", userId, invitation.getTenantId());
                return false;
            }

            
            // 保存成员记录 todo 确认 调用 addMember 方法
            TenantMember savedMember = tenantMemberService.addMember(userId,invitation.getTenantId(), StatusConstants.PENDING);
            

            
            log.info("用户 {} 成功使用邀请码 {} 加入租户 {}, 成员ID: {}", 
                userId, code, invitation.getTenantId(), savedMember.getId());
            
            return true;
            

    }
    
    @Override
    public Invitation getCurrentValidInviteCode(Long userId) {

        
        // 查找当前用户创建的有效邀请码
        return this.findValidInvitationByInviter(MetaContext.getCurrentTenantId(), userId);
    }
    
    @Override
    public boolean revokeInviteCode(Long userId, String code) {
        try {

            
            // 查找邀请码
            Invitation invitation = this.findByInvitationCode(code);
            if (invitation == null) {
                log.warn("邀请码不存在: {}", code);
                return false;
            }
            
            // 验证是否是该用户创建的邀请码
            if (!invitation.getInviterUserId().equals(userId) ) {
                log.warn("用户 {} 无权作废邀请码 {}", userId, code);
                return false;
            }
            
            // 更新邀请码状态为已过期
            invitation.setStatus(StatusConstants.EXPIRED);
            invitation.setUpdatedAt(Instant.now());
            this.updateById(invitation);
            
            log.info("用户 {} 成功作废邀请码: {}", userId, code);
            return true;
        } catch (Exception e) {
            log.error("作废邀请码失败", e);
            return false;
        }
    }



    @Override
    @Transactional
    public Invitation createInvitation(Invitation invitation) {



        save(invitation);
        return invitation;
    }



    @Override
    public Invitation findByInvitationCode(String invitationCode) {
        return invitationMapper.findByInviteCode(invitationCode);
    }










    @Override
    public String generateInvitationCode(Long invitationId) {
        StringBuilder code = new StringBuilder();
        for (int i = 0; i < INVITATION_CODE_LENGTH; i++) {
            code.append(INVITATION_CODE_CHARS.charAt(RANDOM.nextInt(INVITATION_CODE_CHARS.length())));
        }

        // 确保生成的邀请码唯一
        String generatedCode = code.toString();

        int count = 0;
        while (findByInvitationCode(generatedCode) != null) {
            count++;
            code = new StringBuilder();
            for (int i = 0; i < INVITATION_CODE_LENGTH; i++) {
                code.append(INVITATION_CODE_CHARS.charAt(RANDOM.nextInt(INVITATION_CODE_CHARS.length())));
            }
            generatedCode = code.toString();
        }

        if(count >=2){
            log.error("warning msg, but need to clean up history invitation code");
        }
        return generatedCode;
    }


















    @Override
    public Invitation findValidInvitationByInviter(Long tenantId, Long inviterUserId) {
        QueryWrapper<Invitation> wrapper = new QueryWrapper<>();
        wrapper.eq("tenant_id", tenantId)
                .eq("inviter_user_id", inviterUserId)
                .eq("status", StatusConstants.ACTIVE)
                .eq("deleted_flag", false)
                .gt("expired_at", Instant.now())
                .orderByDesc("created_at")
                .last("LIMIT 1");
        return getOne(wrapper);
    }
}

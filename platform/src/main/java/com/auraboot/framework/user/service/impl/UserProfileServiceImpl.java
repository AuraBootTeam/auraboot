package com.auraboot.framework.user.service.impl;

import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.exception.ValidationException;
import com.auraboot.framework.file.entity.FileEntity;
import com.auraboot.framework.file.service.FileService;
import com.auraboot.framework.user.converter.UserProfileConverter;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.dto.UpdateUserProfileRequest;
import com.auraboot.framework.user.dto.UserProfileResponse;
import com.auraboot.framework.user.service.UserProfileService;
import com.auraboot.framework.user.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 用户个人资料服务实现类
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserProfileServiceImpl implements UserProfileService {
    
    private final UserService userService;
    private final FileService fileService;
    private final UserProfileConverter userProfileMapper;
    
    @Override
    public UserProfileResponse getUserProfile(Long userId) {
        log.info("获取用户个人资料，用户ID: {}", userId);
        
        // 获取用户信息
        User user = userService.findByUserId(userId);
        if (user == null) {
            throw new ValidationException(ResponseCode.CommonValidationFailed, "用户不存在");
        }
        
        // 转换为响应DTO
        UserProfileResponse response = userProfileMapper.toUserProfileResponse(user);
        
        // 获取头像URL
        if (StringUtils.hasText(user.getImgId())) {
            try {
                String avatarUrl = fileService.getFileDownloadUrl(user.getImgId());
                response.setAvatarUrl(avatarUrl);
            } catch (Exception e) {
                log.warn("获取头像URL失败，用户ID: {}, 头像文件ID: {}, 错误: {}", userId, user.getImgId(), e.getMessage());
                response.setAvatarUrl(null);
            }
        }
        
        return response;
    }
    
    @Override
    @Transactional
    public UserProfileResponse updateUserProfile(Long userId, UpdateUserProfileRequest request) {
        log.info("更新用户个人资料，用户ID: {}, 请求: {}", userId, request);
        
        // 获取用户信息
        User user = userService.findByUserId(userId);
        if (user == null) {
            throw new ValidationException(ResponseCode.CommonValidationFailed, "用户不存在");
        }
        
        // 验证邮箱唯一性（如果邮箱有变更）
        if (StringUtils.hasText(request.getEmail()) && !request.getEmail().equals(user.getEmail())) {
            User existingUser = userService.findByEmail(request.getEmail());
            if (existingUser != null && !existingUser.getId().equals(userId)) {
                throw new ValidationException(ResponseCode.CommonValidationFailed, "邮箱已被其他用户使用");
            }
        }
        
        // 验证头像文件是否存在（如果有头像文件ID）
        if (StringUtils.hasText(request.getImgId())) {
            try {
                FileEntity fileEntity = fileService.findByPid(request.getImgId());
                if (fileEntity == null) {
                    throw new ValidationException(ResponseCode.CommonValidationFailed, "头像文件不存在");
                }
            } catch (Exception e) {
                log.warn("验证头像文件失败，文件ID: {}, 错误: {}", request.getImgId(), e.getMessage());
                throw new ValidationException(ResponseCode.CommonValidationFailed, "头像文件验证失败");
            }
        }
        
        // 更新用户信息
        userProfileMapper.updateUserFromRequest(user, request);
        
        // 保存更新
        userService.update(user);
        
        // 返回更新后的用户资料
        return getUserProfile(userId);
    }
}
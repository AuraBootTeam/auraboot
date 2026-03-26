package com.auraboot.framework.user.service;

import com.auraboot.framework.user.dto.UpdateUserProfileRequest;
import com.auraboot.framework.user.dto.UserProfileResponse;

/**
 * 用户个人资料服务接口
 */
public interface UserProfileService {
    
    /**
     * 获取用户个人资料
     * 
     * @param userId 用户ID
     * @return 用户个人资料响应
     */
    UserProfileResponse getUserProfile(Long userId);
    
    /**
     * 更新用户个人资料
     * 
     * @param userId 用户ID
     * @param request 更新请求
     * @return 更新后的用户个人资料响应
     */
    UserProfileResponse updateUserProfile(Long userId, UpdateUserProfileRequest request);
}
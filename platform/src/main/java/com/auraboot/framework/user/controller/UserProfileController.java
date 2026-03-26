package com.auraboot.framework.user.controller;

import com.auraboot.framework.application.annotation.CurrentUserId;
import com.auraboot.framework.auth.service.PasswordManagementService;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.exception.RootUnCheckedException;
import com.auraboot.framework.file.dto.FileUploadResponseDTO;
import com.auraboot.framework.file.service.FileService;
import com.auraboot.framework.user.dto.ChangePasswordRequest;
import com.auraboot.framework.user.dto.UpdateUserProfileRequest;
import com.auraboot.framework.user.dto.UserProfileResponse;
import com.auraboot.framework.user.service.UserProfileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import static com.auraboot.framework.common.constant.ResponseCode.BadParam;

/**
 * 用户个人资料控制器
 */
@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
@Tag(name = "用户个人资料", description = "用户个人资料管理相关接口")
public class UserProfileController {
    
    private final UserProfileService userProfileService;
    private final FileService fileService;
    private final PasswordManagementService passwordManagementService;
    
    /**
     * 获取当前用户个人资料
     */
    @GetMapping("/profile")
    @Operation(summary = "获取个人资料")
    public ApiResponse<UserProfileResponse> getUserProfile(@CurrentUserId Long userId) {
        UserProfileResponse response = userProfileService.getUserProfile(userId);
        return ApiResponse.success(response);
    }
    
    /**
     * 更新当前用户个人资料
     */
    @PutMapping("/profile")
    @Operation(summary = "更新个人资料")
    public ApiResponse<UserProfileResponse> updateUserProfile(
            @CurrentUserId Long userId,
            @RequestBody @Valid UpdateUserProfileRequest request) {
        UserProfileResponse response = userProfileService.updateUserProfile(userId, request);
        return ApiResponse.success(response);
    }
    
    /**
     * Change password for current user.
     */
    @PutMapping("/password")
    @Operation(summary = "修改密码")
    public ApiResponse<Void> changePassword(
            @CurrentUserId Long userId,
            @RequestBody @Valid ChangePasswordRequest request) {
        if (!request.getNewPassword().equals(request.getConfirmPassword())) {
            throw new RootUnCheckedException(BadParam, "New password and confirm password do not match");
        }
        passwordManagementService.changePassword(userId, request.getCurrentPassword(), request.getNewPassword());
        return ApiResponse.success(null);
    }

    /**
     * 上传用户头像
     */
    @PostMapping("/avatar/upload")
    @Operation(summary = "上传头像")
    public ApiResponse<FileUploadResponseDTO> uploadAvatar(
            @RequestParam("file") MultipartFile file,
            @CurrentUserId Long userId) {
        
        // 验证文件类型
        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            throw new RootUnCheckedException(
                    BadParam,"只能上传图片文件");

        }
        
        // 验证文件大小 (5MB)
        if (file.getSize() > 5 * 1024 * 1024) {
            throw new RootUnCheckedException(
                    BadParam,"头像文件大小不能超过5MB");
        }
        
        FileUploadResponseDTO response = fileService.uploadFile(file, userId);
        return ApiResponse.success(response);
    }
}
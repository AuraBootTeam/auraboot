package com.auraboot.framework.user.controller;

import com.auraboot.framework.application.annotation.CurrentUserId;
import com.auraboot.framework.auth.service.PasswordManagementService;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.exception.RootUnCheckedException;
import com.auraboot.framework.file.dto.FileUploadResponseDTO;
import com.auraboot.framework.file.service.FileService;
import com.auraboot.framework.permission.annotation.AuthenticatedAccess;
import com.auraboot.framework.permission.annotation.DisallowImpersonation;
import com.auraboot.framework.user.dto.ChangePasswordRequest;
import com.auraboot.framework.user.dto.UpdateUserProfileRequest;
import com.auraboot.framework.user.dto.UserProfileResponse;
import com.auraboot.framework.user.service.UserProfileService;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import static com.auraboot.framework.common.constant.ResponseCode.BadParam;
import static com.auraboot.framework.common.constant.ResponseCode.FORBIDDEN;

/**
 * 用户个人资料控制器
 */
@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
@Tag(name = "用户个人资料", description = "用户个人资料管理相关接口")
@ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "OK", useReturnTypeSchema = true),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid request", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Authentication required", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Permission denied", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Resource not found", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Conflict", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "413", description = "Upload too large", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "415", description = "Unsupported request media", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422", description = "Invalid business request", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "Internal error", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiResponse.class)))
})
@AuthenticatedAccess("operates only on the caller's own profile/password/avatar (@CurrentUserId)")
public class UserProfileController {
    
    private final UserProfileService userProfileService;
    private final FileService fileService;
    private final PasswordManagementService passwordManagementService;

    @Value("${security.password.self-service-enabled:false}")
    private boolean passwordSelfServiceEnabled;
    
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
    @DisallowImpersonation
    @Operation(summary = "修改密码")
    public ApiResponse<Void> changePassword(
            @CurrentUserId Long userId,
            @RequestBody @Valid ChangePasswordRequest request) {
        if (!passwordSelfServiceEnabled) {
            throw new RootUnCheckedException(
                    FORBIDDEN,
                    "Self-service password change is disabled. Contact an administrator.");
        }
        if (!request.getNewPassword().equals(request.getConfirmPassword())) {
            throw new RootUnCheckedException(BadParam, "New password and confirm password do not match");
        }
        passwordManagementService.changePassword(userId, request.getCurrentPassword(), request.getNewPassword());
        return ApiResponse.success(null);
    }

    /**
     * 上传用户头像
     */
    @PostMapping(value = "/avatar/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
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

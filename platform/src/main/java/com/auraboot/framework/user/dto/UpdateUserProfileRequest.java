package com.auraboot.framework.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 用户个人资料更新请求DTO
 */
@Data
public class UpdateUserProfileRequest {
    
    /**
     * 昵称
     */
    @Size(min = 1, max = 50, message = "昵称长度必须在1-50个字符之间")
    private String nickName;
    
    /**
     * 邮箱
     */
    @Email(message = "邮箱格式不正确")
    @Size(max = 100, message = "邮箱长度不能超过100个字符")
    private String email;
    
    /**
     * 手机号
     */
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String mobile;
    
    /**
     * 地区
     */
    @Size(max = 100, message = "地区长度不能超过100个字符")
    private String area;
    
    /**
     * 个性签名
     */
    @Size(max = 200, message = "个性签名长度不能超过200个字符")
    private String signature;
    
    /**
     * 头像文件ID
     */
    private String imgId;
}
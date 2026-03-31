package com.auraboot.framework.auth.dto;

import com.auraboot.framework.rbac.entity.Role;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.util.List;

/**
 * 用户完整信息响应DTO
 * 用于 /api/auth/me 接口
 */
@Data
public class UserInfoResponse {
    
    private UserDTO user;
    private PermissionsDTO permissions;
    private PreferencesDTO preferences;

    public UserInfoResponse(UserDTO user, PermissionsDTO permissions, PreferencesDTO preferences) {
        this.user = user;
        this.permissions = permissions;
        this.preferences = preferences;
    }
    
    /**
     * 用户基本信息DTO
     */
    @Data
    public static class UserDTO {
        private String id;           // 用户ID (String类型，方便前端处理)
        private String pid;          // 业务ID
        private String name;         // 用户名称（nickName或userName）
        private String email;        // 邮箱
        private String mobile;       // 手机号
        @JsonSerialize(using = ToStringSerializer.class)
        private Long tenantId;       // 当前租户ID
        private String tenantName;   // 当前租户名称
        private String imgId;        // 头像ID
    }
    
    /**
     * Permission信息DTO
     */
    @Data
    public static class PermissionsDTO {
        private List<RoleDTO> roles;
        private List<String> permissionCodes;
        
        public PermissionsDTO(List<RoleDTO> roles, List<String> permissionCodes) {
            this.roles = roles;
            this.permissionCodes = permissionCodes;
        }
    }
    
    /**
     * Resolved UI preferences (user > tenant > default cascade).
     */
    @Data
    public static class PreferencesDTO {
        private String timezone;
        private String dateFormat;
        private String datetimeFormat;
        private String timeFormat;

        public static final String DEFAULT_TIMEZONE = "UTC";
        public static final String DEFAULT_DATE_FORMAT = "YYYY-MM-DD";
        public static final String DEFAULT_DATETIME_FORMAT = "YYYY-MM-DD HH:mm:ss";
        public static final String DEFAULT_TIME_FORMAT = "HH:mm:ss";
    }

    /**
     * 角色DTO
     */
    @Data
    public static class RoleDTO {
        private Long id;
        private String code;
        private String name;
        private String type;
        
        public static RoleDTO fromEntity(Role role) {
            RoleDTO dto = new RoleDTO();
            dto.setId(role.getId());
            dto.setCode(role.getCode());
            dto.setName(role.getName());
            dto.setType(role.getType());
            return dto;
        }
    }
}

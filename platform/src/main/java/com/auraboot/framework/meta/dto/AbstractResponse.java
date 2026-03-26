package com.auraboot.framework.meta.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 响应DTO抽象基类
 * 包含所有响应DTO的通用字段，用于减少代码重复
 *
 * @author AuraBoot
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public abstract class AbstractResponse {
    
    /**
     * 主键ID
     */
    private Long id;
    
    /**
     * 父级ID
     */
    private String pid;
    
    /**
     * 租户ID
     */
    private Long tenantId;
    
      
    
    
    
    /**
     * 状态
     */
    private String status;
    
    /**
     * 删除状态
     */
    private Boolean deletedFlag;
    
    /**
     * 版本号
     */
    private Integer version;
    
    /**
     * 语义版本
     */
    private String semver;
    
    /**
     * 行版本
     */
    private Integer rowVersion;
    
    /**
     * 是否当前版本
     */
    private Boolean isCurrent;
    
    /**
     * 是否成功
     */
    private Boolean success;
    
    /**
     * 错误消息
     */
    private String message;
    
    /**
     * 创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    private LocalDateTime updatedAt;
    
    /**
     * 判断操作是否成功
     */
    public boolean isSuccess() {
        return success != null && success;
    }
}
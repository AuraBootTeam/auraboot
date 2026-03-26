package com.auraboot.framework.menu.entity;

import com.auraboot.framework.application.database.mybatis.ExtensionTypeHandler;
import com.auraboot.framework.menu.constant.MenuStatus;
import com.auraboot.framework.meta.entity.payload.ExtensionBean;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.type.JdbcType;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 菜单实体
 */
@Data
@Slf4j
@TableName("ab_menu")
public class Menu {
    

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;                    // 菜单ID
    private Long tenantId;              // 所属租户ID

    private String pid;                 // 业务ID(ULID)
    private Instant createdAt;          // 创建时间
    private Instant updatedAt;          // 更新时间
    
    private Long parentId;              // 父菜单ID
    private String code;                // 菜单唯一编码（用于去重）
    private String name;                // 菜单名称
    private String path;                // 路由路径
    private String component;           // 前端组件路径 todo delete 
    private String icon;                // 图标

    /**
     * see @LinkType
     */
    private Integer type;               // 目录=0，菜单=1

    /**
     * Associated permission code for menu access control
     */
    private String permissionCode;



    private Boolean visible = true;     // 是否展示
    private Integer orderNo = 0;        // 排序号
    
    // 扩展字段
    private String i18nKey;             // 国际化key todo delete
    private String redirect;            // 重定向路径

    /**
     * Page key for linking to a page by its logical key.
     * Used during import to resolve the actual pagePid.
     */
    private String pageKey;

    /**
     * Page PID for linking to a page schema.
     * Used by frontend routing to render the associated page.
     */
    private String pagePid;

    @TableField(value = "extension", typeHandler = ExtensionTypeHandler.class, jdbcType = JdbcType.OTHER)
    private ExtensionBean extension;

    private MenuStatus status = MenuStatus.ACTIVE;   // 状态 todo use smallint
    private Boolean deletedFlag = false; // 逻辑删除标记
    

    @TableField(exist = false)
    private String linkTarget;          // 链接目标 (路由路径、Schema ID、外部URL等)
    @TableField(exist = false)
    private String linkParams;          // 链接参数 (JSON格式)
    
    // 审计字段
    private Long createdBy;           // 创建人
    private Long updatedBy;           // 更新人
    
    // 非数据库字段
    @TableField(exist =false)
    private List<Menu> children;        // 子菜单


}
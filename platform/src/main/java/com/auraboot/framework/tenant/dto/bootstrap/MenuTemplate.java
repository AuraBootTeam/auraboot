package com.auraboot.framework.tenant.dto.bootstrap;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 菜单模板
 *
 * 定义租户初始化时需要创建的菜单结构
 *
 * @author AuraBoot
 * @since 2.2.0
 */
@Data
public class MenuTemplate {
    
    /**
     * 菜单编码
     * 唯一标识菜单,例如: CONTENT_CREATION, AI_IMAGE_GENERATION
     * 必填字段
     */
    private String code;
    
    /**
     * 父菜单编码
     * 如果为null,表示这是一个顶级菜单
     * 用于构建菜单树形结构
     */
    private String parentCode;
    
    /**
     * 菜单名称
     * 显示给用户的菜单名称,例如: "节目制作", "AI生图"
     * 必填字段
     */
    private String name;
    
    /**
     * 路由路径
     * 前端路由路径,例如: "/content", "/content/ai-image"
     * 必填字段
     */
    private String path;
    
    /**
     * 组件名称
     * 前端组件名称,例如: "AiImagePage"
     * 对于目录类型的菜单可以为null
     */
    private String component;
    
    /**
     * 图标
     * 菜单图标标识,例如: "content-icon", "ai-icon"
     */
    private String icon;
    
    /**
     * 菜单类型
     * 0 = 目录 (不可点击,仅用于分组)
     * 1 = 菜单 (可点击,对应具体页面)
     */
    private Integer type;
    
    /**
     * 权限编码
     * 关联的权限代码,用于权限控制
     * 必须在权限表中存在,否则验证失败
     */
    private String permissionCode;
    
    /**
     * 排序号
     * 用于控制菜单显示顺序,数值越小越靠前
     */
    private Integer orderNo;
    
    /**
     * 是否可见
     * true = 显示在菜单中
     * false = 隐藏菜单(但路由仍然可访问)
     */
    private Boolean visible;

    /**
     * Localized Chinese name.
     */
    @JsonProperty("name:zh-CN")
    private String nameZhCN;

    /**
     * Localized English name.
     */
    @JsonProperty("name:en")
    private String nameEn;

    /**
     * Extension properties stored in JSONB.
     * Supports platform visibility via "platforms" key:
     *   null or absent → visible on all platforms (default)
     *   ["web"] → web only
     *   ["mobile"] → mobile only
     *   ["web", "mobile"] → both (explicit)
     */
    private Map<String, Object> extension;

    /**
     * Captures all "name:*" localized name entries from JSON beyond the hardcoded nameZhCN/nameEn.
     */
    @JsonIgnore
    private Map<String, String> localizedNames = new LinkedHashMap<>();

    @JsonIgnore
    private Map<String, Object> unknownFields;

    @JsonAnySetter
    public void setUnknownField(String key, Object value) {
        if (unknownFields == null) {
            unknownFields = new HashMap<>();
        }
        if (key != null && key.startsWith("name:") && value instanceof String strVal) {
            if (localizedNames == null) {
                localizedNames = new LinkedHashMap<>();
            }
            String locale = key.substring("name:".length());
            if ("en".equals(locale)) locale = "en-US";
            localizedNames.put(locale, strVal);
            return;
        }
        unknownFields.put(key, value);
    }

    /**
     * Return a merged map of all localized names.
     * Includes entries from the dynamic localizedNames map plus the legacy nameZhCN/nameEn fields.
     */
    @JsonIgnore
    public Map<String, String> getAllLocalizedNames() {
        Map<String, String> result = new LinkedHashMap<>();
        if (localizedNames != null) result.putAll(localizedNames);
        if (nameZhCN != null && !nameZhCN.isBlank()) result.putIfAbsent("zh-CN", nameZhCN);
        if (nameEn != null && !nameEn.isBlank()) result.putIfAbsent("en-US", nameEn);
        return result;
    }
}

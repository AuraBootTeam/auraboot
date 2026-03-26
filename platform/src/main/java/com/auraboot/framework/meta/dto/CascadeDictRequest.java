package com.auraboot.framework.meta.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 级联字典请求DTO
 * 用于级联字典的参数映射和动态查询
 */
@Data
public class CascadeDictRequest {

    /**
     * 租户ID
     */
    private Long tenantId;

      

    

    /**
     * 字典编码
     */
    private String dictCode;

    /**
     * 父级值
     */
    private String parentValue;

    /**
     * 级联参数映射
     * key: 参数名, value: 参数值
     */
    private Map<String, String> cascadeParams;

    /**
     * 过滤条件
     */
    private Map<String, Object> filters;

    /**
     * 是否包含禁用项
     */
    private Boolean includeDisabled;

    /**
     * 最大层级深度
     */
    private Integer maxLevel;

    /**
     * 是否只返回叶子节点
     */
    private Boolean leafOnly;

    /**
     * 排序字段
     */
    private String sortField;

    /**
     * 排序方向
     */
    private String sortOrder;

    /**
     * 分页页码
     */
    private Integer pageNum;

    /**
     * 分页大小
     */
    private Integer pageSize;

    /**
     * 构造函数
     */
    public CascadeDictRequest() {
        this.includeDisabled = false;
        this.maxLevel = 10;
        this.leafOnly = false;
        this.sortField = "sortOrder";
        this.sortOrder = "asc";
        this.pageNum = 1;
        this.pageSize = 100;
    }

    /**
     * 构造函数
     * @param tenantId 租户ID
       
     * @param dictCode 字典编码
     */
    public CascadeDictRequest(  String dictCode) {
        this();

        this.dictCode = dictCode;
    }

    /**
     * 构造函数
     * @param tenantId 租户ID
       
     * @param dictCode 字典编码
     * @param parentValue 父级值
     */
    public CascadeDictRequest( String dictCode, String parentValue) {
        this(  dictCode);
        this.parentValue = parentValue;
    }

    /**
     * 添加级联参数
     * @param key 参数名
     * @param value 参数值
     */
    public void addCascadeParam(String key, String value) {
        if (cascadeParams == null) {
            cascadeParams = new java.util.HashMap<>();
        }
        cascadeParams.put(key, value);
    }

    /**
     * 添加过滤条件
     * @param key 字段名
     * @param value 字段值
     */
    public void addFilter(String key, Object value) {
        if (filters == null) {
            filters = new java.util.HashMap<>();
        }
        filters.put(key, value);
    }

    /**
     * 获取级联参数值
     * @param key 参数名
     * @return 参数值
     */
    public String getCascadeParam(String key) {
        return cascadeParams != null ? cascadeParams.get(key) : null;
    }

    /**
     * 获取过滤条件值
     * @param key 字段名
     * @return 字段值
     */
    public Object getFilter(String key) {
        return filters != null ? filters.get(key) : null;
    }

    /**
     * 检查是否有级联参数
     * @return 是否有级联参数
     */
    public boolean hasCascadeParams() {
        return cascadeParams != null && !cascadeParams.isEmpty();
    }

    /**
     * 检查是否有过滤条件
     * @return 是否有过滤条件
     */
    public boolean hasFilters() {
        return filters != null && !filters.isEmpty();
    }

    /**
     * 检查是否为根级查询
     * @return 是否为根级查询
     */
    public boolean isRootQuery() {
        return parentValue == null || parentValue.trim().isEmpty();
    }

    /**
     * 检查是否需要分页
     * @return 是否需要分页
     */
    public boolean needsPagination() {
        return pageNum != null && pageSize != null && pageSize > 0;
    }
}
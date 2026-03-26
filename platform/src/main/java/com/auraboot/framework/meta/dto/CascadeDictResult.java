package com.auraboot.framework.meta.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 级联字典结果DTO
 * 用于级联字典查询的结果返回
 */
@Data
public class CascadeDictResult {

    /**
     * 字典编码
     */
    private String dictCode;

    /**
     * 字典名称
     */
    private String dictName;

    /**
     * 父级值
     */
    private String parentValue;

    /**
     * 是否成功
     */
    private Boolean success;

    /**
     * 错误信息
     */
    private String errorMessage;

    /**
     * 字典项列表（平铺结构）
     */
    private List<DictItemData> items;

    /**
     * 字典树（层级结构）
     */
    private DictTreeNode tree;

    /**
     * 字典项映射（key-value形式）
     */
    private Map<String, String> itemMap;

    /**
     * 层级映射（level -> items）
     */
    private Map<Integer, List<DictItemData>> levelMap;

    /**
     * 父子关系映射（parentValue -> children）
     */
    private Map<String, List<DictItemData>> parentChildMap;

    /**
     * 总数量
     */
    private Long totalCount;

    /**
     * 当前页码
     */
    private Integer currentPage;

    /**
     * 页面大小
     */
    private Integer pageSize;

    /**
     * 最大层级
     */
    private Integer maxLevel;

    /**
     * 是否有更多数据
     */
    private Boolean hasMore;

    /**
     * 级联配置信息
     */
    private Object cascadeConfig;

    /**
     * 加载时间戳
     */
    private Long loadTimestamp;

    /**
     * 构造函数
     */
    public CascadeDictResult() {
        this.success = false;
        this.items = new java.util.ArrayList<>();
        this.itemMap = new java.util.HashMap<>();
        this.levelMap = new java.util.HashMap<>();
        this.parentChildMap = new java.util.HashMap<>();
        this.totalCount = 0L;
        this.currentPage = 1;
        this.pageSize = 100;
        this.maxLevel = 0;
        this.hasMore = false;
        this.loadTimestamp = System.currentTimeMillis();
    }

    /**
     * 构造函数
     * @param dictCode 字典编码
     */
    public CascadeDictResult(String dictCode) {
        this();
        this.dictCode = dictCode;
    }

    /**
     * 构造函数
     * @param dictCode 字典编码
     * @param parentValue 父级值
     */
    public CascadeDictResult(String dictCode, String parentValue) {
        this(dictCode);
        this.parentValue = parentValue;
    }

    /**
     * 添加字典项
     * @param item 字典项
     */
    public void addItem(DictItemData item) {
        if (item != null) {
            items.add(item);
            itemMap.put(item.getValue(), item.getLabel());
            
            // 更新层级映射
            Integer level = calculateLevel(item);
            levelMap.computeIfAbsent(level, k -> new java.util.ArrayList<>()).add(item);
            
            // 更新父子关系映射
            String parent = item.getParentValue() != null ? item.getParentValue() : "root";
            parentChildMap.computeIfAbsent(parent, k -> new java.util.ArrayList<>()).add(item);
            
            // 更新最大层级
            if (level > maxLevel) {
                maxLevel = level;
            }
        }
    }

    /**
     * 批量添加字典项
     * @param items 字典项列表
     */
    public void addItems(List<DictItemData> items) {
        if (items != null) {
            for (DictItemData item : items) {
                addItem(item);
            }
        }
    }

    /**
     * 计算字典项的层级
     * @param item 字典项
     * @return 层级（从0开始）
     */
    private Integer calculateLevel(DictItemData item) {
        if (item.getParentValue() == null || item.getParentValue().trim().isEmpty()) {
            return 0;
        }
        
        // 简单实现，实际可能需要递归计算
        // 这里假设层级信息已经在数据中维护
        return 1;
    }

    /**
     * 获取指定层级的字典项
     * @param level 层级
     * @return 字典项列表
     */
    public List<DictItemData> getItemsByLevel(Integer level) {
        return levelMap.getOrDefault(level, new java.util.ArrayList<>());
    }

    /**
     * 获取指定父级的子项
     * @param parentValue 父级值
     * @return 子项列表
     */
    public List<DictItemData> getChildrenByParent(String parentValue) {
        String key = parentValue != null ? parentValue : "root";
        return parentChildMap.getOrDefault(key, new java.util.ArrayList<>());
    }

    /**
     * 获取根级项
     * @return 根级项列表
     */
    public List<DictItemData> getRootItems() {
        return getChildrenByParent(null);
    }

    /**
     * 检查是否有数据
     * @return 是否有数据
     */
    public boolean hasData() {
        return items != null && !items.isEmpty();
    }

    /**
     * 检查是否为树形结构
     * @return 是否为树形结构
     */
    public boolean isTreeStructure() {
        return tree != null;
    }

    /**
     * 检查是否为平铺结构
     * @return 是否为平铺结构
     */
    public boolean isFlatStructure() {
        return !isTreeStructure() && hasData();
    }

    /**
     * 获取项目数量
     * @return 项目数量
     */
    public int getItemCount() {
        return items != null ? items.size() : 0;
    }

    /**
     * 设置成功状态
     */
    public void setSuccess() {
        this.success = true;
        this.errorMessage = null;
    }

    /**
     * 设置失败状态
     * @param errorMessage 错误信息
     */
    public void setFailure(String errorMessage) {
        this.success = false;
        this.errorMessage = errorMessage;
    }

    /**
     * 更新统计信息
     */
    public void updateStatistics() {
        this.totalCount = (long) getItemCount();
        this.loadTimestamp = System.currentTimeMillis();
    }
}
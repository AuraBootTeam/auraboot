package com.auraboot.framework.meta.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 字典树节点DTO
 * 用于级联字典的树形结构展示
 */
@Data
public class DictTreeNode {

    /**
     * 节点值
     */
    private String value;

    /**
     * 节点标签
     */
    private String label;

    /**
     * 节点描述
     */
    private String description;

    /**
     * 父节点值
     */
    private String parentValue;

    /**
     * 排序顺序
     */
    private Integer sortOrder;

    /**
     * 节点层级（从0开始）
     */
    private Integer level;

    /**
     * 是否为叶子节点
     */
    private Boolean isLeaf;

    /**
     * 是否展开
     */
    private Boolean expanded;

    /**
     * 是否选中
     */
    private Boolean selected;

    /**
     * 是否禁用
     */
    private Boolean disabled;

    /**
     * 子节点列表
     */
    private List<DictTreeNode> children;

    /**
     * 扩展属性
     */
    private Object extension;

    /**
     * 构造函数
     */
    public DictTreeNode() {
        this.level = 0;
        this.isLeaf = true;
        this.expanded = false;
        this.selected = false;
        this.disabled = false;
        this.children = new ArrayList<>();
        this.sortOrder = 0;
    }

    /**
     * 构造函数
     * @param value 值
     * @param label 标签
     */
    public DictTreeNode(String value, String label) {
        this();
        this.value = value;
        this.label = label;
    }

    /**
     * 构造函数
     * @param value 值
     * @param label 标签
     * @param parentValue 父值
     */
    public DictTreeNode(String value, String label, String parentValue) {
        this(value, label);
        this.parentValue = parentValue;
    }

    /**
     * 添加子节点
     * @param child 子节点
     */
    public void addChild(DictTreeNode child) {
        if (child != null) {
            this.children.add(child);
            child.setParentValue(this.value);
            child.setLevel(this.level + 1);
            this.isLeaf = false;
        }
    }

    /**
     * 移除子节点
     * @param child 子节点
     */
    public void removeChild(DictTreeNode child) {
        if (child != null) {
            this.children.remove(child);
            if (this.children.isEmpty()) {
                this.isLeaf = true;
            }
        }
    }

    /**
     * 获取子节点数量
     * @return 子节点数量
     */
    public int getChildCount() {
        return children != null ? children.size() : 0;
    }

    /**
     * 检查是否有子节点
     * @return 是否有子节点
     */
    public boolean hasChildren() {
        return children != null && !children.isEmpty();
    }

    /**
     * 检查是否为根节点
     * @return 是否为根节点
     */
    public boolean isRoot() {
        return parentValue == null || parentValue.trim().isEmpty();
    }

    /**
     * 获取显示文本
     * @return 显示文本
     */
    public String getDisplayText() {
        return label != null ? label : value;
    }

    /**
     * 获取完整路径（从根到当前节点）
     * @return 路径字符串
     */
    public String getPath() {
        if (isRoot()) {
            return value;
        }
        // 简单实现，实际可能需要递归构建完整路径
        return parentValue + "/" + value;
    }

    /**
     * 递归设置展开状态
     * @param expanded 展开状态
     */
    public void setExpandedRecursive(boolean expanded) {
        this.expanded = expanded;
        if (hasChildren()) {
            for (DictTreeNode child : children) {
                child.setExpandedRecursive(expanded);
            }
        }
    }

    /**
     * 递归查找节点
     * @param value 节点值
     * @return 找到的节点，未找到返回null
     */
    public DictTreeNode findNode(String value) {
        if (this.value != null && this.value.equals(value)) {
            return this;
        }
        
        if (hasChildren()) {
            for (DictTreeNode child : children) {
                DictTreeNode found = child.findNode(value);
                if (found != null) {
                    return found;
                }
            }
        }
        
        return null;
    }

    /**
     * 获取所有叶子节点
     * @return 叶子节点列表
     */
    @JsonIgnore
    public List<DictTreeNode> getLeafNodes() {
        List<DictTreeNode> leafNodes = new ArrayList<>();
        collectLeafNodes(leafNodes);
        return leafNodes;
    }

    /**
     * 递归收集叶子节点
     * @param leafNodes 叶子节点列表
     */
    private void collectLeafNodes(List<DictTreeNode> leafNodes) {
        if (isLeaf) {
            leafNodes.add(this);
        } else if (hasChildren()) {
            for (DictTreeNode child : children) {
                child.collectLeafNodes(leafNodes);
            }
        }
    }

    /**
     * 排序子节点
     */
    public void sortChildren() {
        if (hasChildren()) {
            children.sort((a, b) -> {
                int sortCompare = Integer.compare(
                    a.getSortOrder() != null ? a.getSortOrder() : 0,
                    b.getSortOrder() != null ? b.getSortOrder() : 0
                );
                if (sortCompare != 0) {
                    return sortCompare;
                }
                // 如果排序号相同，按值排序
                return a.getValue().compareTo(b.getValue());
            });
            
            // 递归排序子节点的子节点
            for (DictTreeNode child : children) {
                child.sortChildren();
            }
        }
    }
}

package com.auraboot.framework.meta.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 页面排序请求
 */

@Data
@AllArgsConstructor
@NoArgsConstructor
public class PageOrderRequest {
    
    private Long tenantId;
    
    private List<PageOrderItem> items;
    
    private List<PageOrder> pageOrders;
    
    private String orderBy;
    
    private String orderDirection;
    
    // 手动添加getter方法以解决编译问题
    public List<PageOrder> getPageOrders() {
        return pageOrders;
    }
    
    public void setPageOrders(List<PageOrder> pageOrders) {
        this.pageOrders = pageOrders;
    }
    
    /**
     * 页面排序项 (兼容性别名)
     */
    public static class PageOrder {
        
        private Long id;
        
        private Integer order;
        
        // Getters and Setters
        public Long getId() {
            return id;
        }
        
        public void setId(Long id) {
            this.id = id;
        }
        
        public Integer getOrder() {
            return order;
        }
        
        public void setOrder(Integer order) {
            this.order = order;
        }
    }
    
    /**
     * 页面排序项
     */
    public static class PageOrderItem {
        
        private Long pageId;
        
        private String pagePid;
        
        private Integer sortOrder;
        
        // Getters and Setters
        public Long getPageId() {
            return pageId;
        }
        
        public void setPageId(Long pageId) {
            this.pageId = pageId;
        }
        
        public String getPagePid() {
            return pagePid;
        }
        
        public void setPagePid(String pagePid) {
            this.pagePid = pagePid;
        }
        
        public Integer getSortOrder() {
            return sortOrder;
        }
        
        public void setSortOrder(Integer sortOrder) {
            this.sortOrder = sortOrder;
        }
    }
    

}
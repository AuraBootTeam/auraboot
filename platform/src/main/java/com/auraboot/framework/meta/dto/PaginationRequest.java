package com.auraboot.framework.meta.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 分页请求参数
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class PaginationRequest {
    // 当前页码，从1开始
    private Integer pageNum = 1;
    
    // 每页大小
    private Integer pageSize = 10;

    
    // 搜索关键词
    private String keyword;

}
package com.auraboot.framework.meta.dto;

import lombok.Data;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 字典项排序请求DTO
 * 用于字典项排序操作的参数封装
 */
@Data
public class DictItemSortRequest {

    /**
     * 字典项PID
     */
    @NotBlank(message = "字典项PID不能为空")
    private String pid;

    /**
     * 新排序号
     */
    @NotNull(message = "排序号不能为空")
    private Integer sortNo;

    /**
     * 构造函数
     */
    public DictItemSortRequest() {
    }

    /**
     * 构造函数
     * @param pid 字典项PID
     * @param sortNo 排序号
     */
    public DictItemSortRequest(String pid, Integer sortNo) {
        this.pid = pid;
        this.sortNo = sortNo;
    }
}
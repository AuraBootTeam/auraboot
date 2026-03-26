package com.auraboot.framework.common.dto;

import lombok.Data;

import java.util.List;

/**
 * 分页结果封装类
 * 用于统一分页查询结果的返回格式
 *
 * @param <T> 数据类型
 */
@Data
public class PageResult<T> {

    /**
     * 数据列表
     */
    private List<T> records;

    /**
     * 总记录数
     */
    private Long total;

    /**
     * 每页大小
     */
    private Long size;

    /**
     * 当前页码
     */
    private Long current;

    /**
     * 总页数
     */
    private Long pages;

    /**
     * 是否有上一页
     */
    private Boolean hasPrevious;

    /**
     * 是否有下一页
     */
    private Boolean hasNext;

    /**
     * 构造函数
     */
    public PageResult() {
        this.total = 0L;
        this.size = 20L;
        this.current = 1L;
        this.pages = 0L;
        this.hasPrevious = false;
        this.hasNext = false;
    }

    /**
     * 构造函数
     * @param records 数据列表
     * @param total 总记录数
     * @param size 每页大小
     * @param current 当前页码
     */
    public PageResult(List<T> records, Long total, Long size, Long current) {
        this.records = records;
        this.total = total;
        this.size = size;
        this.current = current;
        this.pages = (total + size - 1) / size;
        this.hasPrevious = current > 1;
        this.hasNext = current < this.pages;
    }

    /**
     * 设置分页信息并自动计算相关字段
     */
    public void setPageInfo(Long total, Long size, Long current) {
        this.total = total;
        this.size = size;
        this.current = current;
        this.pages = (total + size - 1) / size;
        this.hasPrevious = current > 1;
        this.hasNext = current < this.pages;
    }

    /**
     * 从 MyBatis-Plus Page 对象创建 PageResult
     * @param page MyBatis-Plus Page 对象
     * @param <T> 数据类型
     * @return PageResult 对象
     */
    public static <T> PageResult<T> of(com.baomidou.mybatisplus.extension.plugins.pagination.Page<T> page) {
        PageResult<T> result = new PageResult<>();
        result.setRecords(page.getRecords());
        result.setTotal(page.getTotal());
        result.setSize(page.getSize());
        result.setCurrent(page.getCurrent());
        result.setPages(page.getPages());
        result.setHasPrevious(page.hasPrevious());
        result.setHasNext(page.hasNext());
        return result;
    }
}
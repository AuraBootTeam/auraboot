package com.auraboot.framework.meta.dto;

import lombok.Data;

import java.util.List;

@Data
public class PaginationResult<T> {

    private List<T> records;
    private Long total;
    private Integer page;
    private Integer pageSize;
    private Integer totalPages;

    public PaginationResult() {}

    public PaginationResult(List<T> records, Long total, Integer page, Integer pageSize) {
        this.records = records;
        this.total = total;
        this.page = page;
        this.pageSize = pageSize;
        this.totalPages = calculateTotalPages(total, pageSize);
    }

    public static <T> PaginationResult<T> of(List<T> records, Long total, Integer page, Integer pageSize) {
        return new PaginationResult<>(records, total, page, pageSize);
    }

    public static <T> PaginationResult<T> empty(Integer page, Integer pageSize) {
        return new PaginationResult<>(List.of(), 0L, page, pageSize);
    }

    private Integer calculateTotalPages(Long total, Integer pageSize) {
        if (total == null || total == 0 || pageSize == null || pageSize <= 0) {
            return 0;
        }
        return (int) Math.ceil((double) total / pageSize);
    }
}

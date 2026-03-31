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

    /**
     * Cursor for keyset pagination.
     * Contains the last record's ID from this page.
     * Pass this value as the "cursor" parameter to fetch the next page.
     * Null when using traditional offset pagination or when there are no more results.
     */
    private Long nextCursor;

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

    /**
     * Create a PaginationResult with keyset cursor.
     */
    public static <T> PaginationResult<T> ofCursor(List<T> records, Long total, Integer pageSize, Long nextCursor) {
        PaginationResult<T> result = new PaginationResult<>(records, total, 0, pageSize);
        result.setNextCursor(nextCursor);
        return result;
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

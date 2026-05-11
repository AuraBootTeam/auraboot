package com.auraboot.framework.common.util;

/**
 * Bounds pagination parameters before deriving SQL offsets or list slices.
 */
public final class PaginationSafetyUtils {

    private PaginationSafetyUtils() {
    }

    public static int pageNumber(int pageNum) {
        return Math.max(1, pageNum);
    }

    public static int zeroBasedPageNumber(int page) {
        return Math.max(0, page);
    }

    public static int pageSize(int pageSize, int maxPageSize) {
        int max = Math.max(1, maxPageSize);
        return Math.min(max, Math.max(1, pageSize));
    }

    public static int offset(int pageNum, int pageSize, int maxPageSize) {
        int safePageNum = pageNumber(pageNum);
        int safePageSize = pageSize(pageSize, maxPageSize);
        return multiplyPageOffset(safePageNum - 1, safePageSize);
    }

    public static int zeroBasedOffset(int page, int pageSize, int maxPageSize) {
        int safePage = zeroBasedPageNumber(page);
        int safePageSize = pageSize(pageSize, maxPageSize);
        return multiplyPageOffset(safePage, safePageSize);
    }

    private static int multiplyPageOffset(int pageIndex, int safePageSize) {
        try {
            return Math.multiplyExact(pageIndex, safePageSize);
        } catch (ArithmeticException e) {
            return Integer.MAX_VALUE;
        }
    }
}

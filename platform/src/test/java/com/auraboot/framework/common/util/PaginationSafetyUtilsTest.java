package com.auraboot.framework.common.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PaginationSafetyUtilsTest {

    @Test
    void clampsPageNumberAndSizeBeforeOffset() {
        assertThat(PaginationSafetyUtils.offset(-10, 5000, 100)).isZero();
        assertThat(PaginationSafetyUtils.offset(3, 5000, 100)).isEqualTo(200);
    }

    @Test
    void saturatesOverflowingOffset() {
        assertThat(PaginationSafetyUtils.offset(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE))
                .isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    void handlesZeroBasedPagination() {
        assertThat(PaginationSafetyUtils.zeroBasedOffset(-1, 50, 100)).isZero();
        assertThat(PaginationSafetyUtils.zeroBasedOffset(2, 50, 100)).isEqualTo(100);
    }
}

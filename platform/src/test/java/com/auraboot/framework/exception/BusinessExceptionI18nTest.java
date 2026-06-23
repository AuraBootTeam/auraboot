package com.auraboot.framework.exception;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code $i18n:} factory methods on {@link BusinessException}: the service layer emits a key
 * (+ optional args / cause) and the response boundary resolves it to the request locale.
 */
class BusinessExceptionI18nTest {

    @Test
    void i18nStoresKeyAsMessageWithoutArgs() {
        BusinessException ex = BusinessException.i18n("role.system_no_delete");

        assertThat(ex.getMessage()).isEqualTo("$i18n:role.system_no_delete");
        assertThat(ex.getI18nArgs()).isNull();
        assertThat(ex.getCause()).isNull();
    }

    @Test
    void i18nCarriesArgs() {
        BusinessException ex = BusinessException.i18n("role.not_found", 42L);

        assertThat(ex.getMessage()).isEqualTo("$i18n:role.not_found");
        assertThat(ex.getI18nArgs()).containsExactly(42L);
    }

    @Test
    void i18nWrapPreservesCauseAndSetsKey() {
        Exception cause = new IllegalStateException("db down");

        BusinessException ex = BusinessException.i18nWrap(cause, "permission.assign_failed");

        assertThat(ex.getMessage()).isEqualTo("$i18n:permission.assign_failed");
        assertThat(ex.getCause()).isSameAs(cause);
        assertThat(ex.getI18nArgs()).isNull();
    }

    @Test
    void i18nWrapCarriesArgsAndCause() {
        Exception cause = new RuntimeException("boom");

        BusinessException ex = BusinessException.i18nWrap(cause, "tenant.not_found", 7L);

        assertThat(ex.getMessage()).isEqualTo("$i18n:tenant.not_found");
        assertThat(ex.getCause()).isSameAs(cause);
        assertThat(ex.getI18nArgs()).containsExactly(7L);
    }

    @Test
    void i18nWrapToleratesNullCause() {
        BusinessException ex = BusinessException.i18nWrap(null, "permission.sync_failed");

        assertThat(ex.getMessage()).isEqualTo("$i18n:permission.sync_failed");
        assertThat(ex.getCause()).isNull();
    }
}

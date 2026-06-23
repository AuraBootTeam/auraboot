package com.auraboot.framework.meta.service;

/**
 * Data-access authorization result for custom controllers and PF4J handlers.
 *
 * <p>{@code filterClause} is normalized without a leading {@code WHERE} or
 * {@code AND}. Call {@link #asWhereConjunction()} when appending it to an
 * existing SQL {@code WHERE} clause.
 */
public record DataAccessAuthorizationContext(
        Long tenantId,
        Long userId,
        String resourceCode,
        String actionCode,
        String filterClause) {

    public boolean hasFilterClause() {
        return filterClause != null && !filterClause.isBlank();
    }

    public String asWhereConjunction() {
        return hasFilterClause() ? "AND " + filterClause : "";
    }
}

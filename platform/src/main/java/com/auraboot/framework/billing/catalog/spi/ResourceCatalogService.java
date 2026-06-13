package com.auraboot.framework.billing.catalog.spi;

import com.auraboot.framework.billing.catalog.model.ResourceCatalog;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * SPI for the Resource Catalog — the authoritative registry of all
 * billing/quota resource types known to the platform.
 *
 * <p>Enterprise or plugin modules may replace the default DB-backed
 * implementation by providing a bean that qualifies over the default
 * {@link com.auraboot.framework.billing.catalog.service.ResourceCatalogServiceImpl}.
 *
 * <p>All methods are read-only.  The catalog is populated at migration time
 * (seed rows) and extended at runtime only via the admin write path (not part
 * of this SPI).
 */
public interface ResourceCatalogService {

    /**
     * Look up a resource by its stable code (e.g. {@code "AI_TOKEN"}).
     *
     * @param resourceCode the machine-readable resource code, case-sensitive
     * @return the catalog entry, or empty if not found or not ACTIVE
     */
    Optional<ResourceCatalog> findByCode(String resourceCode);

    /**
     * Return all resources whose status is {@code ACTIVE}, ordered by category
     * then resource_code.
     */
    List<ResourceCatalog> listActive();

    /**
     * Return {@code true} if a resource with the given code exists and is
     * {@code ACTIVE}.
     *
     * @param resourceCode the machine-readable resource code
     */
    boolean isRegistered(String resourceCode);

    /**
     * Return the conversion factor for the given resource code.
     *
     * <p>Returns {@link BigDecimal#ONE} when the resource has no explicit
     * conversion factor (null in DB) — the raw usage value is already in the
     * billing unit.
     *
     * @param resourceCode the machine-readable resource code
     * @return the conversion factor, never null
     * @throws java.util.NoSuchElementException if the resource is not registered
     */
    BigDecimal conversionFactor(String resourceCode);
}

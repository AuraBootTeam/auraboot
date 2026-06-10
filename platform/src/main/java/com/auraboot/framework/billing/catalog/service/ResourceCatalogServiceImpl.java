package com.auraboot.framework.billing.catalog.service;

import com.auraboot.framework.billing.catalog.mapper.ResourceCatalogMapper;
import com.auraboot.framework.billing.catalog.model.ResourceCatalog;
import com.auraboot.framework.billing.catalog.spi.ResourceCatalogService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * Default DB-backed implementation of {@link ResourceCatalogService}.
 *
 * <p>This is the OSS production implementation — reads directly from the
 * {@code ab_billing_resource_catalog} table.  There is intentionally no NoOp
 * variant: the catalog is seeded at migration time and must always be
 * available.
 *
 * <p>All reads are {@code @Transactional(readOnly = true)} to allow the
 * connection pool to route them to read replicas.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResourceCatalogServiceImpl implements ResourceCatalogService {

    private static final String STATUS_ACTIVE = "ACTIVE";

    private final ResourceCatalogMapper resourceCatalogMapper;

    @Override
    @Transactional(readOnly = true)
    public Optional<ResourceCatalog> findByCode(String resourceCode) {
        return Optional.ofNullable(
                resourceCatalogMapper.selectOne(
                        new LambdaQueryWrapper<ResourceCatalog>()
                                .eq(ResourceCatalog::getResourceCode, resourceCode)
                                .eq(ResourceCatalog::getStatus, STATUS_ACTIVE)
                )
        );
    }

    @Override
    @Transactional(readOnly = true)
    public List<ResourceCatalog> listActive() {
        return resourceCatalogMapper.selectList(
                new LambdaQueryWrapper<ResourceCatalog>()
                        .eq(ResourceCatalog::getStatus, STATUS_ACTIVE)
                        .orderByAsc(ResourceCatalog::getCategory)
                        .orderByAsc(ResourceCatalog::getResourceCode)
        );
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isRegistered(String resourceCode) {
        return resourceCatalogMapper.exists(
                new LambdaQueryWrapper<ResourceCatalog>()
                        .eq(ResourceCatalog::getResourceCode, resourceCode)
                        .eq(ResourceCatalog::getStatus, STATUS_ACTIVE)
        );
    }

    @Override
    @Transactional(readOnly = true)
    public BigDecimal conversionFactor(String resourceCode) {
        ResourceCatalog entry = resourceCatalogMapper.selectOne(
                new LambdaQueryWrapper<ResourceCatalog>()
                        .eq(ResourceCatalog::getResourceCode, resourceCode)
                        .eq(ResourceCatalog::getStatus, STATUS_ACTIVE)
        );
        if (entry == null) {
            throw new NoSuchElementException(
                    "Resource not registered in catalog: " + resourceCode);
        }
        return entry.getConversionFactor() != null
                ? entry.getConversionFactor()
                : BigDecimal.ONE;
    }
}

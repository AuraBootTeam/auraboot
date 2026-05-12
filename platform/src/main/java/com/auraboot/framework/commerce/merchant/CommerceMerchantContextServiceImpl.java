package com.auraboot.framework.commerce.merchant;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.commerce.merchant.dto.MerchantCommerceContextResponse;
import com.auraboot.framework.tenant.dao.entity.Store;
import com.auraboot.framework.tenant.dao.mapper.StoreMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class CommerceMerchantContextServiceImpl implements CommerceMerchantContextService {

    private static final List<MerchantCommerceContextResponse.OperationLink> OPERATIONS = List.of(
            new MerchantCommerceContextResponse.OperationLink("products", "/merchant/products", true),
            new MerchantCommerceContextResponse.OperationLink("inventory", "/merchant/inventory", true),
            new MerchantCommerceContextResponse.OperationLink("orders", "/merchant/orders", true),
            new MerchantCommerceContextResponse.OperationLink("fulfillment", "/merchant/fulfillment", true),
            new MerchantCommerceContextResponse.OperationLink("settings", "/merchant/settings", true)
    );

    private final StoreMapper storeMapper;

    @Override
    public Optional<MerchantCommerceContextResponse> currentContext() {
        if (!MetaContext.exists() || MetaContext.getCurrentTenantId() == null) {
            return Optional.empty();
        }

        Long tenantId = MetaContext.getCurrentTenantId();
        List<MerchantCommerceContextResponse.StoreSummary> stores =
                storeMapper.findMerchantStoresByTenantId(tenantId)
                        .stream()
                        .map(this::toSummary)
                        .toList();

        MerchantCommerceContextResponse.StoreSummary selectedStore =
                stores.isEmpty() ? null : stores.get(0);

        return Optional.of(new MerchantCommerceContextResponse(
                tenantId,
                selectedStore,
                stores,
                OPERATIONS
        ));
    }

    private MerchantCommerceContextResponse.StoreSummary toSummary(Store store) {
        String handle = StringUtils.hasText(store.getCode()) ? store.getCode() : store.getPid();
        String storefrontPath = StringUtils.hasText(handle) ? "/s/" + handle : null;
        return new MerchantCommerceContextResponse.StoreSummary(
                store.getPid(),
                handle,
                store.getName(),
                store.getStatus(),
                storefrontPath
        );
    }
}

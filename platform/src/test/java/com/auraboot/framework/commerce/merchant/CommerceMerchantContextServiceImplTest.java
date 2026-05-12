package com.auraboot.framework.commerce.merchant;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.commerce.merchant.dto.MerchantCommerceContextResponse;
import com.auraboot.framework.tenant.dao.entity.Store;
import com.auraboot.framework.tenant.dao.mapper.StoreMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CommerceMerchantContextServiceImplTest {

    @Mock
    private StoreMapper storeMapper;

    @AfterEach
    void clearMetaContext() {
        MetaContext.clear();
    }

    @Test
    void currentContextReturnsTenantStoresAndOperationLinks() {
        MetaContext.setContext(101L, 7L, "USER_7", "merchant");

        Store store = new Store();
        store.setPid("STORE_demo");
        store.setCode("demo");
        store.setName("Demo Store");
        store.setStatus("active");

        when(storeMapper.findMerchantStoresByTenantId(101L)).thenReturn(List.of(store));

        CommerceMerchantContextService service = new CommerceMerchantContextServiceImpl(storeMapper);

        Optional<MerchantCommerceContextResponse> response = service.currentContext();

        assertTrue(response.isPresent());
        assertEquals(101L, response.get().tenantId());
        assertEquals("demo", response.get().selectedStore().handle());
        assertEquals("/s/demo", response.get().selectedStore().storefrontPath());
        assertEquals(1, response.get().stores().size());
        assertEquals("products", response.get().operations().get(0).code());
        assertEquals("/merchant/products", response.get().operations().get(0).route());
    }

    @Test
    void currentContextReturnsEmptyStoreStateForTenantWithoutStores() {
        MetaContext.setContext(101L, 7L, "USER_7", "merchant");
        when(storeMapper.findMerchantStoresByTenantId(101L)).thenReturn(List.of());

        CommerceMerchantContextService service = new CommerceMerchantContextServiceImpl(storeMapper);

        Optional<MerchantCommerceContextResponse> response = service.currentContext();

        assertTrue(response.isPresent());
        assertEquals(101L, response.get().tenantId());
        assertNull(response.get().selectedStore());
        assertTrue(response.get().stores().isEmpty());
        assertFalse(response.get().operations().isEmpty());
    }

    @Test
    void currentContextFailsClosedWithoutTenantContext() {
        CommerceMerchantContextService service = new CommerceMerchantContextServiceImpl(storeMapper);

        Optional<MerchantCommerceContextResponse> response = service.currentContext();

        assertTrue(response.isEmpty());
        verifyNoInteractions(storeMapper);
    }
}

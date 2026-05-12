package com.auraboot.framework.commerce.publicapi;

import com.auraboot.framework.commerce.publicapi.dto.StorefrontBootstrapResponse;
import com.auraboot.framework.commerce.publicapi.dto.StorefrontProductListResponse;
import com.auraboot.framework.tenant.dao.entity.Store;
import com.auraboot.framework.tenant.dao.mapper.StoreMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CommercePublicApiServiceImplTest {

    @Mock
    private StoreMapper storeMapper;

    @Test
    void bootstrapReadsStoreScopeAndCommerceSettingsFromStoreExtension() {
        Store store = new Store();
        store.setPid("STORE_demo");
        store.setCode("demo");
        store.setName("Demo Store");
        store.setTenantId(101L);
        store.setExtension("""
                {
                  "commerce": {
                    "locale": "en-US",
                    "currencyCode": "USD",
                    "theme": {
                      "themeId": "main",
                      "version": "1.0.0"
                    }
                  }
                }
                """);
        when(storeMapper.findPublicCandidatesByCode("demo")).thenReturn(List.of(store));

        CommercePublicApiService service =
                new CommercePublicApiServiceImpl(storeMapper, new ObjectMapper());

        Optional<StorefrontBootstrapResponse> response = service.bootstrap("demo");

        assertTrue(response.isPresent());
        assertEquals("demo", response.get().storeHandle());
        assertEquals("Demo Store", response.get().storeName());
        assertEquals("en-US", response.get().locale());
        assertEquals("USD", response.get().currencyCode());
        assertEquals("main", response.get().theme().themeId());
        assertEquals("1.0.0", response.get().theme().version());
    }

    @Test
    void bootstrapUsesExplicitDefaultsWhenCommerceSettingsAreMissing() {
        Store store = new Store();
        store.setCode("demo");
        store.setName("Demo Store");
        store.setTenantId(101L);
        when(storeMapper.findPublicCandidatesByCode("demo")).thenReturn(List.of(store));

        CommercePublicApiService service =
                new CommercePublicApiServiceImpl(storeMapper, new ObjectMapper());

        Optional<StorefrontBootstrapResponse> response = service.bootstrap("demo");

        assertTrue(response.isPresent());
        assertEquals("zh-CN", response.get().locale());
        assertEquals("CNY", response.get().currencyCode());
        assertEquals("default", response.get().theme().themeId());
    }

    @Test
    void productsRequireKnownStoreAndReturnEmptyCatalogContractForNow() {
        Store store = new Store();
        store.setCode("demo");
        store.setName("Demo Store");
        store.setTenantId(101L);
        when(storeMapper.findPublicCandidatesByCode("demo")).thenReturn(List.of(store));
        when(storeMapper.findPublicCandidatesByCode("missing")).thenReturn(List.of());

        CommercePublicApiService service =
                new CommercePublicApiServiceImpl(storeMapper, new ObjectMapper());

        Optional<StorefrontProductListResponse> products =
                service.products("demo", "all", "keyboard", null, 12);
        Optional<StorefrontProductListResponse> missing =
                service.products("missing", null, null, null, 12);

        assertTrue(products.isPresent());
        assertEquals(0, products.get().items().size());
        assertEquals(0L, products.get().total());
        assertTrue(missing.isEmpty());
    }

    @Test
    void publicStoreHandleFailsClosedWhenItMatchesMultipleStores() {
        Store first = new Store();
        first.setCode("demo");
        first.setName("First Store");
        first.setTenantId(101L);

        Store second = new Store();
        second.setCode("demo");
        second.setName("Second Store");
        second.setTenantId(202L);

        when(storeMapper.findPublicCandidatesByCode("demo")).thenReturn(List.of(first, second));

        CommercePublicApiService service =
                new CommercePublicApiServiceImpl(storeMapper, new ObjectMapper());

        Optional<StorefrontBootstrapResponse> response = service.bootstrap("demo");

        assertTrue(response.isEmpty());
    }
}

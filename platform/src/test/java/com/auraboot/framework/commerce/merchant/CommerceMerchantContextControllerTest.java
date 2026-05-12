package com.auraboot.framework.commerce.merchant;

import com.auraboot.framework.commerce.merchant.dto.MerchantCommerceContextResponse;
import com.auraboot.framework.common.dto.ApiResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommerceMerchantContextControllerTest {

    @Test
    void currentContextReturnsAuthenticatedMerchantContext() {
        CommerceMerchantContextController controller =
                new CommerceMerchantContextController(new FakeCommerceMerchantContextService(true));

        ApiResponse<MerchantCommerceContextResponse> response = controller.currentContext();

        assertTrue(response.isSuccess());
        assertNotNull(response.getData());
        assertEquals(101L, response.getData().tenantId());
        assertEquals("demo", response.getData().selectedStore().handle());
    }

    @Test
    void currentContextReturnsForbiddenWhenTenantContextIsMissing() {
        CommerceMerchantContextController controller =
                new CommerceMerchantContextController(new FakeCommerceMerchantContextService(false));

        ApiResponse<MerchantCommerceContextResponse> response = controller.currentContext();

        assertFalse(response.isSuccess());
        assertEquals("403", response.getCode());
        assertEquals("Merchant tenant context is required", response.getMessage());
    }

    private static final class FakeCommerceMerchantContextService
            implements CommerceMerchantContextService {
        private final boolean authenticated;

        private FakeCommerceMerchantContextService(boolean authenticated) {
            this.authenticated = authenticated;
        }

        @Override
        public Optional<MerchantCommerceContextResponse> currentContext() {
            if (!authenticated) {
                return Optional.empty();
            }
            MerchantCommerceContextResponse.StoreSummary store =
                    new MerchantCommerceContextResponse.StoreSummary(
                            "STORE_demo",
                            "demo",
                            "Demo Store",
                            "active",
                            "/s/demo"
                    );
            return Optional.of(new MerchantCommerceContextResponse(
                    101L,
                    store,
                    List.of(store),
                    List.of(new MerchantCommerceContextResponse.OperationLink(
                            "products",
                            "/merchant/products",
                            true
                    ))
            ));
        }
    }
}

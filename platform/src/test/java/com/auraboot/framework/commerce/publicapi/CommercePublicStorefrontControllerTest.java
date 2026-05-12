package com.auraboot.framework.commerce.publicapi;

import com.auraboot.framework.commerce.publicapi.dto.CompleteCheckoutRequest;
import com.auraboot.framework.commerce.publicapi.dto.CreateCheckoutRequest;
import com.auraboot.framework.commerce.publicapi.dto.CreateStorefrontCartRequest;
import com.auraboot.framework.commerce.publicapi.dto.StorefrontBootstrapResponse;
import com.auraboot.framework.commerce.publicapi.dto.StorefrontProductDetailResponse;
import com.auraboot.framework.commerce.publicapi.dto.StorefrontProductListResponse;
import com.auraboot.framework.common.dto.ApiResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommercePublicStorefrontControllerTest {

    @Test
    void bootstrapReturnsStoreScopeWithoutAdminContext() {
        CommercePublicApiService service = new FakeCommercePublicApiService();
        CommercePublicStorefrontController controller = new CommercePublicStorefrontController(service);

        ApiResponse<StorefrontBootstrapResponse> response = controller.bootstrap("demo");

        assertTrue(response.isSuccess());
        assertNotNull(response.getData());
        assertEquals("demo", response.getData().storeHandle());
        assertEquals("Demo Store", response.getData().storeName());
        assertEquals("en-US", response.getData().locale());
        assertEquals("USD", response.getData().currencyCode());
        assertEquals("main", response.getData().theme().themeId());
    }

    @Test
    void bootstrapReturnsNotFoundForUnknownStoreHandle() {
        CommercePublicStorefrontController controller =
                new CommercePublicStorefrontController(new FakeCommercePublicApiService());

        ApiResponse<StorefrontBootstrapResponse> response = controller.bootstrap("missing");

        assertFalse(response.isSuccess());
        assertEquals("404", response.getCode());
        assertEquals("Store not found", response.getMessage());
        assertEquals(Map.of("storeHandle", "missing"), response.getContext());
    }

    @Test
    void productListReturnsEmptyContractForScopedStore() {
        CommercePublicStorefrontController controller =
                new CommercePublicStorefrontController(new FakeCommercePublicApiService());

        ApiResponse<StorefrontProductListResponse> response =
                controller.products("demo", "all", "keyboard", null, 12);

        assertTrue(response.isSuccess());
        assertNotNull(response.getData());
        assertEquals(0, response.getData().items().size());
        assertEquals(0L, response.getData().total());
    }

    @Test
    void productDetailReturnsNotFoundUntilCatalogReadModelExists() {
        CommercePublicStorefrontController controller =
                new CommercePublicStorefrontController(new FakeCommercePublicApiService());

        ApiResponse<StorefrontProductDetailResponse> response = controller.product("demo", "sample-product");

        assertFalse(response.isSuccess());
        assertEquals("404", response.getCode());
        assertEquals("Product not found", response.getMessage());
        assertEquals(Map.of("storeHandle", "demo", "handle", "sample-product"), response.getContext());
    }

    @Test
    void cartAndCheckoutMutationsExposeUnsupportedFeatureUntilCommerceCoreExists() {
        CommercePublicStorefrontController controller =
                new CommercePublicStorefrontController(new FakeCommercePublicApiService());

        ApiResponse<?> cart = controller.createCart(
                "demo",
                new CreateStorefrontCartRequest(List.of())
        );
        ApiResponse<?> checkout = controller.createCheckout(
                new CreateCheckoutRequest("demo", "cart_1", List.of(), null)
        );
        ApiResponse<?> complete = controller.completeCheckout(
                "chk_1",
                new CompleteCheckoutRequest("idem_1", null)
        );

        assertFalse(cart.isSuccess());
        assertEquals("3", cart.getCode());
        assertEquals("Commerce cart runtime is not implemented yet", cart.getMessage());
        assertFalse(checkout.isSuccess());
        assertEquals("3", checkout.getCode());
        assertEquals("Commerce checkout runtime is not implemented yet", checkout.getMessage());
        assertFalse(complete.isSuccess());
        assertEquals("3", complete.getCode());
        assertEquals("Commerce checkout completion is not implemented yet", complete.getMessage());
    }

    private static final class FakeCommercePublicApiService implements CommercePublicApiService {
        @Override
        public Optional<StorefrontBootstrapResponse> bootstrap(String storeHandle) {
            if (!"demo".equals(storeHandle)) {
                return Optional.empty();
            }
            return Optional.of(new StorefrontBootstrapResponse(
                    "demo",
                    "Demo Store",
                    "en-US",
                    "USD",
                    new StorefrontBootstrapResponse.ThemeRef("main", "1.0.0", null),
                    List.of(new StorefrontBootstrapResponse.ChannelRef("online-store", "Online Store"))
            ));
        }

        @Override
        public Optional<StorefrontProductListResponse> products(
                String storeHandle,
                String collectionHandle,
                String query,
                String cursor,
                Integer pageSize
        ) {
            if (!"demo".equals(storeHandle)) {
                return Optional.empty();
            }
            return Optional.of(new StorefrontProductListResponse(List.of(), 0L, null));
        }

        @Override
        public Optional<StorefrontProductDetailResponse> product(String storeHandle, String handle) {
            return Optional.empty();
        }
    }
}


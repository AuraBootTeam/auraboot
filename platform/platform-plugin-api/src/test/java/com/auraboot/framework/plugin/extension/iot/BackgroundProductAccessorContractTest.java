package com.auraboot.framework.plugin.extension.iot;

import com.auraboot.framework.plugin.extension.iot.BackgroundProductAccessor.EventDef;
import com.auraboot.framework.plugin.extension.iot.BackgroundProductAccessor.ProductSchema;
import com.auraboot.framework.plugin.extension.iot.BackgroundProductAccessor.ProductView;
import com.auraboot.framework.plugin.extension.iot.BackgroundProductAccessor.PropertyDef;
import com.auraboot.framework.plugin.extension.iot.BackgroundProductAccessor.ServiceDef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract test for {@link BackgroundProductAccessor} using an in-memory fake.
 */
class BackgroundProductAccessorContractTest {

    private InMemoryProductAccessor accessor;

    @BeforeEach
    void setUp() {
        accessor = new InMemoryProductAccessor();
        accessor.putProduct(new ProductView(
                "temp-product",
                Map.of("zh-CN", "温度产品", "en-US", "Temperature Product"),
                "DEVICE",
                "JSON",
                "MQTT",
                100L));
        accessor.putProduct(new ProductView(
                "gw-product",
                Map.of("zh-CN", "网关产品", "en-US", "Gateway Product"),
                "GATEWAY",
                "JSON",
                "MQTT",
                200L));
        accessor.putSchema("temp-product", new ProductSchema(
                List.of(new PropertyDef("temperature", "float", true, "°C",
                        Map.of("min", -40.0, "max", 125.0))),
                List.of(new EventDef("over_temp", "struct", false, null,
                        Map.of("threshold", 90.0))),
                List.of(new ServiceDef("set_threshold", "struct", false, null, Map.of()))));
    }

    @Test
    void lookupByKey_returnsTenantScopedProduct() {
        Optional<ProductView> result = accessor.lookupByKey(100L, "temp-product");

        assertThat(result).isPresent();
        assertThat(result.get().nodeType()).isEqualTo("DEVICE");
        assertThat(result.get().name()).containsEntry("en-US", "Temperature Product");
    }

    @Test
    void lookupByKey_isolatesAcrossTenants() {
        assertThat(accessor.lookupByKey(200L, "temp-product")).isEmpty();
        assertThat(accessor.lookupByKey(100L, "gw-product")).isEmpty();
        assertThat(accessor.lookupByKey(200L, "gw-product")).isPresent();
    }

    @Test
    void lookupByKey_unknownReturnsEmpty() {
        assertThat(accessor.lookupByKey(100L, "missing")).isEmpty();
    }

    @Test
    void getSchema_returnsTslShape() {
        ProductSchema schema = accessor.getSchema("temp-product").orElseThrow();

        assertThat(schema.properties()).hasSize(1);
        PropertyDef temp = schema.properties().get(0);
        assertThat(temp.identifier()).isEqualTo("temperature");
        assertThat(temp.dataType()).isEqualTo("float");
        assertThat(temp.required()).isTrue();
        assertThat(temp.unit()).isEqualTo("°C");
        assertThat(temp.range()).containsEntry("min", -40.0).containsEntry("max", 125.0);

        assertThat(schema.events()).hasSize(1);
        assertThat(schema.events().get(0).identifier()).isEqualTo("over_temp");

        assertThat(schema.services()).hasSize(1);
        assertThat(schema.services().get(0).identifier()).isEqualTo("set_threshold");
    }

    @Test
    void getSchema_unknownProductReturnsEmpty() {
        assertThat(accessor.getSchema("does-not-exist")).isEmpty();
    }

    /** In-memory implementation used to assert the contract shape. */
    static final class InMemoryProductAccessor implements BackgroundProductAccessor {
        private final Map<String, ProductView> products = new HashMap<>();
        private final Map<String, ProductSchema> schemas = new HashMap<>();

        void putProduct(ProductView view) {
            products.put(view.tenantId() + ":" + view.productKey(), view);
        }

        void putSchema(String productKey, ProductSchema schema) {
            schemas.put(productKey, schema);
        }

        @Override
        public Optional<ProductView> lookupByKey(long tenantId, String productKey) {
            return Optional.ofNullable(products.get(tenantId + ":" + productKey));
        }

        @Override
        public Optional<ProductSchema> getSchema(String productKey) {
            return Optional.ofNullable(schemas.get(productKey));
        }
    }
}
